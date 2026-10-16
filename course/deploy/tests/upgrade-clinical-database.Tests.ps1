# Native-boundary behavior harness. This does not claim to execute MySQL or
# validate MySQL's SQL parser/DDL semantics. Real MySQL gates run separately.
$ErrorActionPreference = 'Stop'

function Assert-Equal($actual, $expected, [string]$message) {
    if (($actual -join "`n") -cne ($expected -join "`n")) { throw "$message -- expected [$expected], observed [$actual]" }
}

function Add-ColumnShape([string]$table, [string]$definition, [int]$ordinal) {
    if ($definition -notmatch '^(\w+)\s+(VARCHAR\(\d+\)|BIGINT|INT|DECIMAL\(\d+,\d+\)|DATETIME(?:\(6\))?|DATE|TIME)(.*)$') { throw "Unsupported fixture column: $definition" }
    $name = $matches[1]; $type = $matches[2].ToLowerInvariant(); $tail = $matches[3]
    $nullable = if ($tail -match 'NOT NULL|PRIMARY KEY') { 'NO' } else { 'YES' }
    $default = if ($tail -match 'DEFAULT 0\b') { 'VALUE:0' } else { 'NULL' }
    $collation = if ($type -like 'varchar*') { 'utf8mb4:utf8mb4_unicode_ci' } else { '~:~' }
    if ($tail -match 'CHARACTER SET ascii') { $collation = 'ascii:ascii_bin' }
    return "C:${table}:${ordinal}:${name}:${type}:${nullable}:${default}::$collation"
}

# An intentionally small SQL boundary double: execute CREATE TABLE / the three
# ADD COLUMN clauses into information_schema-shaped state. It rejects all DML,
# DROP and unsupported statements. Contract assertions below are hand-written.
function Invoke-ModelDdl([string[]]$shape, [string]$sql) {
    $result = @($shape)
    $sql = [regex]::Replace($sql, '(?m)^\s*--[^\r\n]*', '')
    foreach ($rawStatement in ($sql -split ';')) {
        $statement = $rawStatement.Trim()
        if (-not $statement) { continue }
        if ($statement -match '^(CREATE DATABASE|USE )') { continue }
        if ($statement -match '^ALTER TABLE appointment\s+([\s\S]+)$') {
            $body = $matches[1]
            $ordinal = @($result | Where-Object { $_ -like 'C:appointment:*' }).Count
            foreach ($addition in [regex]::Matches($body, '(?im)ADD(?: COLUMN)?\s+([^\r\n]+)')) {
                $ordinal++
                $result += Add-ColumnShape 'appointment' $addition.Groups[1].Value.Trim().TrimEnd(',') $ordinal
            }
            continue
        }
        if ($statement -notmatch '^CREATE TABLE(?: IF NOT EXISTS)?\s+([a-z_]+)\s*\(([\s\S]+)\)\s*((?:ENGINE=)[\s\S]+)?$') { throw "Unsupported fixture statement: $statement" }
        $table = $matches[1]; $body = $matches[2]; $options = $matches[3]
        $engine = if ($options -match 'ENGINE=(\w+)') { $matches[1] } else { 'InnoDB' }
        $tableCollation = if ($options -match 'COLLATE (\w+)') { $matches[1] } else { 'utf8mb4_unicode_ci' }
        if (@($result | Where-Object { $_ -like "T:${table}:*" }).Count) { throw 'Fixture refuses duplicate table' }
        $result += "T:${table}:BASE TABLE:${engine}:${tableCollation}"
        $ordinal = 0; $keys = @(); $foreign = @()
        foreach ($rawLine in ($body -split "`n")) {
            $line = $rawLine.Trim().TrimEnd(',')
            if (-not $line) { continue }
            if ($line -match '^CONSTRAINT (\w+) FOREIGN KEY \((\w+)\) REFERENCES (\w+)\((\w+)\)(.*)$') {
                $fk = [pscustomobject]@{name=$matches[1];column=$matches[2];target=$matches[3];targetColumn=$matches[4];update='RESTRICT';delete='RESTRICT'}
                $rules = $matches[5]
                if ($rules -match 'ON UPDATE (CASCADE|SET NULL|NO ACTION|RESTRICT)') { $fk.update = $matches[1] -replace 'NO ACTION','RESTRICT' }
                if ($rules -match 'ON DELETE (CASCADE|SET NULL|NO ACTION|RESTRICT)') { $fk.delete = $matches[1] -replace 'NO ACTION','RESTRICT' }
                $foreign += $fk; continue
            }
            if ($line -match '^CONSTRAINT (\w+) UNIQUE \(([^)]+)\)') {
                $keys += [pscustomobject]@{name=$matches[1];unique=0;columns=($matches[2] -replace '\s','')}; continue
            }
            if ($line -match '^INDEX (\w+) \(([^)]+)\)') {
                $keys += [pscustomobject]@{name=$matches[1];unique=1;columns=($matches[2] -replace '\s','')}; continue
            }
            $ordinal++; $result += Add-ColumnShape $table $line $ordinal
            if ($line -match '^(\w+).*PRIMARY KEY') { $keys += [pscustomobject]@{name='PRIMARY';unique=0;columns=$matches[1]} }
        }
        foreach ($fk in $foreign) {
            if (-not @($keys | Where-Object { ($_.columns -split ',')[0] -eq $fk.column }).Count) {
                $keys += [pscustomobject]@{name=$fk.name;unique=1;columns=$fk.column}
            }
            $result += "F:${table}:$($fk.name):$($fk.column):$($fk.target):$($fk.targetColumn):medical_cloud_course:$($fk.update):$($fk.delete)"
        }
        foreach ($key in $keys) {
            $keyColumns = @(($key.columns -split ',') | ForEach-Object { "${_}:~:A" }) -join ','
            $result += "I:${table}:$($key.name):$($key.unique):BTREE:$keyColumns"
        }
    }
    return @($result | Sort-Object)
}

