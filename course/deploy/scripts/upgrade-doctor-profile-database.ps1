# Latest deployment entry: exact 006 -> 007, or complete 007 read-only.
# Strict 003/004/005/006 upgrade scripts remain unchanged and intentionally reject 007.
# This entry verifies the specific additive change it owns (doctor_profile gains
# nullable title/bio) instead of re-deriving the whole shape; 006 is guaranteed by
# upgrade-payment-reference-database.ps1.
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
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../migrations/007_doctor_profile.sql')).Path

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

    $marker = @(Read-Database "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='medical_cloud_course' AND table_type='BASE TABLE'),':',(SELECT SUM(table_name='hospital_admin_operation') FROM information_schema.tables WHERE table_schema='medical_cloud_course' AND table_type='BASE TABLE'),':',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='payment_order' AND column_name='payment_reference'),':',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='doctor_profile' AND column_name='title'),':',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='doctor_profile' AND column_name='bio'))")
    if ($marker.Count -ne 1) { throw 'Cannot probe target structure; no DDL was attempted' }
    $markerParts = ($marker[0] -split ':')
    if ($markerParts.Count -ne 5) { throw 'Structure probe shape unexpected; no DDL was attempted' }
    $tableCount = $markerParts[0]; $hospitalMarker = $markerParts[1]; $refColumn = $markerParts[2]; $titleColumn = $markerParts[3]; $bioColumn = $markerParts[4]
    if ($tableCount -cne '13' -or $hospitalMarker -cne '1' -or $refColumn -cne '1') { throw 'Target is not a complete 006 baseline; partial or foreign structure refused without backup or DDL' }
    if ($titleColumn -ceq '1' -and $bioColumn -ceq '1') {
        Write-Output 'Already upgraded: complete 007 structure verified; tables=13; backup=0; DDL=0.'
        return
    }
    if ($titleColumn -cne '0' -or $bioColumn -cne '0') { throw 'doctor_profile title/bio is in a partial state; refusing without review' }

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
    $after = @(Read-Database "SELECT CONCAT((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='doctor_profile' AND column_name='title'),':',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='medical_cloud_course' AND table_name='doctor_profile' AND column_name='bio'))")
    if ($migrationExit -ne 0 -or $after.Count -ne 1 -or ($after[0] -split ':')[0] -cne '1' -or ($after[0] -split ':')[1] -cne '1') { throw 'Migration stopped after backup; complete007=FALSE. Inspect before retrying; do not DROP or restore over this database. Backup and partial structure retained.' }
    Write-Output 'Upgraded exact 006 to complete 007; tables=13; backup=1; DDL=1. doctor_profile gains nullable title/bio; existing rows unchanged. Use this latest entry; old strict 003/004/005/006 intentionally refuse 007.'
} finally { $env:MYSQL_PWD = $previousPassword }
