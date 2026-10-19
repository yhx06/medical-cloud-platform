# Offline Windows PowerShell 5 native-boundary tests. No MySQL is started.
# Breaks caught: bypassed identity/backup/metadata gates, replayed DDL,
# relaxed constraints, partial-DDL cleanup, changed old rows or leaked secrets.
$ErrorActionPreference = 'Stop'

function Assert-Equal($actual, $expected, [string]$message) {
    if (($actual -join "`n") -cne ($expected -join "`n")) { throw "$message -- expected [$expected], observed [$actual]" }
}

# Reuse only the two test-only SQL model functions; never run old test main.
$oldTests = Join-Path $PSScriptRoot 'upgrade-clinical-database.Tests.ps1'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($oldTests,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Old SQL model failed to parse' }
foreach ($name in @('Add-ColumnShape','Invoke-ModelDdl')) {
    $nodes = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name })
    if ($nodes.Count -ne 1) { throw 'Old SQL model helper missing or duplicated' }
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}
function Save-State($state) { $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $env:HOB_BOUNDARY_STATE -Encoding UTF8 }
function Load-State { Get-Content -LiteralPath $env:HOB_BOUNDARY_STATE -Raw -Encoding UTF8 | ConvertFrom-Json }

# Drive the upgrader in one child PS5 process and inspect its real finally
# effect in that same process (a child could never mutate its parent's env).
if ($args.Count -and $args[0] -eq '--run-upgrade') {
    $previous = $env:MYSQL_PWD; $code = 0
    try { & $args[1] -MySqlCli $PSCommandPath -MySqlDumpCli $PSCommandPath -HostName 127.0.0.1 -Port ([int]$args[2]) -Database $args[3] -ExpectedServerUuid $args[4] -BackupPath $args[5] }
    catch { Write-Output $_.Exception.Message; $code = 1 }
    if ($env:MYSQL_PWD -cne $previous) { Write-Output 'FAIL: upgrader did not restore MYSQL_PWD'; exit 91 }
    exit $code
}

# This file is also the external mysql/mysqldump boundary. Only synthetic rows
# and identity exist; schema effects are interpreted from the actual SQL stdin.
if ($args.Count -and $args[0] -eq '--no-defaults') {
    $state = Load-State
    $dumpArg = @($args | Where-Object { $_ -like '--result-file=*' })
    if ($dumpArg.Count) {
        $state.dumps++; Save-State $state
        Assert-Equal (@($args | Where-Object { $_ -eq '--single-transaction' }).Count) 1 'Full transactional dump required'
        Assert-Equal (@($args | Where-Object { $_ -eq '--quick' }).Count) 1 'Streaming dump required'
        Assert-Equal $args[-1] 'medical_cloud_course' 'Full database dump required'
        if ($state.failDump) { [IO.File]::WriteAllText($dumpArg[0].Substring(14),'synthetic-partial-dump'); $global:LASTEXITCODE = 7; return }
        if (-not $state.emptyDump) { [IO.File]::WriteAllText($dumpArg[0].Substring(14),($state.rows -join "`n")) }
        else { [IO.File]::WriteAllText($dumpArg[0].Substring(14),'') }
        $global:LASTEXITCODE = 0; return
    }
    $execute = @($args | Where-Object { $_ -like '--execute=*' })
    if ($execute.Count) {
        $state.reads++
        $sql = $execute[0].Substring(10)
        if ($sql -ceq 'SELECT DATABASE(), @@port, @@server_uuid') {
            Save-State $state
            if ($state.failRead) { "medical_cloud_course`t34077`t11111111-2222-4333-8444-555555555555"; $global:LASTEXITCODE = 9; return }
            $global:LASTEXITCODE = 0
            "medical_cloud_course`t34077`t11111111-2222-4333-8444-555555555555"; return
        }
        foreach ($source in @('TABLES','COLUMNS','STATISTICS','REFERENTIAL_CONSTRAINTS','TRIGGERS','TABLE_CONSTRAINTS')) {
            if ($sql -notmatch "information_schema\.$source") { throw 'Incomplete metadata query' }
        }
        Save-State $state; $global:LASTEXITCODE = 0; $state.shape; return
    }
    $state.ddl++
    if ($state.dumps -ne 1) { throw 'DDL reached without one successful backup' }
    if ($state.partialDdl) {
        $state.shape += 'T:hospital_admin_operation:BASE TABLE:InnoDB:utf8mb4_unicode_ci'
        Save-State $state; $global:LASTEXITCODE = 8; return
    }
    $state.shape = Invoke-ModelDdl $state.shape (@($input) -join "`n")
    Save-State $state; $global:LASTEXITCODE = $(if ($state.failDdlComplete) { 8 } else { 0 }); return
}

