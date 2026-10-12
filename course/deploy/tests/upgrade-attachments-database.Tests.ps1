$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-fixture-ownership.ps1')
$deploy = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repo = (Resolve-Path -LiteralPath (Join-Path $deploy '../..')).Path
$upgrade = Join-Path $deploy 'scripts/upgrade-attachments-database.ps1'
$mysql = if (Get-Command mysql.exe -ErrorAction SilentlyContinue) { (Get-Command mysql.exe).Source } else { 'D:/MySQL/bin/mysql.exe' }
$dump = if (Get-Command mysqldump.exe -ErrorAction SilentlyContinue) { (Get-Command mysqldump.exe).Source } else { 'D:/MySQL/bin/mysqldump.exe' }
$server = [IO.Path]::GetFullPath($(if (Get-Command mysqld.exe -ErrorAction SilentlyContinue) { (Get-Command mysqld.exe).Source } else { 'D:/MySQL/bin/mysqld.exe' }))
$evidenceRoot = Join-Path $repo '.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/fixtures'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('course-audit-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $scratch 'data'
$prior = @{}
foreach ($name in @('MYSQL_PWD','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$businessTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order','audit_event')
$expectedAuditColumns = @(
    'id:varchar(36):NO', 'request_id:varchar(36):NO', 'actor_id:varchar(36):YES',
    'actor_role:varchar(32):YES', 'tenant_id:varchar(36):YES', 'event_type:varchar(64):NO',
    'object_type:varchar(32):NO', 'object_id:varchar(36):YES', 'result:varchar(16):NO',
    'reason_code:varchar(32):NO', 'recorded_at:datetime(6):NO'
)
$expectedAuditIndexes = @(
    'PRIMARY:0:id', 'uk_audit_request_event:0:request_id,event_type',
    'idx_audit_recorded:1:recorded_at,id', 'idx_audit_tenant:1:tenant_id,recorded_at,id',
    'idx_audit_event_result:1:event_type,result,recorded_at,id'
) | Sort-Object

function Invoke-FixtureSql([string]$sql, [string]$database = 'medical_cloud_course') {
    Assert-OwnedAuditFixture $owner $mysql
    $arguments = @('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$port",'--user=root','--batch','--skip-column-names')
    if ($database) { $arguments += "--database=$database" }
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        $result = @(& $mysql @arguments "--execute=$sql" 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'Disposable fixture SQL failed; retained evidence for inspection' }
        return $result
    } finally { $env:MYSQL_PWD = $saved }
}

function Reset-Baseline {
    Invoke-FixtureSql 'DROP DATABASE IF EXISTS medical_cloud_course' '' | Out-Null
    Invoke-FixtureSql $baselineSchema '' | Out-Null
    Invoke-FixtureSql @'
INSERT INTO tenant VALUES ('t','Fixture','ACTIVE');
INSERT INTO department VALUES ('d','t','General','ACTIVE');
INSERT INTO user_account VALUES ('p','patient_fixture','x','PATIENT','ACTIVE',NULL),('dr','doctor_fixture','x','DOCTOR','ACTIVE','t');
INSERT INTO doctor_profile VALUES ('dr','t','d','Fixture doctor');
INSERT INTO schedule_slot VALUES ('s','t','d','dr','2030-05-21','09:00:00','10:00:00',3,39.00,'PUBLISHED');
INSERT INTO appointment VALUES ('a','p','s','t','dr',39.00,'COMPLETED','2030-05-20',NULL,NULL);
INSERT INTO clinical_record VALUES ('r','a','t','dr','p','fixture diagnosis','fixture advice',NULL,NULL,NULL);
INSERT INTO payment_order VALUES ('o','a','p','t',39.00,'PAID',NULL);
'@ | Out-Null
}

function Get-BusinessDigest {
    Assert-OwnedAuditFixture $owner $mysql
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        $rows = @(& $digestDump --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root --set-gtid-purged=OFF --skip-comments --compact --no-create-info --skip-extended-insert --skip-add-locks --skip-disable-keys medical_cloud_course @businessTables 2>&1)
        if ($LASTEXITCODE -ne 0) { throw 'Business digest failed' }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($rows -join "`n"))) }
        finally { $sha.Dispose() }
    } finally { $env:MYSQL_PWD = $saved }
}

function Get-AuditColumns {
    return @(Invoke-FixtureSql "SELECT CONCAT(COLUMN_NAME,':',COLUMN_TYPE,':',IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_event' ORDER BY ORDINAL_POSITION")
}

