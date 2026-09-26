param(
    [Parameter(Mandatory = $true)][string]$MySqlCli,
    [Parameter(Mandatory = $true)][string]$MySqlDumpCli,
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$ExpectedServerUuid,
    [Parameter(Mandatory = $true)][string]$BackupPath
)

$ErrorActionPreference = 'Stop'
if ($Database -cne 'medical_cloud_course') { throw 'Only medical_cloud_course may be upgraded' }
if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER)) { throw 'Set MEDICAL_COURSE_DB_USER before upgrading' }
$mysqlPath = (Resolve-Path -LiteralPath $MySqlCli -ErrorAction Stop).Path
$dumpPath = (Resolve-Path -LiteralPath $MySqlDumpCli -ErrorAction Stop).Path
$backupFull = [IO.Path]::GetFullPath($BackupPath)
if (Test-Path -LiteralPath $backupFull) { throw "Backup destination already exists: $backupFull" }
if (-not (Test-Path -LiteralPath (Split-Path $backupFull -Parent) -PathType Container)) {
    throw 'Backup parent directory does not exist'
}
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\migrations\001_p0_completion.sql')).Path
$previousPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:MEDICAL_COURSE_DB_PASSWORD
$connection = @('--no-defaults', '--protocol=TCP', "--host=$HostName", "--port=$Port", '--default-character-set=utf8mb4', "--user=$env:MEDICAL_COURSE_DB_USER")
try {
    $identity = @(& $mysqlPath @connection --batch --skip-column-names --execute='SELECT DATABASE(), @@port, @@server_uuid' "--database=$Database" 2>&1)
    if ($LASTEXITCODE -ne 0 -or $identity.Count -ne 1) { throw "Cannot identify target database: $identity" }
    $parts = $identity[0] -split "`t"
    if ($parts.Count -ne 3 -or $parts[0] -cne $Database -or [int]$parts[1] -ne $Port -or $parts[2] -cne $ExpectedServerUuid) {
        throw 'Connected database, port, or server UUID does not match the explicit target'
    }

    $preflight = @'
SELECT CONCAT('T:',TABLE_NAME) FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN
('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order')
UNION ALL
SELECT CONCAT('C:',TABLE_NAME,':',COLUMN_NAME,':',COLUMN_TYPE,':',IS_NULLABLE)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
AND TABLE_NAME IN ('appointment','clinical_record','payment_order')
UNION ALL
SELECT CONCAT('I:',TABLE_NAME,':',INDEX_NAME,':',NON_UNIQUE,':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
AND ((TABLE_NAME='appointment' AND INDEX_NAME IN ('uk_course_patient_slot','idx_course_patient_slot'))
OR (TABLE_NAME='clinical_record' AND INDEX_NAME='uk_course_record_appointment')
OR (TABLE_NAME='payment_order' AND INDEX_NAME='uk_course_order_appointment'))
GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE ORDER BY 1;
'@
    $shape = @($preflight | & $mysqlPath @connection "--database=$Database" --batch --skip-column-names 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Schema preflight failed: $shape" }
    $expected = @(
        'T:tenant','T:department','T:user_account','T:doctor_profile','T:schedule_slot','T:appointment','T:clinical_record','T:payment_order',
        'C:appointment:id:varchar(36):NO','C:appointment:patient_id:varchar(36):NO',
        'C:appointment:slot_id:varchar(36):NO','C:appointment:tenant_id:varchar(36):NO',
        'C:appointment:doctor_id:varchar(36):NO','C:appointment:fee:decimal(10,2):NO',
        'C:appointment:status:varchar(16):NO','C:appointment:booked_on:date:NO',
        'C:clinical_record:id:varchar(36):NO','C:clinical_record:appointment_id:varchar(36):NO',
        'C:clinical_record:tenant_id:varchar(36):NO','C:clinical_record:doctor_id:varchar(36):NO',
        'C:clinical_record:patient_id:varchar(36):NO','C:clinical_record:diagnosis:varchar(500):NO',
        'C:clinical_record:advice:varchar(2000):NO',
        'C:payment_order:id:varchar(36):NO','C:payment_order:appointment_id:varchar(36):NO',
        'C:payment_order:patient_id:varchar(36):NO','C:payment_order:tenant_id:varchar(36):NO',
        'C:payment_order:amount:decimal(10,2):NO','C:payment_order:status:varchar(16):NO',
        'I:appointment:uk_course_patient_slot:0:patient_id,slot_id',
        'I:clinical_record:uk_course_record_appointment:0:appointment_id',
        'I:payment_order:uk_course_order_appointment:0:appointment_id'
    ) | Sort-Object
    if ((@($shape | Sort-Object) -join "`n") -cne ($expected -join "`n")) {
        throw 'Target is not the complete legacy course schema; no backup or DDL was attempted'
    }

    & $dumpPath @connection --single-transaction --quick --set-gtid-purged=OFF "--result-file=$backupFull" $Database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $backupFull) -or (Get-Item -LiteralPath $backupFull).Length -eq 0) {
        throw "Backup failed; no DDL was attempted: $backupFull"
    }
    $sql = Get-Content -LiteralPath $migration -Raw -Encoding UTF8
    $result = @($sql | & $mysqlPath @connection --batch --skip-column-names "--database=$Database" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Migration stopped after backup; inspect schema before retrying. Backup: $backupFull. MySQL: $result" }
    Write-Output "Upgraded $Database; preserved backup at $backupFull"
} finally {
    $env:MYSQL_PWD = $previousPassword
}