$deploy = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = [IO.Path]::GetFullPath((Join-Path $deploy '../..'))
if ((Split-Path (Split-Path $project -Parent) -Leaf) -eq '.worktrees') { $project = Split-Path (Split-Path $project -Parent) -Parent }
$scratch = Join-Path $project ('.local/tests/hob-migration-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$prior = @{}
foreach ($name in @('TEMP','TMP','HOB_BOUNDARY_STATE','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD','MYSQL_PWD')) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$env:TEMP = $scratch; $env:TMP = $scratch
$env:HOB_BOUNDARY_STATE = Join-Path $scratch 'state.json'
$env:MEDICAL_COURSE_DB_USER = 'offline_boundary'; $env:MEDICAL_COURSE_DB_PASSWORD = 'synthetic-secret'; $env:MYSQL_PWD = 'prior-synthetic'
$upgrade = Join-Path $deploy 'scripts/upgrade-hospital-onboarding-database.ps1'
$fresh = @(Invoke-ModelDdl @() (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8))
$baseline = @($fresh | Where-Object { $_ -notmatch '^[TCIF]:hospital_admin_operation:' })
$expectedNew = @(
 'T:hospital_admin_operation:BASE TABLE:InnoDB:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:1:id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:2:actor_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:3:operation_key:varchar(36):NO:NULL::ascii:ascii_bin',
 'C:hospital_admin_operation:4:operation_type:varchar(32):NO:NULL::ascii:ascii_bin',
 'C:hospital_admin_operation:5:tenant_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:6:admin_id:varchar(36):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:7:hospital_name:varchar(100):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:8:admin_username:varchar(64):NO:NULL::utf8mb4:utf8mb4_unicode_ci',
 'C:hospital_admin_operation:9:created_at:datetime(6):NO:NULL::~:~',
 'I:hospital_admin_operation:PRIMARY:0:BTREE:id:~:A',
 'I:hospital_admin_operation:uk_hospital_admin_operation_actor_key:0:BTREE:actor_id:~:A,operation_key:~:A',
 'I:hospital_admin_operation:uk_hospital_admin_operation_admin:0:BTREE:admin_id:~:A',
 'I:hospital_admin_operation:idx_hospital_admin_operation_tenant:1:BTREE:tenant_id:~:A',
 'F:hospital_admin_operation:fk_hospital_admin_operation_actor:actor_id:user_account:id:medical_cloud_course:RESTRICT:RESTRICT',
 'F:hospital_admin_operation:fk_hospital_admin_operation_tenant:tenant_id:tenant:id:medical_cloud_course:RESTRICT:RESTRICT',
 'F:hospital_admin_operation:fk_hospital_admin_operation_admin:admin_id:user_account:id:medical_cloud_course:RESTRICT:RESTRICT'
)
$complete = @($baseline + $expectedNew | Sort-Object)

function Run-UpgradeCase([string]$Name) {
    $shape = $baseline; $uuid = '11111111-2222-4333-8444-555555555555'; $port = 34077; $database = 'medical_cloud_course'
    switch ($Name) {
        'WrongUuid' { $uuid = '99999999-2222-4333-8444-555555555555' }
        'EmptyUuid' { $uuid = '00000000-0000-0000-0000-000000000000' }
        'MalformedUuid' { $uuid = 'not-a-uuid' }
        'ProtectedPort' { $port = 3318 }
        'ProtectedTomcatPort' { $port = 8180 }
        'ProtectedWildflyPort' { $port = 8188 }
        'ProtectedManagementPort' { $port = 10090 }
        'ForeignDatabase' { $database = 'medical_cloud' }
        'Complete005Repeat' { $shape = $complete }
        'Old003On005' { $shape = $complete }
        'Old004On005' { $shape = $complete }
        'PartialTable' { $shape = $baseline + $expectedNew[0] }
        'WrongConstraint' { $shape = $complete | ForEach-Object { $_ -replace ':RESTRICT:RESTRICT$',':RESTRICT:CASCADE' } }
        'WrongAscii' { $shape = $complete | ForEach-Object { $_ -replace 'ascii:ascii_bin$','utf8mb4:utf8mb4_unicode_ci' } }
        'WrongUnique' { $shape = $complete | ForEach-Object { $_ -replace ':uk_hospital_admin_operation_actor_key:0:',':uk_hospital_admin_operation_actor_key:1:' } }
        'WrongTenantIndex' { $shape = $complete | Where-Object { $_ -notmatch '^I:hospital_admin_operation:idx_hospital_admin_operation_tenant:' } }
        'ForeignBaseline' { $shape = $baseline + 'T:foreign_table:BASE TABLE:InnoDB:utf8mb4_unicode_ci' }
        'WrongOldColumn' { $shape = $baseline | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\)', 'C:tenant:2:name:varchar(99)' } }
        'WrongOldIndex' { $shape = $baseline | Where-Object { $_ -notmatch '^I:file_metadata:idx_attachment_cleanup:' } }
        'Trigger' { $shape = $baseline + 'X:TRIGGER:unexpected' }
        'CheckConstraint' { $shape = $baseline + 'X:CONSTRAINT:tenant:unexpected' }
    }
    $rows = @('tenant:synthetic-original','user_account:synthetic-bcrypt-placeholder','clinical_record:synthetic-original')
    $state = [pscustomobject]@{shape=@($shape);rows=$rows;reads=0;ddl=0;dumps=0;failDump=($Name -eq 'BackupFailed');emptyDump=($Name -eq 'EmptyBackup');partialDdl=($Name -in @('PartialDdl','PartialDdlRetry'));failDdlComplete=($Name -eq 'FailedDdlComplete');failRead=($Name -eq 'ReadFailed')}
    Save-State $state
    $before = @($shape | Sort-Object) -join "`n"
    $backup = Join-Path $scratch ([guid]::NewGuid().ToString('N') + '.sql')
    if ($Name -in @('ExistingBackup','Complete005Repeat')) { [IO.File]::WriteAllText($backup,'synthetic-existing-backup') }
    if ($Name -eq 'MissingBackupParent') { $backup = Join-Path $scratch ('missing-parent-' + [guid]::NewGuid().ToString('N') + '/backup.sql') }
    $scriptPath = $upgrade
    if ($Name -eq 'Old003On005') { $scriptPath = Join-Path $deploy 'scripts/upgrade-attachments-database.ps1' }
    if ($Name -eq 'Old004On005') { $scriptPath = Join-Path $deploy 'scripts/upgrade-clinical-database.ps1' }
    if ($Name -in @('AlteredMetadata','MissingMetadata','IncompleteMetadata','CRLFMetadata')) {
        $copyRoot = Join-Path $scratch $Name
        New-Item -ItemType Directory -Path (Join-Path $copyRoot 'scripts'),(Join-Path $copyRoot 'migrations') -Force | Out-Null
        if (Test-Path -LiteralPath $upgrade) { Copy-Item -LiteralPath $upgrade -Destination (Join-Path $copyRoot 'scripts') }
        if (Test-Path -LiteralPath (Join-Path $deploy 'migrations/005_hospital_onboarding.sql')) { Copy-Item -LiteralPath (Join-Path $deploy 'migrations/005_hospital_onboarding.sql') -Destination (Join-Path $copyRoot 'migrations') }
        if ($Name -ne 'MissingMetadata') {
            $changed = Get-Content -LiteralPath (Join-Path $deploy 'scripts/upgrade-clinical-database.ps1') -Raw -Encoding UTF8
            if ($Name -eq 'AlteredMetadata') { $changed = $changed.Replace('varchar(100)','varchar(99)') }
            if ($Name -eq 'IncompleteMetadata') { $changed = $changed.Replace("    'department:fk_department_tenant:tenant_id:tenant:id',",'') }
            if ($Name -eq 'CRLFMetadata') { $changed = $changed.Replace("`r`n","`n").Replace("`n","`r`n") }
            [IO.File]::WriteAllText((Join-Path $copyRoot 'scripts/upgrade-clinical-database.ps1'),$changed)
        }
        $scriptPath = Join-Path $copyRoot 'scripts/upgrade-hospital-onboarding-database.ps1'
    }
    $output = ''; $exit = -999
    if (Test-Path -LiteralPath $scriptPath) {
        $saved = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = @(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath --run-upgrade $scriptPath $port $database $uuid $backup 2>&1) -join "`n"
            $exit = $LASTEXITCODE
            if ($Name -eq 'PartialDdlRetry') {
                $output += @(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath --run-upgrade $scriptPath $port $database $uuid $backup 2>&1) -join "`n"
                $exit = $LASTEXITCODE
            }
        } finally { $ErrorActionPreference = $saved }
    }
    [IO.File]::WriteAllText((Join-Path $scratch ($Name + '.log')),$output)
    $after = Load-State
    $backupContent = if (Test-Path -LiteralPath $backup -PathType Leaf) { [IO.File]::ReadAllText($backup) } else { '' }
    if ($output -match 'synthetic-secret|prior-synthetic|offline_boundary') { throw 'Output disclosed synthetic credentials' }
    Assert-Equal ($exit -eq 91) $false 'Upgrader restores password environment after success/failure'
    return [pscustomobject]@{Exit=$exit;DdlCalls=$after.ddl;BackupCalls=$after.dumps;Reads=$after.reads;TableCount=@($after.shape | Where-Object { $_ -like 'T:*' }).Count;Unchanged=(($after.shape | Sort-Object) -join "`n") -ceq $before;RowsUnchanged=(($after.rows -join "`n") -ceq ($rows -join "`n"));Shape=$after.shape;BackupExists=(Test-Path -LiteralPath $backup);BackupContent=$backupContent;Output=$output}
}

