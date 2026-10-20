# Latest deployment entry: exact 005 -> 006, or complete 006 read-only.
# Strict 003/004/005 upgrade scripts remain unchanged and intentionally reject 006.
# This entry verifies the specific additive change it owns (payment_order gains a
# unique payment_reference) instead of re-deriving the whole 13-table shape; the
# exact 005 shape is already guaranteed by upgrade-hospital-onboarding-database.ps1.
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

    # Baseline/complete probe for the specific change this entry owns.
    $marker = @(Read-Database "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='medical_cloud_course' AND table_type='BASE TABLE'),':',(SELECT SUM(table_name='hospital_admin_operation') FROM information_schema.tables WHERE table_schema='medical_cloud_course' AND table_type='BASE TABLE'),':',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='payment_order' AND column_name='payment_reference'),':',(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='medical_cloud_course' AND table_name='payment_order' AND index_name='uk_course_order_payment_reference'))")
    if ($marker.Count -ne 1) { throw 'Cannot probe target structure; no DDL was attempted' }
    $markerParts = ($marker[0] -split ':')
    if ($markerParts.Count -ne 4) { throw 'Structure probe shape unexpected; no DDL was attempted' }
    $tableCount = $markerParts[0]; $hospitalMarker = $markerParts[1]; $refColumns = $markerParts[2]; $refIndexes = $markerParts[3]
    if ($tableCount -cne '13' -or $hospitalMarker -cne '1') { throw 'Target is not a complete 005 baseline (13 tables with hospital_admin_operation); partial or foreign structure refused without backup or DDL' }
    if ($refColumns -ceq '1' -and $refIndexes -ceq '1') {
        Write-Output 'Already upgraded: complete 006 structure verified; tables=13; backup=0; DDL=0.'
        return
    }
    if ($refColumns -cne '0' -or $refIndexes -cne '0') { throw 'payment_reference is in a partial state; refusing without review' }

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
    $after = @(Read-Database "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='payment_order' AND column_name='payment_reference'),':',(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='medical_cloud_course' AND table_name='payment_order' AND index_name='uk_course_order_payment_reference'))")
    if ($migrationExit -ne 0 -or $after.Count -ne 1 -or ($after[0] -split ':')[0] -cne '1' -or ($after[0] -split ':')[1] -cne '1') { throw 'Migration stopped after backup; complete006=FALSE. Inspect before retrying; do not DROP or restore over this database. Backup and partial structure retained.' }
    Write-Output 'Upgraded exact 005 to complete 006; tables=13; backup=1; DDL=1. Legacy PAID rows backfilled with unique references; amounts, paid_at and records unchanged. Use this latest entry; old strict 003/004/005 intentionally refuse 006.'
} finally { $env:MYSQL_PWD = $previousPassword }
