$ErrorActionPreference = 'Stop'
$deploy = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$upgrade = Join-Path $deploy 'scripts/upgrade-p0-database.ps1'
$mysql = (Get-Command mysql.exe).Source
$dump = (Get-Command mysqldump.exe).Source
$server = (Get-Command mysqld.exe).Source
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('course-p0-migration-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $scratch 'data'
New-Item -ItemType Directory -Path $data | Out-Null
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$serverProcess = $null
$oldPassword = $env:MYSQL_PWD
$oldUser = $env:MEDICAL_COURSE_DB_USER
$oldCoursePassword = $env:MEDICAL_COURSE_DB_PASSWORD
$serverUuid = $null

function Invoke-FixtureSql([string]$sql, [string]$database = '') {
    $arguments = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$port", '--user=root', '--batch', '--skip-column-names')
    if ($database) { $arguments += "--database=$database" }
    $result = $sql | & $mysql @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Fixture SQL failed: $result" }
    return $result
}

function Stop-OwnedP0Fixture {
    param([string]$Scratch,[string]$Data,[string]$Server,[int]$LauncherPid,
        [int]$Port,[string]$ServerUuid,[string]$MySqlCli,[string]$EvidenceRoot)
    $resolved = [IO.Path]::GetFullPath($Scratch)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^course-p0-migration-[a-f0-9]{32}$' -or
        [IO.Path]::GetFullPath($Data) -ine (Join-Path $resolved 'data') -or
        $Port -lt 1 -or $Port -gt 65535 -or $Port -in @(3318,8180,8188,10090)) {
        throw 'P0 fixture exact path/port validation failed; retained data'
    }
    $expectedBinary = [IO.Path]::GetFullPath($Server)
    $matchData = '(?:^|\s)--datadir="?' + [regex]::Escape($Data) + '"?(?:\s|$)'
    $owned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" -ErrorAction Stop |
        Where-Object { $_.CommandLine -match $matchData })
    $shutdown = $false
    if ($owned.Count) {
        if ($owned.Count -gt 2 -or -not $ServerUuid -or $LauncherPid -lt 1) { throw 'P0 fixture identity incomplete; shutdown refused' }
        foreach ($process in $owned) {
            if ([IO.Path]::GetFullPath($process.ExecutablePath) -ine $expectedBinary -or
                $process.CommandLine -notmatch ('(?:^|\s)--port=' + $Port + '(?:\s|$)') -or
                ($process.ProcessId -ne $LauncherPid -and $process.ParentProcessId -ne $LauncherPid)) {
                throw 'P0 fixture binary/ancestry/port mismatch; shutdown refused'
            }
        }
        $savedPassword = $env:MYSQL_PWD
        try {
            $env:MYSQL_PWD = ''
            $arguments = @('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$Port",'--user=root','--batch','--skip-column-names')
            $identity = @(& $MySqlCli @arguments --execute='SELECT @@server_uuid,@@port,@@datadir' 2>$null)
            if ($LASTEXITCODE -ne 0 -or $identity.Count -ne 1) { throw 'P0 SQL identity unreadable; shutdown refused' }
            $parts = $identity[0] -split [char]9
            if ($parts.Count -ne 3 -or $parts[0] -ne $ServerUuid -or [int]$parts[1] -ne $Port -or
                [IO.Path]::GetFullPath($parts[2]).TrimEnd('\','/') -ine [IO.Path]::GetFullPath($Data).TrimEnd('\','/')) {
                throw 'P0 SQL UUID/port/datadir mismatch; shutdown refused'
            }
            & $MySqlCli @arguments --execute='SHUTDOWN' 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'P0 SQL SHUTDOWN failed; retained data' }
            $shutdown = $true
        } finally { $env:MYSQL_PWD = $savedPassword }
        for ($wait=0; $wait -lt 40; $wait++) {
            $remaining = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" -ErrorAction Stop |
                Where-Object { $_.CommandLine -match $matchData })
            if (-not $remaining.Count) { break }
            Start-Sleep -Milliseconds 250
        }
    }
    $remaining = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" -ErrorAction Stop |
        Where-Object { $_.CommandLine -match $matchData })
    if ($remaining.Count) { throw 'P0 owned child exit incomplete; retained data' }
    New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
    $destination = Join-Path $EvidenceRoot ([IO.Path]::GetFileName($resolved))
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $resolved -File | Copy-Item -Destination $destination
    [pscustomobject]@{binary=$expectedBinary;datadir=$Data;port=$Port;uuid=$ServerUuid;launcherPid=$LauncherPid;sqlShutdown=$shutdown;remainingOwnedChildren=0;datadirRetained=$true} |
        ConvertTo-Json | Out-File -LiteralPath (Join-Path $destination 'cleanup-identity.json') -Encoding utf8
    Write-Host "P0 exact fixture SQL shutdown=$shutdown; owned children: 0; retained datadir: $Data"
}