$passed = 0; $failures = @()
function Test-Case([string]$name,[scriptblock]$body) {
    try { & $body; $script:passed++; Write-Host "PASS $name" }
    catch { $script:failures += "FAIL ${name}: $($_.Exception.Message)"; Write-Host $script:failures[-1] }
}
try {
    Test-Case 'Fresh005NineFieldsAndConstraints' {
        Assert-Equal @($fresh | Where-Object { $_ -like 'T:*' }).Count 13 'Thirteen application tables'
        Assert-Equal @($fresh | Where-Object { $_ -match '^[TCIF]:hospital_admin_operation:' } | Sort-Object) ($expectedNew | Sort-Object) 'Exact nine fields, unique/FK/index metadata'
    }
    Test-Case 'Exact004' {
        $r = Run-UpgradeCase 'Exact004'; Assert-Equal $r.Exit 0 'Exact 004 upgrade succeeds'
        Assert-Equal $r.DdlCalls 1 'One DDL invocation'; Assert-Equal $r.BackupCalls 1 'One full backup'; Assert-Equal $r.TableCount 13 'One new table'
        Assert-Equal $r.RowsUnchanged $true 'Original row summaries preserved'; Assert-Equal $r.BackupExists $true 'Backup retained'; Assert-Equal ($r.Shape | Sort-Object) $complete 'Complete005 after migration'
        Assert-Equal $r.BackupContent @('tenant:synthetic-original','user_account:synthetic-bcrypt-placeholder','clinical_record:synthetic-original') 'Full original synthetic rows backed up'
        Assert-Equal $r.Reads 3 'Only identity and before/after metadata reads; importer executes no main'
    }
    Test-Case 'Complete005Repeat' {
        $r = Run-UpgradeCase 'Complete005Repeat'; Assert-Equal $r.Exit 0 'Complete005 accepted'; Assert-Equal $r.DdlCalls 0 'Repeat is read-only'; Assert-Equal $r.BackupCalls 0 'No second backup'; Assert-Equal $r.Unchanged $true 'Repeat shape unchanged'; Assert-Equal $r.Reads 2 'Importer does not execute004 main'; Assert-Equal $r.BackupContent 'synthetic-existing-backup' 'Existing backup untouched'
    }
    Test-Case 'CRLFMetadata' { $r = Run-UpgradeCase 'CRLFMetadata'; Assert-Equal $r.Exit 0 'Normalized fingerprint accepts Windows checkout'; Assert-Equal $r.DdlCalls 1 'Normal upgrade'; Assert-Equal $r.Reads 3 'No old main invocation' }
    foreach ($case in @('WrongUuid','EmptyUuid','MalformedUuid','ProtectedPort','ProtectedTomcatPort','ProtectedWildflyPort','ProtectedManagementPort','ForeignDatabase','PartialTable','WrongConstraint','WrongAscii','WrongUnique','WrongTenantIndex','ForeignBaseline','WrongOldColumn','WrongOldIndex','Trigger','CheckConstraint','ReadFailed','AlteredMetadata','MissingMetadata','IncompleteMetadata','ExistingBackup','MissingBackupParent','Old003On005','Old004On005')) {
        Test-Case $case {
            $r = Run-UpgradeCase $case; Assert-Equal ($r.Exit -ne 0) $true 'Unsafe input refused'; Assert-Equal $r.DdlCalls 0 'No DDL'; Assert-Equal $r.BackupCalls 0 'No backup'; Assert-Equal $r.Unchanged $true 'No cleanup'; Assert-Equal $r.RowsUnchanged $true 'Original rows retained'
            if ($case -in @('EmptyUuid','MalformedUuid','ProtectedPort','ProtectedTomcatPort','ProtectedWildflyPort','ProtectedManagementPort','ForeignDatabase','AlteredMetadata','MissingMetadata','IncompleteMetadata')) { Assert-Equal $r.Reads 0 'Refused before native boundary' }
            if ($case -eq 'WrongUuid') { Assert-Equal $r.Reads 1 'Identity before metadata' }
            if ($case -eq 'ReadFailed') { Assert-Equal $r.Reads 1 'Native failed read refused despite plausible identity stdout' }
            if ($case -in @('PartialTable','WrongConstraint','WrongAscii','WrongUnique','WrongTenantIndex','ForeignBaseline','WrongOldColumn','WrongOldIndex','Trigger','CheckConstraint')) { Assert-Equal $r.Reads 2 'Refused after identity and full metadata' }
            if ($case -eq 'ExistingBackup') { Assert-Equal $r.BackupContent 'synthetic-existing-backup' 'Existing backup never overwritten' }
        }
    }
    foreach ($case in @('BackupFailed','EmptyBackup')) {
        Test-Case $case { $r = Run-UpgradeCase $case; Assert-Equal ($r.Exit -ne 0) $true 'Failed/empty backup refused'; Assert-Equal $r.DdlCalls 0 'Backup failure prevents DDL'; Assert-Equal $r.BackupCalls 1 'One dump attempt'; Assert-Equal $r.Unchanged $true 'Database unchanged'; Assert-Equal $r.BackupExists $true 'Failed/empty backup retained'; if ($case -eq 'BackupFailed') { Assert-Equal $r.BackupContent 'synthetic-partial-dump' 'Nonempty failed dump never authorizes DDL' } }
    }
    Test-Case 'PartialDdl' {
        $r = Run-UpgradeCase 'PartialDdl'; Assert-Equal ($r.Exit -ne 0) $true 'Partial DDL fails'; Assert-Equal $r.DdlCalls 1 'No DDL retry'; Assert-Equal $r.BackupExists $true 'Backup retained'; Assert-Equal $r.TableCount 13 'Partial structure retained'; Assert-Equal $r.Unchanged $false 'Partial table not dropped'; Assert-Equal $r.RowsUnchanged $true 'Old data preserved'
    }
    Test-Case 'PartialDdlRetry' {
        $r = Run-UpgradeCase 'PartialDdlRetry'; Assert-Equal ($r.Exit -ne 0) $true 'Partial state retry refused'; Assert-Equal $r.DdlCalls 1 'No second DDL'; Assert-Equal $r.BackupCalls 1 'No second backup'; Assert-Equal $r.Reads 5 'Retry performs only identity/metadata'; Assert-Equal $r.BackupExists $true 'Original backup retained'; Assert-Equal $r.TableCount 13 'Partial table not cleaned up'
    }
    Test-Case 'FailedDdlComplete' {
        $r = Run-UpgradeCase 'FailedDdlComplete'; Assert-Equal ($r.Exit -ne 0) $true 'Native DDL failure refused even with complete metadata'; Assert-Equal ($r.Shape | Sort-Object) $complete 'Observed complete shape retained'; Assert-Equal $r.DdlCalls 1 'No retry'; Assert-Equal $r.BackupExists $true 'Backup retained'
    }
    Test-Case 'IndexBudgetMySql56' {
        # Compute from the SQL model's actual index and column metadata,
        # independently of production expected-shape code. Literal bounds are
        # hand-derived: utf8mb4 VARCHAR(36)=144; ASCII VARCHAR(36)=36.
        $widths = @{}
        foreach ($column in @($fresh | Where-Object { $_ -like 'C:hospital_admin_operation:*' })) {
            $parts = $column -split ':'
            if ($parts[4] -match '^varchar\((\d+)\)$') { $widths[$parts[3]] = [int]$matches[1] * $(if ($parts[8] -eq 'ascii') { 1 } else { 4 }) }
        }
        $budgets = @{}
        foreach ($index in @($fresh | Where-Object { $_ -like 'I:hospital_admin_operation:*' })) {
            $parts = $index -split ':',6; $total = 0
            foreach ($keyColumn in ($parts[5] -split ',')) { $keyParts = $keyColumn -split ':'; Assert-Equal $keyParts[1] '~' 'No prefix indexes'; $total += $widths[$keyParts[0]] }
            $budgets[$parts[2]] = $total
            Assert-Equal ($total -le 767) $true 'Full new index fits MySQL5.6 legacy InnoDB bound'
        }
        Assert-Equal $budgets.Count 4 'Four full indexes'
        Assert-Equal $budgets['uk_hospital_admin_operation_actor_key'] 180 'Actor/key index bytes'
        foreach ($index in @('PRIMARY','uk_hospital_admin_operation_admin','idx_hospital_admin_operation_tenant')) { Assert-Equal $budgets[$index] 144 'Scalar UUID index bytes' }
    }
    Write-Host "Native-boundary summary: passed=$passed failed=$($failures.Count) skipped=0; real MySQL=UNVERIFIED; teacher MySQL5.6=UNVERIFIED"
    if ($failures.Count) { throw ($failures -join "`n") }
} finally {
    foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
    Write-Host "Boundary evidence retained: $scratch"
}
