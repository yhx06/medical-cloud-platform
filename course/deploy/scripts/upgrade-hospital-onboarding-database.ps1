# Latest deployment entry: exact 004 -> 005, or complete 005 read-only.
# Strict 003/004 upgrade scripts remain unchanged and intentionally reject 005.
param(
    [Parameter(Mandatory = $true)][string]$MySqlCli,
    [Parameter(Mandatory = $true)][string]$MySqlDumpCli,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$ExpectedServerUuid,
    [Parameter(Mandatory = $true)][string]$BackupPath
)
$ErrorActionPreference = 'Stop'
if ($Port -in @(3318,8180,8188,10090)) { throw 'Protected original or container port; no SQL was attempted' }
if ([string]::IsNullOrWhiteSpace($HostName)) { throw 'Explicit host required; no SQL was attempted' }
$parsedUuid = [guid]::Empty
if (-not [guid]::TryParseExact($ExpectedServerUuid,'D',[ref]$parsedUuid) -or $parsedUuid -eq [guid]::Empty) { throw 'Explicit nonempty canonical server UUID required; no SQL was attempted' }
if ($Database -cne 'medical_cloud_course') { throw 'Only medical_cloud_course may be upgraded' }
if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER)) { throw 'Set MEDICAL_COURSE_DB_USER before upgrading' }
if ($BackupPath -notmatch '^[A-Za-z]:[\\/]' -or $BackupPath -match '^\\\\') { throw 'Backup must be an absolute local disk file path' }
$mysqlPath = (Resolve-Path -LiteralPath $MySqlCli).Path
$dumpPath = (Resolve-Path -LiteralPath $MySqlDumpCli).Path
$backupFull = [IO.Path]::GetFullPath($BackupPath)
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../migrations/005_hospital_onboarding.sql')).Path

# Import only frozen 004 metadata declarations and its pure shape builder.
# The normalized source fingerprint is deliberately pinned: a changed/missing
# dependency requires review, rather than silently changing our accepted 004.
# Never dot-source the old script: its main performs identity/backup/DDL work.
$metadataPath = Join-Path $PSScriptRoot 'upgrade-clinical-database.ps1'
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) { throw 'Frozen 004 metadata dependency is unavailable; no SQL was attempted' }
$metadataText = [IO.File]::ReadAllText($metadataPath).Replace("`r`n","`n")
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($metadataText))).Replace('-','') }
finally { $sha.Dispose() }
if ($fingerprint -cne '4FD064E442EFF4961E084566D12834DA2248F6119D006A85A17722DFF1CB2C57') { throw 'Frozen 004 metadata dependency changed; no SQL was attempted' }
$tokens = $null; $parseErrors = $null
$metadataAst = [Management.Automation.Language.Parser]::ParseInput($metadataText,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Frozen 004 metadata dependency is invalid; no SQL was attempted' }
$declarations = @(
    '$columns:Equals','$indexes:Equals','$foreignKeys:Equals','$clinicalColumns:Equals',
    '$columns.clinical_draft:Equals','$columns.record_correction:Equals',
    '$indexes.clinical_draft:Equals','$indexes.record_correction:Equals',
    '$foreignKeys:PlusEquals','$shapeSql:Equals'
)
$selected = @()
foreach ($declaration in $declarations) {
    $matches = @($metadataAst.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.AssignmentStatementAst] -and
        ($_.Left.Extent.Text + ':' + $_.Operator.ToString()) -ceq $declaration
    })
    if ($matches.Count -ne 1) { throw 'Frozen 004 metadata declaration is missing or duplicated; no SQL was attempted' }
    $selected += $matches[0]
}
$builder = @($metadataAst.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Get-ExpectedShape'
})
if ($builder.Count -ne 1) { throw 'Frozen 004 metadata builder is missing or duplicated; no SQL was attempted' }
foreach ($node in @($selected + $builder | Sort-Object { $_.Extent.StartOffset })) {
    . ([scriptblock]::Create($node.Extent.Text))
}
$oldTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order','audit_event','file_metadata','clinical_draft','record_correction')
$baseline = @(Get-ExpectedShape $oldTables $true | Sort-Object)
$newShape = @(
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
$complete = @($baseline + $newShape | Sort-Object) -join "`n"
$baselineText = $baseline -join "`n"
$connection = @('--no-defaults','--protocol=TCP',"--host=$HostName","--port=$Port",'--default-character-set=utf8mb4',"--user=$env:MEDICAL_COURSE_DB_USER")

function Read-Database([string]$sql) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rows = @(& $mysqlPath @connection --connect-timeout=3 "--database=$Database" --batch --skip-column-names "--execute=$sql" 2>$null)
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExit -ne 0) { throw 'Target database read failed; inspect the target before retrying' }
    return $rows
}

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:MEDICAL_COURSE_DB_PASSWORD
    $identity = @(Read-Database 'SELECT DATABASE(), @@port, @@server_uuid')
    if ($identity.Count -ne 1) { throw 'Cannot identify target database; no DDL was attempted' }
    $identityParts = $identity[0] -split "`t"
    if ($identityParts.Count -ne 3 -or $identityParts[0] -cne $Database -or $identityParts[1] -cne $Port.ToString() -or $identityParts[2] -cne $ExpectedServerUuid) { throw 'Connected database, port, or server UUID does not match the explicit target' }
    $observed = @(Read-Database $shapeSql | Sort-Object) -join "`n"
    if ($observed -ceq $complete) {
        Write-Output 'Already upgraded: complete 005 structure verified; tables=13; backup=0; DDL=0.'
        return
    }
    if ($observed -cne $baselineText) { throw 'Target is not the exact complete 004 baseline or complete 005 structure; partial or foreign structure refused without backup or DDL' }
    if (Test-Path -LiteralPath $backupFull) { throw 'Backup destination already exists; no DDL was attempted' }
    if (-not (Test-Path -LiteralPath (Split-Path $backupFull -Parent) -PathType Container)) { throw 'Backup parent directory does not exist; no DDL was attempted' }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $dumpPath @connection --single-transaction --quick --set-gtid-purged=OFF "--result-file=$backupFull" $Database 2>$null | Out-Null
        $dumpExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($dumpExit -ne 0 -or -not (Test-Path -LiteralPath $backupFull -PathType Leaf) -or (Get-Item -LiteralPath $backupFull).Length -eq 0) { throw 'Backup failed; no DDL was attempted; any partial backup was retained' }
    $sql = Get-Content -LiteralPath $migration -Raw -Encoding UTF8
    try {
        $ErrorActionPreference = 'Continue'
        $sql | & $mysqlPath @connection --batch --skip-column-names "--database=$Database" 2>$null | Out-Null
        $migrationExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    try { $after = @(Read-Database $shapeSql | Sort-Object) -join "`n" }
    catch { throw 'Migration stopped after backup; structure could not be read. Inspect before retrying; do not DROP or restore over this database. Backup retained.' }
    if ($migrationExit -ne 0 -or $after -cne $complete) { throw 'Migration stopped after backup; complete005=FALSE. Inspect before retrying; do not DROP or restore over this database. Backup and partial structure retained.' }
    Write-Output 'Upgraded exact 004 to complete 005; tables=13; backup=1; DDL=1. Existing rows preserved by an additive-only migration. Use this latest entry; old strict 003/004 intentionally refuse 005.'
} finally { $env:MYSQL_PWD = $previousPassword }