function Get-Shape {
    $sql = @'
SELECT CONCAT('C:',TABLE_NAME,':',COLUMN_NAME,':',IS_NULLABLE,':',DATA_TYPE)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='medical_cloud_course'
AND ((TABLE_NAME='appointment' AND COLUMN_NAME IN ('started_at','booked_at'))
OR (TABLE_NAME='clinical_record' AND COLUMN_NAME IN ('complaint','notes','completed_at'))
OR (TABLE_NAME='payment_order' AND COLUMN_NAME='paid_at'))
UNION ALL
SELECT CONCAT('I:',INDEX_NAME,':',NON_UNIQUE,':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='medical_cloud_course'
AND TABLE_NAME='appointment' AND INDEX_NAME IN ('uk_course_patient_slot','idx_course_patient_slot')
GROUP BY INDEX_NAME,NON_UNIQUE ORDER BY 1;
'@
    return @(Invoke-FixtureSql $sql)
}

function Invoke-Upgrade([string]$database, [string]$backup, [string]$expectedUuid = $serverUuid) {
    $script:lastUpgradeOutput = @(& powershell.exe -NoProfile -NonInteractive -File $upgrade -MySqlCli $mysql -MySqlDumpCli $dump -HostName '127.0.0.1' -Port $port -Database $database -ExpectedServerUuid $expectedUuid -BackupPath $backup 2>&1)
    return $LASTEXITCODE
}

try {
    $base = Split-Path (Split-Path $server -Parent) -Parent
    & $server --no-defaults --initialize-insecure "--basedir=$base" "--datadir=$data" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL initialization failed' }
    $serverProcess = Start-Process -FilePath $server -ArgumentList @('--no-defaults', "--basedir=$base", "--datadir=$data", '--bind-address=127.0.0.1', "--port=$port", '--console') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $scratch 'server.err')
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root --execute='SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Disposable MySQL did not start: $(Get-Content (Join-Path $scratch 'server.err') -Tail 20)" }
    $serverUuid = @(Invoke-FixtureSql 'SELECT @@server_uuid')[0]
    $env:MEDICAL_COURSE_DB_USER = 'root'
    $env:MEDICAL_COURSE_DB_PASSWORD = ''
    $env:MYSQL_PWD = ''
    $freshSchema = Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8
    # The P0 upgrader accepts the eight-table legacy baseline, before audit exists.
    $legacy = $freshSchema -replace '(?s)\r?\nCREATE TABLE IF NOT EXISTS audit_event\s*\(.*?;\s*$', ''
    $legacy = $legacy -replace '(?m)^    (?:booked_at DATETIME\(6\)|started_at DATETIME|complaint VARCHAR\(500\)|notes VARCHAR\(2000\)|completed_at DATETIME|paid_at DATETIME) NULL,\r?\n', ''
    $legacy = $legacy.Replace('INDEX idx_course_patient_slot (patient_id, slot_id)', 'CONSTRAINT uk_course_patient_slot UNIQUE (patient_id, slot_id)')
    if ($legacy -eq $freshSchema -or $legacy -match '(?:booked_at DATETIME\(6\)|started_at DATETIME|completed_at DATETIME|paid_at DATETIME) NULL') { throw 'Legacy fixture could not be derived from current schema' }
    $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Legacy fixture schema failed' }
    Invoke-FixtureSql @'
INSERT INTO tenant VALUES ('t','Fixture','ACTIVE');
INSERT INTO department VALUES ('d','t','General','ACTIVE');
INSERT INTO user_account VALUES ('p','patient_fixture','x','PATIENT','ACTIVE',NULL),('dr','doctor_fixture','x','DOCTOR','ACTIVE','t');
INSERT INTO schedule_slot VALUES ('s','t','d','dr','2030-05-21','09:00:00','10:00:00',3,39.00,'PUBLISHED');
INSERT INTO appointment VALUES ('a-booked','p','s','t','dr',39.00,'BOOKED','2030-05-20');
INSERT INTO appointment VALUES ('a-completed','dr','s','t','dr',39.00,'COMPLETED','2030-05-19');
INSERT INTO clinical_record VALUES ('r','a-completed','t','dr','dr','old diagnosis','old advice');
INSERT INTO payment_order VALUES ('o','a-completed','dr','t',39.00,'PAID');
'@ 'medical_cloud_course' | Out-Null

    Describe 'preserving P0 database upgrade on disposable MySQL' {
        It 'rejects another database before backup or DDL' {
            $backup = Join-Path $scratch 'wrong.sql'
            (Invoke-Upgrade 'other_database' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (Get-Shape | Where-Object { $_ -like 'C:*' }).Count | Should Be 0
        }
        It 'rejects another MySQL instance identity before backup or DDL' {
            $backup = Join-Path $scratch 'wrong-instance.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup '00000000-0000-0000-0000-000000000000') | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 0
        }
        It 'preserves all old IDs and rows and creates the target shape' {
            $backup = Join-Path $scratch 'before.sql'
            $exit = Invoke-Upgrade 'medical_cloud_course' $backup
            if ($exit -ne 0) { throw "Upgrade failed: $script:lastUpgradeOutput" }
            (Test-Path $backup) | Should Be $true
            ((Get-Content $backup -Raw) -match 'a-booked') | Should Be $true
            $rows = @(Invoke-FixtureSql "SELECT CONCAT('A:',id,':',status) FROM appointment UNION ALL SELECT CONCAT('R:',id,':',appointment_id) FROM clinical_record UNION ALL SELECT CONCAT('O:',id,':',status) FROM payment_order ORDER BY 1" 'medical_cloud_course')
            ($rows -join '|') | Should Be 'A:a-booked:BOOKED|A:a-completed:COMPLETED|O:o:PAID|R:r:a-completed'
            (Invoke-FixtureSql 'SELECT paid_at IS NULL FROM payment_order WHERE id="o"' 'medical_cloud_course') | Should Be '1'
            $shape = @(Get-Shape)
            ($shape -contains 'C:appointment:started_at:YES:datetime') | Should Be $true
            ($shape -contains 'C:appointment:booked_at:YES:datetime') | Should Be $true
            (Invoke-FixtureSql 'SELECT booked_at IS NULL FROM appointment WHERE id="a-booked"' 'medical_cloud_course') | Should Be '1'
            ($shape -contains 'C:clinical_record:complaint:YES:varchar') | Should Be $true
            ($shape -contains 'C:clinical_record:notes:YES:varchar') | Should Be $true
            ($shape -contains 'C:clinical_record:completed_at:YES:datetime') | Should Be $true
            ($shape -contains 'C:payment_order:paid_at:YES:datetime') | Should Be $true
            ($shape -contains 'I:idx_course_patient_slot:1:patient_id,slot_id') | Should Be $true
            ($shape -contains 'I:uk_course_patient_slot:0:patient_id,slot_id') | Should Be $false
            Invoke-FixtureSql "UPDATE appointment SET status='CANCELED' WHERE id='a-booked'; INSERT INTO appointment (id,patient_id,slot_id,tenant_id,doctor_id,fee,status,booked_on) VALUES ('a-rebooked','p','s','t','dr',39.00,'BOOKED','2030-05-21')" 'medical_cloud_course' | Out-Null
            (Invoke-FixtureSql "SELECT COUNT(*) FROM appointment WHERE patient_id='p' AND slot_id='s'" 'medical_cloud_course') | Should Be '2'
        }
        It 'rejects a partially migrated schema before backup or further DDL' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course; CREATE DATABASE medical_cloud_course;' | Out-Null
            $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            Invoke-FixtureSql 'ALTER TABLE appointment ADD COLUMN started_at DATETIME NULL' 'medical_cloud_course' | Out-Null
            $backup = Join-Path $scratch 'partial.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape) -contains 'I:uk_course_patient_slot:0:patient_id,slot_id') | Should Be $true
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 1
        }
        It 'gives fresh databases the same target columns and non-unique index' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' | Out-Null
            $fresh = Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8
            $fresh | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            $LASTEXITCODE | Should Be 0
            $shape = @(Get-Shape)
            ($shape -contains 'C:appointment:started_at:YES:datetime') | Should Be $true
            ($shape -contains 'C:appointment:booked_at:YES:datetime') | Should Be $true
            ($shape -contains 'C:clinical_record:complaint:YES:varchar') | Should Be $true
            ($shape -contains 'C:clinical_record:notes:YES:varchar') | Should Be $true
            ($shape -contains 'C:clinical_record:completed_at:YES:datetime') | Should Be $true
            ($shape -contains 'C:payment_order:paid_at:YES:datetime') | Should Be $true
            ($shape -contains 'I:idx_course_patient_slot:1:patient_id,slot_id') | Should Be $true
            ($shape -contains 'I:uk_course_patient_slot:0:patient_id,slot_id') | Should Be $false
        }
        It 'rejects a same-named legacy database missing a required clinical column before backup or DDL' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' | Out-Null
            $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            $LASTEXITCODE | Should Be 0
            Invoke-FixtureSql 'ALTER TABLE clinical_record DROP COLUMN diagnosis' 'medical_cloud_course' | Out-Null
            $backup = Join-Path $scratch 'missing-diagnosis.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 0
        }
        It 'rejects missing clinical appointment uniqueness before backup or DDL' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' | Out-Null
            $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            $LASTEXITCODE | Should Be 0
            Invoke-FixtureSql 'ALTER TABLE clinical_record ADD INDEX idx_record_appointment (appointment_id), DROP INDEX uk_course_record_appointment' 'medical_cloud_course' | Out-Null
            $backup = Join-Path $scratch 'missing-record-unique.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 0
        }
        It 'rejects missing order appointment uniqueness before backup or DDL' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' | Out-Null
            $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            $LASTEXITCODE | Should Be 0
            Invoke-FixtureSql 'ALTER TABLE payment_order ADD INDEX idx_order_appointment (appointment_id), DROP INDEX uk_course_order_appointment' 'medical_cloud_course' | Out-Null
            $backup = Join-Path $scratch 'missing-order-unique.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 0
        }
        It 'rejects another patient-slot unique index before backup or DDL' {
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' | Out-Null
            $legacy | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root
            $LASTEXITCODE | Should Be 0
            Invoke-FixtureSql 'ALTER TABLE appointment ADD UNIQUE INDEX uk_other_patient_slot (patient_id, slot_id)' 'medical_cloud_course' | Out-Null
            $backup = Join-Path $scratch 'extra-patient-slot-unique.sql'
            (Invoke-Upgrade 'medical_cloud_course' $backup) | Should Not Be 0
            (Test-Path $backup) | Should Be $false
            (@(Get-Shape | Where-Object { $_ -like 'C:*' })).Count | Should Be 0
            (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='medical_cloud_course' AND TABLE_NAME='appointment' AND INDEX_NAME='uk_other_patient_slot'" 'medical_cloud_course') | Should Be '2'
        }
        It 'rejects withdrawal after a competing booking commits beyond an old MySQL snapshot' {
            $fixtureDatabase = 'course_slot_test_' + [guid]::NewGuid().ToString('N')
            Invoke-FixtureSql "CREATE DATABASE $fixtureDatabase CHARACTER SET utf8mb4" | Out-Null
            $priorUrl = $env:COURSE_MYSQL_TEST_URL
            $priorTestUser = $env:COURSE_MYSQL_TEST_USER
            $priorTestPassword = $env:COURSE_MYSQL_TEST_PASSWORD
            try {
                $env:COURSE_MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$port/${fixtureDatabase}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                $env:COURSE_MYSQL_TEST_USER = 'root'
                $env:COURSE_MYSQL_TEST_PASSWORD = ''
                $integrationOutput = @(& mvn -f (Join-Path $deploy '../pom.xml') '-Dtest=MySqlSlotSnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1)
                if ($LASTEXITCODE -ne 0) { throw (($integrationOutput | Select-Object -Last 32) -join [Environment]::NewLine) }
            } finally {
                $env:COURSE_MYSQL_TEST_URL = $priorUrl
                $env:COURSE_MYSQL_TEST_USER = $priorTestUser
                $env:COURSE_MYSQL_TEST_PASSWORD = $priorTestPassword
            }
        }
    }
} finally {
    try {
        $launcherPid = if ($serverProcess) { $serverProcess.Id } else { 0 }
        $repo = (Resolve-Path -LiteralPath (Join-Path $deploy '../..')).Path
        Stop-OwnedP0Fixture -Scratch $scratch -Data $data -Server $server -LauncherPid $launcherPid -Port $port -ServerUuid $serverUuid -MySqlCli $mysql -EvidenceRoot (Join-Path $repo '.superpowers/sdd/2026-10-04-medical-cloud-course-audit/task7')
    } finally {
        $env:MYSQL_PWD = $oldPassword
        $env:MEDICAL_COURSE_DB_USER = $oldUser
        $env:MEDICAL_COURSE_DB_PASSWORD = $oldCoursePassword
    }
}