function Save-State($state) { $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $env:CLINICAL_BOUNDARY_STATE -Encoding UTF8 }
function Load-State { Get-Content -LiteralPath $env:CLINICAL_BOUNDARY_STATE -Raw -Encoding UTF8 | ConvertFrom-Json }

# The production script invokes this file as its external mysql/mysqldump
# boundary. Persist observed calls and schema effects across child processes.
if ($args.Count -and $args[0] -eq '--no-defaults') {
    $state = Load-State
    $dumpArg = @($args | Where-Object { $_ -like '--result-file=*' })
    if ($dumpArg.Count) {
        $state.dumps++; Save-State $state
        if ($state.failDump) { $global:LASTEXITCODE = 1; return }
        Assert-Equal (@($args | Where-Object { $_ -eq '--single-transaction' }).Count) 1 'Transactional dump required'
        Assert-Equal $args[-1] 'medical_cloud_course' 'Full database dump required'
        [IO.File]::WriteAllText($dumpArg[0].Substring(14), ($state.rows -join "`n"))
        $global:LASTEXITCODE = 0; return
    }
    $state.reads++
    $execute = @($args | Where-Object { $_ -like '--execute=*' })
    if ($execute.Count) {
        $query = $execute[0].Substring(10)
        if ($query -ceq 'SELECT DATABASE(), @@port, @@server_uuid') {
            Save-State $state; $global:LASTEXITCODE = 0
            "medical_cloud_course`t34077`t11111111-2222-4333-8444-555555555555"; return
        }
        if ($query -notmatch 'information_schema.TABLES' -or $query -notmatch 'information_schema.COLUMNS' -or $query -notmatch 'information_schema.STATISTICS' -or $query -notmatch 'information_schema.REFERENTIAL_CONSTRAINTS' -or $query -notmatch 'information_schema.TRIGGERS') { throw 'Unexpected SQL read' }
        Save-State $state; $global:LASTEXITCODE = 0; $state.shape; return
    }
    $state.ddl++
    $sql = @($input) -join "`n"
    if ($state.partialDdl) {
        $state.shape = Invoke-ModelDdl $state.shape 'ALTER TABLE appointment ADD COLUMN patient_complaint VARCHAR(500) NULL;'
        Save-State $state; $global:LASTEXITCODE = 1; return
    }
    $state.shape = Invoke-ModelDdl $state.shape $sql
    Save-State $state; $global:LASTEXITCODE = 0; return
}

