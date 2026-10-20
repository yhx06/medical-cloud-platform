# Latest deployment entry: exact 005 -> 006, or exact 006 read-only.
# Every table/column/index/FK is compared with pinned immutable metadata.
# No prior upgrader main is executed; frozen003/004/005 remain unchanged.
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

. (Join-Path $PSScriptRoot 'protected-migration-metadata.ps1')
$metadata = Get-ProtectedMigrationMetadata $PSScriptRoot
$baselineShape = $metadata.Shape005
$baselineText = $baselineShape -join "`n"
$complete = $metadata.Shape006 -join "`n"
$shapeSql = $metadata.ShapeSql
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

# Preserve all pre-existing columns of all13 tables. HEX encodes bytes without
# delimiter/newline/NULL ambiguity; private rows stay only in process memory.
function Get-PreservationSql([string[]]$shape) {
    $queries = @()
    foreach ($table in @($shape | Where-Object { $_ -like 'T:*' } | ForEach-Object { ($_ -split ':')[1] } | Sort-Object)) {
        $expressions = @()
        foreach ($column in @($shape | Where-Object { $_ -like "C:${table}:*" } | Sort-Object { [int](($_ -split ':')[2]) })) {
            $quoted = '`' + ($column -split ':')[3] + '`'
            $expressions += ('IF({0} IS NULL,''N'',CONCAT(''H'',HEX(CAST({0} AS BINARY))))' -f $quoted)
        }
        $queries += ("SELECT CONCAT('D:" + $table + ":'," + ($expressions -join ",':',") + ') FROM `' + $table + '`')
    }
    return "/* protected-row-snapshot: private, never log */`n" + ($queries -join "`nUNION ALL`n")
}

function Assert-PaymentData {
    $sql = @'
/* protected-payment-invariants */
SELECT
 COALESCE(SUM(status='PAID' AND (payment_reference IS NULL
   OR CHAR_LENGTH(payment_reference) <> 25 OR OCTET_LENGTH(payment_reference) <> 25
   OR BINARY payment_reference <> BINARY UPPER(payment_reference)
   OR NOT (payment_reference COLLATE utf8mb4_bin REGEXP '^PAY-[0-9]{8}-[0-9A-F]{12}$')
   OR BINARY LEFT(payment_reference,13) <> BINARY CONCAT('PAY-',COALESCE(DATE_FORMAT(paid_at,'%Y%m%d'),'00000000'),'-'))),0),
 COALESCE(SUM(status='UNPAID' AND payment_reference IS NOT NULL),0),
 COUNT(CASE WHEN status='PAID' THEN payment_reference END)
   - COUNT(DISTINCT BINARY payment_reference)
FROM payment_order;
'@
    $validation = @(Read-Database $sql)
    if ($validation.Count -ne 1 -or $validation[0] -cne "0`t0`t0") { throw 'Payment backfill invariants failed; inspect the target before retrying' }
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
        Assert-PaymentData
        Write-Output 'Already upgraded: complete 006 structure verified; tables=13; backup=0; DDL=0.'
        return
    }
    if ($observed -cne $baselineText) { throw 'Target is not the exact complete 005 baseline or complete 006 structure; partial, foreign or later structure refused without backup or DDL' }

    if (Test-Path -LiteralPath $backupFull) { throw 'Backup destination already exists; no DDL was attempted' }
    if (-not (Test-Path -LiteralPath (Split-Path $backupFull -Parent) -PathType Container)) { throw 'Backup parent directory does not exist; no DDL was attempted' }
    $preservationSql = Get-PreservationSql $baselineShape
    $beforeRows = @(Read-Database $preservationSql | Sort-Object) -join "`n"
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
    try {
        $after = @(Read-Database $shapeSql | Sort-Object) -join "`n"
        if ($migrationExit -ne 0 -or $after -cne $complete) { throw 'Incomplete006' }
        $afterRows = @(Read-Database $preservationSql | Sort-Object) -join "`n"
        if ($afterRows -cne $beforeRows) { throw 'Pre-existing rows changed' }
        Assert-PaymentData
    } catch { throw 'Migration stopped after backup; complete006=FALSE. Inspect before retrying; do not DROP or restore over this database. Backup and partial structure retained.' }
    Write-Output 'Upgraded exact 005 to complete 006; tables=13; backup=1; DDL=1. Legacy PAID rows backfilled with unique references; amounts, paid_at and records unchanged. Use this latest entry; old strict 003/004/005 intentionally refuse 006.'
} finally { $beforeRows = $null; $afterRows = $null; $env:MYSQL_PWD = $previousPassword }
