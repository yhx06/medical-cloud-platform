$ErrorActionPreference = 'Stop'
$deploy = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repo = (Resolve-Path -LiteralPath (Join-Path $deploy '../..')).Path
$upgrade = Join-Path $deploy 'scripts/upgrade-audit-database.ps1'
$mysql = (Get-Command mysql.exe).Source
$dump = (Get-Command mysqldump.exe).Source
$server = [IO.Path]::GetFullPath((Get-Command mysqld.exe).Source)
$evidenceRoot = Join-Path $repo '.superpowers/sdd/2026-10-04-medical-cloud-course-audit/fixtures'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('course-audit-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $scratch 'data'
$prior = @{}
foreach ($name in @('MYSQL_PWD','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$businessTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order')
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

function Reset-P0Fixture {
    Invoke-FixtureSql 'DROP DATABASE IF EXISTS medical_cloud_course' '' | Out-Null
    Invoke-FixtureSql $p0Schema '' | Out-Null
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
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        $rows = @(& $dump --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$port" --user=root --set-gtid-purged=OFF --skip-comments --compact --no-create-info --skip-extended-insert --skip-add-locks --skip-disable-keys medical_cloud_course @businessTables 2>&1)
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
    if (-not (Test-Path -LiteralPath $upgrade)) { $script:lastUpgradeOutput = 'Audit upgrade script has not been implemented'; return -999 }
    $script:lastUpgradeOutput = @(& powershell.exe -NoProfile -NonInteractive -File $upgrade -MySqlCli $mysql -MySqlDumpCli $dumpCli -HostName 127.0.0.1 -Port $targetPort -Database $database -ExpectedServerUuid $uuid -BackupPath $backup 2>&1) -join "`n"
    return $LASTEXITCODE
}

function Assert-Refusal([string]$backup) {
    (Test-Path -LiteralPath $backup) | Should Be $false
    @(Get-AuditColumns).Count | Should Be 0
    (Get-BusinessDigest) | Should BeExactly $businessDigestBefore
}

try {
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    if ($port -in @(3318,8180,8188)) { throw 'Refused protected runtime port' }
    $base = Split-Path (Split-Path $server -Parent) -Parent
    $env:MYSQL_PWD = ''
    & $server --no-defaults --initialize-insecure "--basedir=$base" "--datadir=$data" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL initialization failed' }
    $serverProcess = Start-Process -FilePath $server -ArgumentList @('--no-defaults',"--basedir=`"$base`"","--datadir=`"$data`"",'--bind-address=127.0.0.1',"--port=$port",'--mysqlx=0','--console') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $scratch 'server.err')
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        & $mysql --no-defaults --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$port" --user=root --execute='SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable MySQL did not start' }
    $serverUuid = @(Invoke-FixtureSql 'SELECT @@server_uuid' '')[0]
    $env:MEDICAL_COURSE_DB_USER = 'root'
    $env:MEDICAL_COURSE_DB_PASSWORD = ''
    $p0Schema = (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8) -replace '(?s)\r?\nCREATE TABLE IF NOT EXISTS audit_event\s*\(.*?;\s*$', ''
    Reset-P0Fixture
    Write-Host "Disposable MySQL ready on port $port; evidence: $scratch"

    Describe 'protected add-only audit migration on disposable MySQL' {
        It 'validP0ShapeAddsOnlyAuditTable' {
            Reset-P0Fixture
            $businessDigestBefore = Get-BusinessDigest
            $backup = Join-Path $scratch 'before-audit.sql'
            (Test-Path -LiteralPath $upgrade) | Should Be $true
            (Invoke-Upgrade $backup) | Should Be 0
            $auditColumns = @(Get-AuditColumns)
            $auditForeignKeys = @(Invoke-FixtureSql "SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='audit_event'")
            $businessDigestAfter = Get-BusinessDigest
            @($auditColumns).Count | Should Be 11
            @($auditForeignKeys).Count | Should Be 0
            $businessDigestAfter | Should BeExactly $businessDigestBefore
            ($auditColumns -join '|') | Should BeExactly ($expectedAuditColumns -join '|')
            ((Get-AuditIndexes) -join '|') | Should BeExactly ($expectedAuditIndexes -join '|')
            (Invoke-FixtureSql "SELECT CONCAT(ENGINE,':',TABLE_COLLATION) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_event'") | Should Be 'InnoDB:utf8mb4_unicode_ci'
            (Invoke-FixtureSql 'SELECT COUNT(*) FROM audit_event') | Should Be '0'
            (Invoke-FixtureSql 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()') | Should Be '9'
            $backupText = Get-Content -LiteralPath $backup -Raw
            foreach ($table in $businessTables) { ($backupText -match ('CREATE TABLE `' + $table + '`')) | Should Be $true }
            ($backupText -match 'CREATE TABLE `audit_event`') | Should Be $false
            ($backupText -match 'INSERT INTO `payment_order`') | Should Be $true
            $migratedColumns = $auditColumns -join '|'
            $migratedIndexes = (Get-AuditIndexes) -join '|'
            Invoke-FixtureSql 'DROP DATABASE medical_cloud_course' '' | Out-Null
            Invoke-FixtureSql (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8) '' | Out-Null
            ((Get-AuditColumns) -join '|') | Should BeExactly $migratedColumns
            ((Get-AuditIndexes) -join '|') | Should BeExactly $migratedIndexes
        }
        It 'backupFailurePreventsDdl' {
            Reset-P0Fixture
            $businessDigestBefore = Get-BusinessDigest
            $backup = Join-Path $scratch 'failed-backup.sql'
            # mysql is a real native CLI that rejects mysqldump-only switches.
            (Invoke-Upgrade $backup -dumpCli $mysql) | Should Not Be 0
            $script:lastUpgradeOutput | Should Match 'Backup failed'
            $script:lastUpgradeOutput | Should Not Match 'unknown option|unknown variable'
            Assert-Refusal $backup
        }
        It 'wrongUuidPortDatabaseOrLegacyShapeRefusesWrites' {
            Reset-P0Fixture
            $businessDigestBefore = Get-BusinessDigest
            $backup = Join-Path $scratch 'wrong-uuid.sql'
            (Invoke-Upgrade $backup -uuid '00000000-0000-0000-0000-000000000000') | Should Not Be 0
            Assert-Refusal $backup
            $backup = Join-Path $scratch 'wrong-database.sql'
            (Invoke-Upgrade $backup -database 'other_database') | Should Not Be 0
            Assert-Refusal $backup
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            try {
                $listener.Start()
                $backup = Join-Path $scratch 'wrong-port.sql'
                (Invoke-Upgrade $backup -targetPort $listener.LocalEndpoint.Port) | Should Not Be 0
                Assert-Refusal $backup
            } finally { $listener.Stop() }
            $variant = 0
            foreach ($mutation in @(
                'ALTER TABLE appointment DROP COLUMN booked_at',
                'ALTER TABLE tenant MODIFY name VARCHAR(99) NOT NULL',
                'ALTER TABLE payment_order ADD INDEX idx_order_appointment (appointment_id), DROP INDEX uk_course_order_appointment',
                'ALTER TABLE doctor_profile DROP FOREIGN KEY fk_course_doctor_department',
                'ALTER TABLE schedule_slot MODIFY capacity INT UNSIGNED NOT NULL',
                'ALTER TABLE department MODIFY status VARCHAR(16) NOT NULL DEFAULT "ACTIVE"',
                'ALTER TABLE tenant MODIFY name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL',
                'ALTER TABLE department MODIFY name VARCHAR(100) NULL',
                'ALTER TABLE department DROP FOREIGN KEY fk_department_tenant; ALTER TABLE department ADD CONSTRAINT fk_department_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE',
                'CREATE TRIGGER foreign_trigger BEFORE UPDATE ON tenant FOR EACH ROW SET NEW.status="ACTIVE"',
                'CREATE TABLE foreign_table (id INT PRIMARY KEY)',
                'ALTER TABLE user_account ADD COLUMN unexpected VARCHAR(5) NULL'
            )) {
                $variant++
                Write-Host "Checking P0 shape refusal variant $variant"
                Reset-P0Fixture
                Invoke-FixtureSql $mutation | Out-Null
                $businessDigestBefore = Get-BusinessDigest
                $backup = Join-Path $scratch ('wrong-shape-' + [guid]::NewGuid().ToString('N') + '.sql')
                (Invoke-Upgrade $backup) | Should Not Be 0
                $script:lastUpgradeOutput | Should Match 'baseline'
                Assert-Refusal $backup
            }
        }
        It 'completeAuditShapeIsReadOnlyAlreadyUpgraded' {
            Reset-P0Fixture
            (Invoke-Upgrade (Join-Path $scratch 'repeat-first.sql')) | Should Be 0
            Invoke-FixtureSql "INSERT INTO audit_event VALUES ('audit','request',NULL,NULL,NULL,'LOGIN','ACCOUNT',NULL,'FAILED','UNAUTHORIZED','2030-01-02 03:04:05.123456')" | Out-Null
            Invoke-FixtureSql "SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON" | Out-Null
            $marker = @(Invoke-FixtureSql 'SELECT NOW(6)')[0]
            $backup = Join-Path $scratch 'repeat-second.sql'
            # An exact retry uses the already-created first backup path.
            (Invoke-Upgrade (Join-Path $scratch 'repeat-first.sql')) | Should Be 0
            $script:lastUpgradeOutput | Should Match 'Already upgraded'
            (Invoke-Upgrade $backup) | Should Be 0
            $script:lastUpgradeOutput | Should Match 'Already upgraded'
            (Test-Path -LiteralPath $backup) | Should Be $false
            $writes = @(Invoke-FixtureSql "SELECT argument FROM mysql.general_log WHERE event_time >= '$marker' AND command_type='Query' AND argument REGEXP '^(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|REPLACE|LOCK|SET)'" '')
            $writes.Count | Should Be 0
            Invoke-FixtureSql 'SET GLOBAL general_log=OFF' | Out-Null
            (Invoke-FixtureSql "SELECT DATE_FORMAT(recorded_at,'%f') FROM audit_event WHERE id='audit'") | Should Be '123456'
        }
        It 'partialOrForeignAuditShapeRefusesRepair' {
            $variant = 0
            foreach ($mutation in @(
                'CREATE TABLE audit_event (id VARCHAR(36) PRIMARY KEY)',
                'ALTER TABLE audit_event DROP INDEX idx_audit_tenant',
                'ALTER TABLE audit_event MODIFY reason_code VARCHAR(33) NOT NULL',
                'ALTER TABLE audit_event ADD CONSTRAINT fk_foreign_audit FOREIGN KEY (tenant_id) REFERENCES tenant(id)',
                'ALTER TABLE audit_event ENGINE=MyISAM',
                'ALTER TABLE audit_event ADD INDEX idx_extra_audit (actor_id)',
                'ALTER TABLE audit_event MODIFY recorded_at DATETIME NOT NULL',
                'ALTER TABLE audit_event MODIFY actor_role VARCHAR(32) NOT NULL',
                'ALTER TABLE audit_event MODIFY event_type VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL',
                'ALTER TABLE audit_event DROP INDEX uk_audit_request_event, ADD UNIQUE INDEX uk_audit_request_event (event_type,request_id)',
                'ALTER TABLE audit_event DROP INDEX idx_audit_tenant, ADD INDEX idx_audit_tenant (tenant_id(12),recorded_at,id)',
                'ALTER TABLE audit_event MODIFY result VARCHAR(16) NOT NULL DEFAULT "SUCCESS"'
            )) {
                $variant++
                Write-Host "Checking audit shape refusal variant $variant"
                Reset-P0Fixture
                if ($mutation -notlike 'CREATE*') { (Invoke-Upgrade (Join-Path $scratch ('prepare-' + [guid]::NewGuid().ToString('N') + '.sql'))) | Should Be 0 }
                Invoke-FixtureSql $mutation | Out-Null
                $beforeColumns = (Get-AuditColumns) -join '|'
                $beforeIndexes = (Get-AuditIndexes) -join '|'
                $businessDigestBefore = Get-BusinessDigest
                $backup = Join-Path $scratch ('foreign-' + [guid]::NewGuid().ToString('N') + '.sql')
                (Invoke-Upgrade $backup) | Should Not Be 0
                $script:lastUpgradeOutput | Should Match 'audit_event'
                (Test-Path -LiteralPath $backup) | Should Be $false
                ((Get-AuditColumns) -join '|') | Should BeExactly $beforeColumns
                ((Get-AuditIndexes) -join '|') | Should BeExactly $beforeIndexes
                (Get-BusinessDigest) | Should BeExactly $businessDigestBefore
            }
        }
        It 'literalDefaultIsNotSqlNull' {
            Reset-P0Fixture
            (Invoke-Upgrade (Join-Path $scratch 'literal-default-prepare.sql')) | Should Be 0
            Invoke-FixtureSql 'ALTER TABLE audit_event MODIFY actor_id VARCHAR(36) NULL DEFAULT "~"' | Out-Null
            $backup = Join-Path $scratch 'literal-default-refusal.sql'
            (Invoke-Upgrade $backup) | Should Not Be 0
            $script:lastUpgradeOutput | Should Match 'audit_event'
            (Test-Path -LiteralPath $backup) | Should Be $false
            (Invoke-FixtureSql "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_event' AND COLUMN_NAME='actor_id'") | Should BeExactly '~'
        }
        It 'restoresMysqlPasswordAndNeverPrintsCredentialsOrNativeErrors' {
            Reset-P0Fixture
            $env:MYSQL_PWD = 'prior-password-sentinel'
            try {
                & $upgrade -MySqlCli $mysql -MySqlDumpCli $dump -HostName 127.0.0.1 -Port $port -Database medical_cloud_course -ExpectedServerUuid $serverUuid -BackupPath (Join-Path $scratch 'restore.sql') | Out-Null
                $env:MYSQL_PWD | Should BeExactly 'prior-password-sentinel'
                $env:MEDICAL_COURSE_DB_PASSWORD = 'credential-sentinel'
                $backup = Join-Path $scratch 'auth-failed.sql'
                (Invoke-Upgrade $backup) | Should Not Be 0
                $script:lastUpgradeOutput | Should Not Match 'credential-sentinel|Access denied|using password|prior-password-sentinel'
                (Test-Path -LiteralPath $backup) | Should Be $false
                $env:MYSQL_PWD | Should BeExactly 'prior-password-sentinel'
                $caught = $false
                try {
                    & $upgrade -MySqlCli $mysql -MySqlDumpCli $dump -HostName 127.0.0.1 -Port $port -Database medical_cloud_course -ExpectedServerUuid $serverUuid -BackupPath (Join-Path $scratch 'direct-auth-failed.sql') | Out-Null
                } catch {
                    $caught = $true
                    $_.Exception.Message | Should Not Match 'credential-sentinel|Access denied|using password'
                }
                $caught | Should Be $true
                $env:MYSQL_PWD | Should BeExactly 'prior-password-sentinel'
            } finally { $env:MYSQL_PWD = ''; $env:MEDICAL_COURSE_DB_PASSWORD = '' }
        }
        It 'rejectsNonAbsoluteOrExistingBackupDestinationsWithoutWrites' {
            Reset-P0Fixture
            foreach ($backup in @('relative.sql', '\\localhost\share\audit.sql', (Join-Path $scratch 'server.err'))) {
                (Invoke-Upgrade $backup) | Should Not Be 0
                @(Get-AuditColumns).Count | Should Be 0
            }
        }
    }
} finally {
    try {
        $resolved = [IO.Path]::GetFullPath($scratch)
        $root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^course-audit-[a-f0-9]{32}$') { throw 'Unexpected fixture path; refused cleanup' }
        $owned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object { $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)') })
        if ($owned.Count) {
            # On Windows mysqld may spawn a child with the same datadir.
            if (@($owned | Where-Object { $_.ExecutablePath -ine $server }).Count -or $owned.Count -gt 2) { throw "Fixture process identity mismatch; expected binary $server; actual $($owned.ExecutablePath -join ','); retained datadir" }
            if ($owned.Count -eq 2 -and @($owned | Where-Object { $_.ParentProcessId -in $owned.ProcessId }).Count -ne 1) { throw 'Fixture process ancestry mismatch; retained datadir' }
            Invoke-FixtureSql 'SHUTDOWN' '' | Out-Null
            for ($wait=0; $wait -lt 40; $wait++) {
                $stillOwned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object { $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)') })
                if (-not $stillOwned.Count) { break }; Start-Sleep -Milliseconds 250
            }
            if ($stillOwned.Count) { throw 'Fixture shutdown incomplete; retained datadir' }
            Write-Host "Fixture shutdown verified for $server and exact datadir $data; remaining owned processes: 0"
        }
        # Preserve evidence under the ignored plan directory; MySQL needs the shorter ASCII datadir.
        New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
        $fixtureEvidence = Join-Path $evidenceRoot ([IO.Path]::GetFileName($resolved))
        New-Item -ItemType Directory -Path $fixtureEvidence -Force | Out-Null
        Get-ChildItem -LiteralPath $resolved -File | Copy-Item -Destination $fixtureEvidence
    } finally {
        foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
    }
}