$deploy = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$upgrade = Join-Path $deploy 'scripts/upgrade-clinical-database.ps1'
$oldUpgrade = Join-Path $deploy 'scripts/upgrade-attachments-database.ps1'
$evidence = [IO.Path]::GetFullPath((Join-Path $deploy '../../.superpowers/sdd/2026-10-16-medical-cloud-clinical-supplements'))
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('clinical-boundary-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$prior = @{}
foreach ($name in @('CLINICAL_BOUNDARY_STATE','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD','MYSQL_PWD')) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$env:CLINICAL_BOUNDARY_STATE = Join-Path $scratch 'state.json'
$env:MEDICAL_COURSE_DB_USER = 'boundary_fixture'; $env:MEDICAL_COURSE_DB_PASSWORD = 'fixture-secret'; $env:MYSQL_PWD = 'prior-secret'
$failures = @(); $passed = 0
$fresh = @(Invoke-ModelDdl @() (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8))
$baseline = @($fresh | Where-Object { $_ -notmatch '^[TCIF]:(clinical_draft|record_correction):' -and $_ -notmatch '^C:appointment:(11|12|13):' })

function Reset-Fixture([string[]]$shape = $baseline) {
    Save-State ([pscustomobject]@{shape=$shape;rows=@('clinical_record:r|a|t|dr|p|original diagnosis|original advice|NULL|NULL|NULL','payment_order:o|a|p|t|39.00|PAID|NULL');reads=0;ddl=0;dumps=0;failDump=$false;partialDdl=$false})
}
function Invoke-Upgrade([string]$scriptPath = $upgrade, [int]$port = 34077, [string]$uuid = '11111111-2222-4333-8444-555555555555', [string]$database = 'medical_cloud_course', [string]$backup = '') {
    if (-not $backup) { $backup = Join-Path $scratch ([guid]::NewGuid().ToString('N') + '.sql') }
    $script:lastBackup = $backup
    if (-not (Test-Path -LiteralPath $scriptPath)) { $script:lastOutput = 'Clinical upgrade script has not been implemented'; return -999 }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $script:lastOutput = @(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $scriptPath -MySqlCli $PSCommandPath -MySqlDumpCli $PSCommandPath -HostName 127.0.0.1 -Port $port -Database $database -ExpectedServerUuid $uuid -BackupPath $backup 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    [IO.File]::WriteAllText((Join-Path $scratch ('call-' + [guid]::NewGuid().ToString('N') + '.log')), $script:lastOutput)
    return $code
}
function Assert-NoWrites { $state = Load-State; Assert-Equal $state.ddl 0 'No DDL'; Assert-Equal $state.dumps 0 'No backup' }
function Test-Case([string]$name, [scriptblock]$body) {
    try { & $body; $script:passed++; Write-Host "PASS $name" }
    catch { $script:failures += "FAIL ${name}: $($_.Exception.Message)"; Write-Host $script:failures[-1] }
}

try {
    Test-Case 'freshSchemaMatchesJpaContract' {
        Assert-Equal @($fresh | Where-Object { $_ -like 'T:*' }).Count 12 'Twelve tables'
        $expectedAppointment = @('C:appointment:11:patient_complaint:varchar(500):YES:NULL::utf8mb4:utf8mb4_unicode_ci','C:appointment:12:complaint_updated_at:datetime(6):YES:NULL::~:~','C:appointment:13:complaint_revision:bigint:NO:VALUE:0::~:~')
        Assert-Equal @($fresh | Where-Object { $_ -match '^C:appointment:(11|12|13):' } | Sort-Object) ($expectedAppointment | Sort-Object) 'Patient description contract'
        $draftColumns = @($fresh | Where-Object { $_ -like 'C:clinical_draft:*' } | ForEach-Object { ($_ -split ':',4)[3] } | Sort-Object)
        Assert-Equal $draftColumns (@('id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci','appointment_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci','tenant_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci','doctor_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci','patient_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci','complaint:varchar(500):YES:NULL::utf8mb4:utf8mb4_unicode_ci','diagnosis:varchar(500):YES:NULL::utf8mb4:utf8mb4_unicode_ci','advice:varchar(2000):YES:NULL::utf8mb4:utf8mb4_unicode_ci','notes:varchar(2000):YES:NULL::utf8mb4:utf8mb4_unicode_ci','saved_at:datetime(6):NO:NULL::~:~','revision:bigint:NO:NULL::~:~') | Sort-Object) 'Draft contract'
        $correction = @($fresh | Where-Object { $_ -like 'C:record_correction:*' } | ForEach-Object { ($_ -split ':',4)[3] } | Sort-Object)
        $wanted = @('id:varchar(36):NO','record_id:varchar(36):NO','appointment_id:varchar(36):NO','tenant_id:varchar(36):NO','doctor_id:varchar(36):NO','patient_id:varchar(36):NO','sequence_number:bigint:NO','before_complaint:varchar(500):YES','before_diagnosis:varchar(500):NO','before_advice:varchar(2000):NO','before_notes:varchar(2000):YES','after_complaint:varchar(500):YES','after_diagnosis:varchar(500):NO','after_advice:varchar(2000):NO','after_notes:varchar(2000):YES','reason:varchar(2000):NO','created_at:datetime(6):NO','operation_key:varchar(36):NO') | ForEach-Object { $_ + ':NULL::' + $(if ($_ -like '*varchar*') { 'utf8mb4:utf8mb4_unicode_ci' } else { '~:~' }) } | Sort-Object
        Assert-Equal $correction $wanted 'Correction contract'
        Assert-Equal @($fresh | Where-Object { $_ -match '^F:(clinical_draft|record_correction):' }).Count 9 'Nine restricted scalar foreign keys'
        Assert-Equal @($fresh | Where-Object { $_ -match '^F:(clinical_draft|record_correction):' -and $_ -notmatch ':medical_cloud_course:RESTRICT:RESTRICT$' }).Count 0 'No clinical FK cascades'
        foreach ($table in @('clinical_draft','record_correction')) { Assert-Equal ("T:${table}:BASE TABLE:InnoDB:utf8mb4_unicode_ci" -cin $fresh) $true 'Clinical engine and character collation' }
        foreach ($key in @('I:clinical_draft:uk_clinical_draft_appointment:0:BTREE:appointment_id:~:A','I:record_correction:uk_record_correction_sequence:0:BTREE:record_id:~:A,sequence_number:~:A','I:record_correction:uk_record_correction_operation:0:BTREE:doctor_id:~:A,operation_key:~:A')) { Assert-Equal ($key -cin $fresh) $true 'Required unique key' }
    }
    Test-Case 'tenTableBaselineUpgradePreservesOldRows' {
        Reset-Fixture; $before = (Load-State).rows
        Assert-Equal (Invoke-Upgrade) 0 "Migration succeeds: $script:lastOutput"
        $state = Load-State; Assert-Equal $state.ddl 1 'Exactly one migration invocation'; Assert-Equal $state.dumps 1 'Exactly one full backup'
        Assert-Equal $state.rows $before 'Old record/order per-row summaries unchanged'
        Assert-Equal ($state.shape | Sort-Object) $fresh 'Fresh and migrated schema agree'
        Assert-Equal (Test-Path -LiteralPath $script:lastBackup) $true 'Backup retained'
    }
    Test-Case 'secondRunReadOnlyWithoutBackup' {
        Reset-Fixture $fresh
        $existing = Join-Path $scratch 'existing.sql'; [IO.File]::WriteAllText($existing,'existing backup')
        Assert-Equal (Invoke-Upgrade -backup $existing) 0 'Repeat may use existing backup path'
        Assert-NoWrites
        Assert-Equal (Get-Content -LiteralPath $existing -Raw) 'existing backup' 'Existing backup unchanged'
    }
    Test-Case 'partialClinicalTablesRefused' {
        foreach ($partial in @(@($fresh | Where-Object { $_ -notmatch '^[TCIF]:record_correction:' }), @($fresh | Where-Object { $_ -notmatch '^C:appointment:12:' }), @($fresh | Where-Object { $_ -notmatch '^I:record_correction:uk_record_correction_operation:' }), @($fresh | Where-Object { $_ -notmatch '^F:clinical_draft:fk_clinical_draft_patient:' }))) {
            Reset-Fixture $partial; $before = (Load-State).shape
            Assert-Equal ((Invoke-Upgrade) -ne 0) $true 'Partial new structure refused'; Assert-NoWrites
            Assert-Equal (Load-State).shape $before 'Partial structure retained'
        }
    }
    Test-Case 'wrongUuidOrProtectedPortRefusedBeforeSql' {
        Reset-Fixture; Assert-Equal ((Invoke-Upgrade -uuid '00000000-0000-4000-8000-000000000000') -ne 0) $true 'Wrong UUID refused'; Assert-NoWrites
        Assert-Equal (Load-State).reads 1 'Wrong valid UUID stops after identity read'
        foreach ($uuid in @('not-a-uuid','00000000-0000-0000-0000-000000000000')) { Reset-Fixture; Assert-Equal ((Invoke-Upgrade -uuid $uuid) -ne 0) $true 'Malformed or empty UUID refused'; Assert-NoWrites; Assert-Equal (Load-State).reads 0 'Malformed UUID refused before SQL' }
        foreach ($port in @(3318,8180,8188,10090)) { Reset-Fixture; Assert-Equal ((Invoke-Upgrade -port $port) -ne 0) $true 'Protected port refused'; Assert-NoWrites; Assert-Equal (Load-State).reads 0 'Protected port refused before SQL' }
        Reset-Fixture; Assert-Equal ((Invoke-Upgrade -database 'medical_cloud') -ne 0) $true 'Foreign database refused'; Assert-NoWrites; Assert-Equal (Load-State).reads 0 'Foreign database refused before SQL'
    }
    Test-Case 'failedBackupPreventsDdl' {
        Reset-Fixture; $state = Load-State; $state.failDump = $true; Save-State $state; $before = $state.rows
        Assert-Equal ((Invoke-Upgrade) -ne 0) $true 'Failed backup refused'
        $state = Load-State; Assert-Equal $state.ddl 0 'Failed backup prevents DDL'; Assert-Equal $state.dumps 1 'One failed dump'; Assert-Equal $state.rows $before 'Failed backup preserves old rows'
    }
    Test-Case 'old003After004HasDocumentedRefusal' {
        Reset-Fixture $fresh; Assert-Equal ((Invoke-Upgrade -scriptPath $oldUpgrade) -ne 0) $true 'Old 003 refuses latest shape'; Assert-NoWrites
        Assert-Equal ($script:lastOutput -match 'complete nine-table audited course baseline') $true 'Old 003 refusal is explicit baseline mismatch'
    }
    Test-Case 'foreignBaselineAndUnknownTablesRefused' {
        foreach ($foreign in @(@($baseline + 'T:unknown:BASE TABLE:InnoDB:utf8mb4_unicode_ci'), @($baseline | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\)', 'C:tenant:2:name:varchar(99)' }), @($baseline + 'X:TRIGGER:unexpected'), @($baseline | Where-Object { $_ -notmatch '^I:file_metadata:idx_attachment_cleanup:' }), @($baseline | ForEach-Object { $_ -replace '^C:tenant:3:status:varchar\(16\):NO:NULL:', 'C:tenant:3:status:varchar(16):NO:VALUE:ACTIVE:' }), @($baseline | ForEach-Object { $_ -replace '^F:department:fk_department_tenant:tenant_id:tenant:id:medical_cloud_course:RESTRICT:RESTRICT$', 'F:department:fk_department_tenant:tenant_id:tenant:id:medical_cloud_course:RESTRICT:CASCADE' }))) {
            Reset-Fixture $foreign; Assert-Equal ((Invoke-Upgrade) -ne 0) $true 'Foreign baseline refused'; Assert-NoWrites
        }
    }
    Test-Case 'partialDdlPreservesBackupAndRefusesRetry' {
        Reset-Fixture; $state = Load-State; $state.partialDdl = $true; Save-State $state
        Assert-Equal ((Invoke-Upgrade) -ne 0) $true 'Partial DDL fails'
        $state = Load-State; Assert-Equal $state.ddl 1 'Never retries failed DDL'; Assert-Equal (Test-Path -LiteralPath $script:lastBackup) $true 'Backup retained after partial DDL'
        Assert-Equal ($script:lastOutput -match 'Actual clinical structure:.*patient_complaint') $true 'Actual partial structure reported'
        $state.ddl=0; $state.dumps=0; Save-State $state
        Assert-Equal ((Invoke-Upgrade) -ne 0) $true 'Partial state retry refused'; Assert-NoWrites
    }
    Write-Host "Native-boundary summary: passed=$passed failed=$($failures.Count) skipped=0; real MySQL=UNVERIFIED; teacher MySQL 5.6=UNVERIFIED"
    if ($failures.Count) { throw ($failures -join "`n") }
} finally {
    Write-Host "Boundary evidence retained: $scratch"
    foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
}