function Get-AuditIndexes {
    return @(Invoke-FixtureSql "SELECT CONCAT(INDEX_NAME,':',NON_UNIQUE,':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_event' GROUP BY INDEX_NAME,NON_UNIQUE ORDER BY 1")
}

function Invoke-Upgrade([string]$backup, [string]$database = 'medical_cloud_course', [string]$uuid = $serverUuid, [int]$targetPort = $port, [string]$dumpCli = $dump) {
    Assert-OwnedAuditFixture $owner $mysql
    if (-not (Test-Path -LiteralPath $upgrade)) { $script:lastUpgradeOutput = 'Attachment upgrade script has not been implemented'; return -999 }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $script:lastUpgradeOutput = @(& powershell.exe -NoProfile -NonInteractive -File $upgrade -MySqlCli $mysql -MySqlDumpCli $dumpCli -HostName 127.0.0.1 -Port $targetPort -Database $database -ExpectedServerUuid $uuid -BackupPath $backup 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    [IO.File]::WriteAllText((Join-Path $scratch ('upgrade-' + [guid]::NewGuid().ToString('N') + '.log')), $script:lastUpgradeOutput)
    return $code
}


try {
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    if ($port -in @(3318,8180,8188,10090)) { throw 'Refused protected runtime port' }
    $base = Split-Path (Split-Path $server -Parent) -Parent
    $env:MYSQL_PWD = ''
    & $server --no-defaults --initialize-insecure "--basedir=$base" "--datadir=$data" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL initialization failed' }
    # Expected identity comes from this freshly initialized local datadir, never the endpoint.
    $serverUuid = Get-AuditFixtureOfflineUuid $data
    $serverProcess = Start-Process -FilePath $server -ArgumentList @('--no-defaults',"--basedir=`"$base`"","--datadir=`"$data`"",'--bind-address=127.0.0.1',"--port=$port",'--mysqlx=0','--console') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $scratch 'server.err') -RedirectStandardOutput (Join-Path $scratch 'server.out')
    $owner = [pscustomobject]@{Data=[IO.Path]::GetFullPath($data);Server=$server;Port=$port;Uuid=$serverUuid;LauncherPid=$serverProcess.Id;LauncherCreated=$serverProcess.StartTime}
    # Windows PowerShell JSON Date rounds to milliseconds; keep an exact ISO field
    # alongside it. The runtime guard continues using the precise in-memory value.
    $owner | Select-Object *,@{Name='LauncherCreatedIso';Expression={$_.LauncherCreated.ToString('o')}} |
        ConvertTo-Json | Out-File -LiteralPath (Join-Path $scratch 'launcher-identity.json') -Encoding utf8
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        & $mysql --no-defaults --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$port" --user=root --execute='SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable MySQL did not start' }
    Assert-OwnedAuditFixture $owner $mysql
    $env:MEDICAL_COURSE_DB_USER = 'root'
    $env:MEDICAL_COURSE_DB_PASSWORD = ''
    $baselineSchema = (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8) -replace '(?s)\r?\nCREATE TABLE IF NOT EXISTS file_metadata\s*\(.*?;\s*$', ''
    Reset-Baseline
    Write-Host "Disposable MySQL ready on port $port; evidence: $scratch"


    $env:MEDICAL_COURSE_DB_USER = 'root'
    $dumpCounter = Join-Path $scratch 'dump-calls.txt'
    $countedDump = Join-Path $scratch 'counted-dump.cmd'
    [IO.File]::WriteAllText($countedDump, "@echo off`r`necho call>>`"$dumpCounter`"`r`n`"$dump`" %*`r`nexit /b %errorlevel%`r`n")
    $digestDump = $dump
    $dump = $countedDump
    $failures = @()
    function Assert-Equal($actual, $expected, [string]$message) {
        if ([string]$actual -cne [string]$expected) { throw "$message; expected [$expected], actual [$actual]" }
    }
    function Dump-Count { if (Test-Path $dumpCounter) { return @(Get-Content $dumpCounter).Count }; return 0 }
    function Shape {
        return @(Invoke-FixtureSql "SHOW CREATE TABLE file_metadata") -join "`n"
    }
    function Begin-Observation {
        Invoke-FixtureSql "SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON" | Out-Null
        $script:marker = @(Invoke-FixtureSql 'SELECT NOW(6)')[0]
        $script:dumpBefore = Dump-Count
    }
    function Assert-NoDdlOrBackup {
        Assert-Equal (Dump-Count) $script:dumpBefore 'Refusal must not invoke backup'
        $ddl = @(Invoke-FixtureSql "SELECT argument FROM mysql.general_log WHERE event_time >= '$script:marker' AND command_type='Query' AND argument REGEXP '^(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|REPLACE|LOCK)'" '')
        Assert-Equal $ddl.Count 0 'Refusal must not invoke DDL or row writes'
        Invoke-FixtureSql 'SET GLOBAL general_log=OFF' | Out-Null
    }
    function Test-Case([string]$name, [scriptblock]$body) {
        try { & $body; Write-Host "PASS $name"; [IO.File]::AppendAllText((Join-Path $scratch 'test-results.log'), "PASS $name`r`n") }
        catch { $script:failures += "$name : $($_.Exception.Message)"; Write-Host "FAIL $name : $($_.Exception.Message)"; [IO.File]::AppendAllText((Join-Path $scratch 'test-results.log'), "FAIL $name : $($_.Exception.Message)`r`n") }
        finally { Invoke-FixtureSql 'SET GLOBAL general_log=OFF' | Out-Null }
    }
    Write-Host ("MySQL version: " + (Invoke-FixtureSql 'SELECT VERSION()'))
    Test-Case 'freshSchemaAnd003HaveIdenticalShape' {
        Reset-Baseline
        Assert-Equal (Invoke-Upgrade (Join-Path $scratch 'first.sql')) 0 'Attachment migration is required'
        $migrated = Shape
        Assert-Equal @(Invoke-FixtureSql "SELECT * FROM file_metadata").Count 0 'Fresh table must be empty'
        $columns = @(Invoke-FixtureSql "SELECT CONCAT(COLUMN_NAME,':',REPLACE(REPLACE(COLUMN_TYPE,'bigint(20)','bigint'),'int(11)','int'),':',IS_NULLABLE,':',IFNULL(COLLATION_NAME,'~')) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata' ORDER BY ORDINAL_POSITION") -join ' '
        Assert-Equal $columns 'id:varchar(36):NO:utf8mb4_unicode_ci tenant_id:varchar(36):NO:utf8mb4_unicode_ci appointment_id:varchar(36):NO:utf8mb4_unicode_ci patient_id:varchar(36):NO:utf8mb4_unicode_ci doctor_id:varchar(36):NO:utf8mb4_unicode_ci uploader_id:varchar(36):NO:utf8mb4_unicode_ci record_id:varchar(36):YES:utf8mb4_unicode_ci source_type:varchar(32):NO:utf8mb4_unicode_ci file_name:varchar(255):NO:utf8mb4_unicode_ci content_type:varchar(64):NO:utf8mb4_unicode_ci size_bytes:bigint:NO:~ sha256:varchar(64):NO:ascii_bin object_key:varchar(128):NO:ascii_bin upload_key:varchar(36):NO:ascii_bin state:varchar(32):NO:utf8mb4_unicode_ci created_at:datetime(6):NO:~ updated_at:datetime(6):NO:~ lease_until:datetime(6):YES:~ deleted_at:datetime(6):YES:~ attempt_token:varchar(36):YES:ascii_bin retry_count:int:NO:~ next_retry_at:datetime(6):YES:~ version:bigint:NO:~' 'Exact frozen fields and ASCII key types'
        Assert-Equal (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata'") 6 'Six non-cascading ownership foreign keys'
        Assert-Equal (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata' AND (DELETE_RULE NOT IN ('RESTRICT','NO ACTION') OR UPDATE_RULE NOT IN ('RESTRICT','NO ACTION'))") 0 'No foreign key cascades'
        $keys = @(Invoke-FixtureSql "SELECT CONCAT(INDEX_NAME,':',NON_UNIQUE,':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata' GROUP BY INDEX_NAME,NON_UNIQUE ORDER BY INDEX_NAME") -join ' '
        Assert-Equal $keys 'fk_attachment_doctor:1:doctor_id fk_attachment_patient:1:patient_id fk_attachment_record:1:record_id idx_attachment_cleanup:1:state,next_retry_at,id idx_attachment_lease:1:state,lease_until,id idx_attachment_listing:1:appointment_id,source_type,state,created_at,id idx_attachment_tenant:1:tenant_id,state PRIMARY:0:id uk_attachment_object:0:object_key uk_attachment_upload:0:uploader_id,upload_key' 'Exact unique, listing and cleanup indexes'
        Assert-Equal (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata' AND INDEX_NAME='idx_attachment_listing'") 5 'Listing index covers all five columns'
        Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' '' | Out-Null
        Invoke-FixtureSql (Get-Content -Raw -Encoding UTF8 (Join-Path $deploy 'schema.sql')) '' | Out-Null
        Assert-Equal (Shape) $migrated 'Fresh and migrated shape must agree'
    }
    Test-Case 'nineTableRowsRemainUnchanged' {
        Reset-Baseline
        Invoke-FixtureSql "INSERT INTO audit_event VALUES ('audit','request',NULL,NULL,NULL,'LOGIN','ACCOUNT',NULL,'FAILED','UNAUTHORIZED','2030-01-02 03:04:05.123456')" | Out-Null
        $before = Get-BusinessDigest
        Assert-Equal (Invoke-Upgrade (Join-Path $scratch 'nine.sql')) 0 'Migration succeeds'
        Assert-Equal (Get-BusinessDigest) $before 'All nine table rows are preserved'
    }
    Test-Case 'secondRunIsReadOnlyWithoutBackup' {
        Begin-Observation
        $before = Get-BusinessDigest
        Assert-Equal (Invoke-Upgrade (Join-Path $scratch 'nine.sql')) 0 'Same backup path permitted for read-only retry'
        Assert-Equal (Invoke-Upgrade (Join-Path $scratch 'repeat.sql')) 0 'Second migration is read-only'
        Assert-NoDdlOrBackup
        Assert-Equal (Get-BusinessDigest) $before 'Repeat does not rewrite rows'
    }
    Test-Case 'wrongUuidPortDatabaseAndBaselineRejected' {
        Reset-Baseline
        Begin-Observation
        Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'uuid.sql') -uuid '00000000-0000-0000-0000-000000000000') -ne 0) $true 'Wrong UUID rejected'
        Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'db.sql') -database 'other_database') -ne 0) $true 'Wrong database rejected'
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        try { $listener.Start(); Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'port.sql') -targetPort $listener.LocalEndpoint.Port) -ne 0) $true 'Wrong port rejected' } finally { $listener.Stop() }
        Assert-NoDdlOrBackup
        foreach ($mutation in @(
            'ALTER TABLE tenant MODIFY name VARCHAR(99) NOT NULL',
            'ALTER TABLE department MODIFY status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE''',
            'ALTER TABLE tenant MODIFY name VARCHAR(100) COLLATE utf8mb4_bin NOT NULL',
            'ALTER TABLE payment_order ADD INDEX alt_idx (appointment_id), DROP INDEX uk_course_order_appointment',
            'ALTER TABLE doctor_profile DROP FOREIGN KEY fk_course_doctor_department',
            'ALTER TABLE audit_event DROP INDEX idx_audit_tenant',
            'ALTER TABLE appointment DROP COLUMN booked_at',
            'CREATE TABLE unexpected (id INT)'
        )) {
            Reset-Baseline; Invoke-FixtureSql $mutation | Out-Null; Begin-Observation
            Assert-Equal ((Invoke-Upgrade (Join-Path $scratch ([guid]::NewGuid().ToString('N') + '.sql'))) -ne 0) $true 'Foreign baseline rejected'
            Assert-NoDdlOrBackup
        }
    }
    Test-Case 'partialNewTableRejected' {
        foreach ($mutation in @(
            'CREATE TABLE file_metadata (id VARCHAR(36) PRIMARY KEY)',
            'ALTER TABLE file_metadata DROP INDEX idx_attachment_cleanup',
            'ALTER TABLE file_metadata MODIFY upload_key VARCHAR(36) COLLATE ascii_general_ci NOT NULL',
            'ALTER TABLE file_metadata DROP FOREIGN KEY fk_attachment_tenant',
            'ALTER TABLE file_metadata MODIFY retry_count INT NOT NULL DEFAULT 0'
        )) {
            Reset-Baseline
            if ($mutation -notlike 'CREATE*') { Assert-Equal (Invoke-Upgrade (Join-Path $scratch ([guid]::NewGuid().ToString('N') + '.sql'))) 0 'Prepare complete new table' }
            Invoke-FixtureSql $mutation | Out-Null
            $before = Shape; Begin-Observation
            Assert-Equal ((Invoke-Upgrade (Join-Path $scratch ([guid]::NewGuid().ToString('N') + '.sql'))) -ne 0) $true 'Partial table rejected'
            Assert-NoDdlOrBackup; Assert-Equal (Shape) $before 'Refusal preserves partial table'
        }
    }
    Test-Case 'backupFailureAndExistingPathPreventDdl' {
        Reset-Baseline
        Begin-Observation
        Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'server.err')) -ne 0) $true 'Existing backup rejected'
        Assert-NoDdlOrBackup
        $before = Get-BusinessDigest
        $failedDump = Join-Path $scratch 'failed-dump.cmd'
        [IO.File]::WriteAllText($failedDump, "@echo off`r`necho call>>`"$dumpCounter`"`r`nexit /b 1`r`n")
        Begin-Observation
        Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'failed.sql') -dumpCli $failedDump) -ne 0) $true 'Dump failure rejected'
        Assert-Equal (Dump-Count) ($script:dumpBefore + 1) 'Exactly one failed backup attempt'
        $ddl = @(Invoke-FixtureSql "SELECT argument FROM mysql.general_log WHERE event_time >= '$script:marker' AND command_type='Query' AND argument REGEXP '^(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|REPLACE|LOCK)'" '')
        Assert-Equal $ddl.Count 0 'Failed backup prevents every DDL or row-write invocation'
        Invoke-FixtureSql 'SET GLOBAL general_log=OFF' | Out-Null
        Assert-Equal (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata'") 0 'Backup failure prevents DDL'
        Assert-Equal (Get-BusinessDigest) $before 'Backup failure preserves rows'
    }
    Test-Case 'partialDdlReportsActualShapeWithoutRetry' {
        Reset-Baseline
        # Execute the actual upgraded script with a native boundary that creates a
        # deliberately partial table and returns a failing native DDL exit status.
        $partialCli = Join-Path $scratch 'partial-mysql.ps1'
        $realMysql = $mysql
        $partialCounter = Join-Path $scratch 'partial-ddl-calls.txt'
        $boundary = @'
if (@($args | Where-Object { $_ -like '--execute=*' }).Count) {
    & '__MYSQL__' @args
} else {
    [IO.File]::AppendAllText('__COUNTER__', "DDL`r`n")
    & '__MYSQL__' @args '--execute=CREATE TABLE file_metadata (id VARCHAR(36) PRIMARY KEY)'
    $global:LASTEXITCODE = 1
}
'@
        [IO.File]::WriteAllText($partialCli, $boundary.Replace('__MYSQL__', $realMysql).Replace('__COUNTER__', $partialCounter))
        $mysql = $partialCli
        try {
            Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'partial-ddl.sql')) -ne 0) $true 'Partial DDL fails'
            Assert-Equal ($script:lastUpgradeOutput -match 'Actual attachment structure:.*file_metadata') $true 'Failure reports observed structure'
        } finally { $mysql = $realMysql }
        Assert-Equal @(Get-Content $partialCounter).Count 1 'Partial failure must not retry DDL automatically'
        Assert-Equal (Invoke-FixtureSql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_metadata'") 1 'Partial DDL retained'
        Begin-Observation
        Assert-Equal ((Invoke-Upgrade (Join-Path $scratch 'no-retry.sql')) -ne 0) $true 'Partial DDL refuses retry'
        Assert-NoDdlOrBackup
    }
    if ($failures.Count) { throw ($failures -join "`n") }
} finally {
    try {
        $resolved = [IO.Path]::GetFullPath($scratch)
        $root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^course-audit-[a-f0-9]{32}$') { throw 'Unexpected fixture path; refused cleanup' }
        $owned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object { $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)') })
        if ($owned.Count) {
            # Invoke-FixtureSql revalidates offline/process/SQL identity immediately before SHUTDOWN.
            Invoke-FixtureSql 'SHUTDOWN' '' | Out-Null
            for ($wait=0; $wait -lt 40; $wait++) {
                $stillOwned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object { $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)') })
                if (-not $stillOwned.Count) { break }; Start-Sleep -Milliseconds 250
            }
            if ($stillOwned.Count) { throw 'Fixture shutdown incomplete; retained datadir' }
            Write-Host "Fixture shutdown verified for $server and exact datadir $data; remaining owned processes: 0"
            [pscustomobject]@{binary=$owner.Server;datadir=$owner.Data;port=$owner.Port;uuid=$owner.Uuid;launcherPid=$owner.LauncherPid;launcherCreated=$owner.LauncherCreated;launcherCreatedIso=$owner.LauncherCreated.ToString('o');sqlShutdown=$true;remainingOwnedChildren=0;datadirRetained=$true} |
                ConvertTo-Json | Out-File -LiteralPath (Join-Path $scratch 'cleanup-identity.json') -Encoding utf8
        }
    } finally {
        # Preserve evidence under the ignored plan directory; MySQL needs the shorter ASCII datadir.
        try {
            New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
            $fixtureEvidence = Join-Path $evidenceRoot ([IO.Path]::GetFileName($scratch))
            New-Item -ItemType Directory -Path $fixtureEvidence -Force | Out-Null
            if (Test-Path -LiteralPath $scratch) { Get-ChildItem -LiteralPath $scratch -File | Copy-Item -Destination $fixtureEvidence }
        }
        finally { foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') } }
    }
}
