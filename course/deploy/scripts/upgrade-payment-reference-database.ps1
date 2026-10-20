# Latest deployment entry: exact 005 -> 006, or complete 006 read-only.
# Strict 003/004/005 upgrade scripts remain unchanged and intentionally reject 006.
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
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../migrations/006_payment_reference.sql')).Path

# Import only frozen 004 metadata declarations and its pure shape builder (same source the 005 entry pins).
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

# Import the frozen 005 entry's hospital_admin_operation shape; never dot-source it (its main works).
$meta005Path = Join-Path $PSScriptRoot 'upgrade-hospital-onboarding-database.ps1'
if (-not (Test-Path -LiteralPath $meta005Path -PathType Leaf)) { throw 'Frozen 005 metadata dependency is unavailable; no SQL was attempted' }
$meta005Text = [IO.File]::ReadAllText($meta005Path).Replace("`r`n","`n")
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint005 = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($meta005Text))).Replace('-','') }
finally { $sha.Dispose() }
if ($fingerprint005 -cne '747E400D44F805D2F7FF6D9DC68E79BA1ABB958FFCBA5FF80D32483D9CDD56D7') { throw 'Frozen 005 metadata dependency changed; no SQL was attempted' }
$tokens005 = $null; $parseErrors005 = $null
$meta005Ast = [Management.Automation.Language.Parser]::ParseInput($meta005Text,[ref]$tokens005,[ref]$parseErrors005)
if ($parseErrors005.Count) { throw 'Frozen 005 metadata dependency is invalid; no SQL was attempted' }
$newShape005 = @($meta005Ast.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.AssignmentStatementAst] -and
    ($_.Left.Extent.Text + ':' + $_.Operator.ToString()) -ceq '$newShape:Equals'
})
if ($newShape005.Count -ne 1) { throw 'Frozen 005 newShape declaration is missing or duplicated; no SQL was attempted' }
. ([scriptblock]::Create($newShape005[0].Extent.Text))

$oldTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order','audit_event','file_metadata','clinical_draft','record_correction')
$baseline = @(($newShape + (Get-ExpectedShape $oldTables $true)) | Sort-Object)
$newShape006 = @(
    'C:payment_order:8:payment_reference:varchar(25):YES:NULL::~:utf8mb4:utf8mb4_unicode_ci',
    'I:payment_order:uk_course_order_payment_reference:0:BTREE:payment_reference:~:A'
)
$complete = @(($baseline + $newShape006) | Sort-Object) -join "`n"
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
        Write-Output 'Already upgraded: complete 006 structure verified; tables=13; backup=0; DDL=0.'
        return
    }
    if ($observed -cne $baselineText) { throw 'Target is not the exact complete 005 baseline or complete 006 structure; partial or foreign structure refused without backup or DDL' }
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
    if ($migrationExit -ne 0 -or $after -cne $complete) { throw 'Migration stopped after backup; complete006=FALSE. Inspect before retrying; do not DROP or restore over this database. Backup and partial structure retained.' }
    Write-Output 'Upgraded exact 005 to complete 006; tables=13; backup=1; DDL=1. Legacy PAID rows backfilled with unique references; amounts, paid_at and records unchanged. Use this latest entry; old strict 003/004/005 intentionally refuse 006.'
} finally { $env:MYSQL_PWD = $previousPassword }
