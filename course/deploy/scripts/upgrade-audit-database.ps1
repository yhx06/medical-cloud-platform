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
if ($BackupPath -notmatch '^[A-Za-z]:[\\/]' -or $BackupPath -match '^\\\\') { throw 'Backup must be an absolute local disk file path' }
$mysqlPath = (Resolve-Path -LiteralPath $MySqlCli).Path
$dumpPath = (Resolve-Path -LiteralPath $MySqlDumpCli).Path
$backupFull = [IO.Path]::GetFullPath($BackupPath)
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../migrations/002_audit_events.sql')).Path
$previousPassword = $env:MYSQL_PWD
$connection = @('--no-defaults','--protocol=TCP',"--host=$HostName","--port=$Port",'--default-character-set=utf8mb4',"--user=$env:MEDICAL_COURSE_DB_USER")

function Read-Database([string]$sql) {
    # Native stderr may contain authentication details. Never incorporate it into errors.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rows = @(& $mysqlPath @connection --connect-timeout=3 "--database=$Database" --batch --skip-column-names "--execute=$sql" 2>$null)
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExit -ne 0) { throw 'Target database read failed; inspect the target before retrying' }
    return $rows
}

# Full P0 baseline, independent of the editable initializer. Ordinal position,
# type, nullability, defaults, extras and character collation are all checked.
$columns = @{
    tenant = 'id:varchar(36):NO name:varchar(100):NO status:varchar(16):NO'
    department = 'id:varchar(36):NO tenant_id:varchar(36):NO name:varchar(100):NO status:varchar(16):NO'
    user_account = 'id:varchar(36):NO username:varchar(64):NO password_hash:varchar(100):NO role:varchar(32):NO status:varchar(16):NO tenant_id:varchar(36):YES'
    doctor_profile = 'user_id:varchar(36):NO tenant_id:varchar(36):NO department_id:varchar(36):NO display_name:varchar(100):NO'
    schedule_slot = 'id:varchar(36):NO tenant_id:varchar(36):NO department_id:varchar(36):NO doctor_id:varchar(36):NO schedule_date:date:NO start_time:time:NO end_time:time:NO capacity:int:NO fee:decimal(10,2):NO status:varchar(16):NO'
    appointment = 'id:varchar(36):NO patient_id:varchar(36):NO slot_id:varchar(36):NO tenant_id:varchar(36):NO doctor_id:varchar(36):NO fee:decimal(10,2):NO status:varchar(16):NO booked_on:date:NO booked_at:datetime(6):YES started_at:datetime:YES'
    clinical_record = 'id:varchar(36):NO appointment_id:varchar(36):NO tenant_id:varchar(36):NO doctor_id:varchar(36):NO patient_id:varchar(36):NO diagnosis:varchar(500):NO advice:varchar(2000):NO complaint:varchar(500):YES notes:varchar(2000):YES completed_at:datetime:YES'
    payment_order = 'id:varchar(36):NO appointment_id:varchar(36):NO patient_id:varchar(36):NO tenant_id:varchar(36):NO amount:decimal(10,2):NO status:varchar(16):NO paid_at:datetime:YES'
    audit_event = 'id:varchar(36):NO request_id:varchar(36):NO actor_id:varchar(36):YES actor_role:varchar(32):YES tenant_id:varchar(36):YES event_type:varchar(64):NO object_type:varchar(32):NO object_id:varchar(36):YES result:varchar(16):NO reason_code:varchar(32):NO recorded_at:datetime(6):NO'
}
$indexes = @{
    tenant = 'PRIMARY:0:id'
    department = 'PRIMARY:0:id uk_department_tenant_name:0:tenant_id,name'
    user_account = 'PRIMARY:0:id uk_course_username:0:username fk_course_user_tenant:1:tenant_id'
    doctor_profile = 'PRIMARY:0:user_id fk_course_doctor_tenant:1:tenant_id fk_course_doctor_department:1:department_id'
    schedule_slot = 'PRIMARY:0:id uk_course_doctor_slot:0:doctor_id,schedule_date,start_time idx_course_slot_catalog:1:tenant_id,status,schedule_date fk_course_slot_department:1:department_id'
    appointment = 'PRIMARY:0:id idx_course_patient_slot:1:patient_id,slot_id idx_course_doctor_appointment:1:doctor_id,tenant_id,booked_on fk_course_appointment_slot:1:slot_id fk_course_appointment_tenant:1:tenant_id'
    clinical_record = 'PRIMARY:0:id uk_course_record_appointment:0:appointment_id'
    payment_order = 'PRIMARY:0:id uk_course_order_appointment:0:appointment_id'
    audit_event = 'PRIMARY:0:id uk_audit_request_event:0:request_id,event_type idx_audit_recorded:1:recorded_at,id idx_audit_tenant:1:tenant_id,recorded_at,id idx_audit_event_result:1:event_type,result,recorded_at,id'
}
$foreignKeys = @(
    'department:fk_department_tenant:tenant_id:tenant:id',
    'user_account:fk_course_user_tenant:tenant_id:tenant:id',
    'doctor_profile:fk_course_doctor_user:user_id:user_account:id',
    'doctor_profile:fk_course_doctor_tenant:tenant_id:tenant:id',
    'doctor_profile:fk_course_doctor_department:department_id:department:id',
    'schedule_slot:fk_course_slot_tenant:tenant_id:tenant:id',
    'schedule_slot:fk_course_slot_department:department_id:department:id',
    'schedule_slot:fk_course_slot_doctor:doctor_id:user_account:id',
    'appointment:fk_course_appointment_patient:patient_id:user_account:id',
    'appointment:fk_course_appointment_slot:slot_id:schedule_slot:id',
    'appointment:fk_course_appointment_tenant:tenant_id:tenant:id',
    'appointment:fk_course_appointment_doctor:doctor_id:user_account:id',
    'clinical_record:fk_course_record_appointment:appointment_id:appointment:id',
    'payment_order:fk_course_order_appointment:appointment_id:appointment:id'
)

function Get-ExpectedShape([string[]]$tables) {
    $expected = @()
    foreach ($table in $tables) {
        $expected += "T:${table}:BASE TABLE:InnoDB:utf8mb4_unicode_ci"
        $ordinal = 0
        foreach ($column in ($columns[$table] -split ' ')) {
            $ordinal++
            $parts = $column -split ':'
            $collation = if ($parts[1] -like 'varchar*') { 'utf8mb4:utf8mb4_unicode_ci' } else { '~:~' }
            $expected += "C:${table}:${ordinal}:${column}:NULL::$collation"
        }
        foreach ($index in ($indexes[$table] -split ' ')) {
            $parts = $index -split ':'
            $indexColumns = @($parts[2] -split ',' | ForEach-Object { "${_}:~:A" }) -join ','
            $expected += "I:${table}:$($parts[0]):$($parts[1]):BTREE:$indexColumns"
        }
    }
    foreach ($foreign in $foreignKeys) {
        if (($foreign -split ':')[0] -in $tables) { $expected += "F:${foreign}:medical_cloud_course:RESTRICT:RESTRICT" }
    }
    return @($expected | Sort-Object)
}

$shapeSql = @'
SELECT CONCAT('T:',TABLE_NAME,':',TABLE_TYPE,':',IFNULL(ENGINE,'~'),':',IFNULL(TABLE_COLLATION,'~'))
FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
UNION ALL
SELECT CONCAT('C:',TABLE_NAME,':',ORDINAL_POSITION,':',COLUMN_NAME,':',REPLACE(COLUMN_TYPE,'int(11)','int'),':',IS_NULLABLE,':',IF(COLUMN_DEFAULT IS NULL,'NULL',CONCAT('VALUE:',COLUMN_DEFAULT)),':',EXTRA,':',IFNULL(CHARACTER_SET_NAME,'~'),':',IFNULL(COLLATION_NAME,'~'))
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
UNION ALL
SELECT CONCAT('I:',TABLE_NAME,':',INDEX_NAME,':',NON_UNIQUE,':',INDEX_TYPE,':',GROUP_CONCAT(CONCAT(COLUMN_NAME,':',IFNULL(SUB_PART,'~'),':',IFNULL(COLLATION,'~')) ORDER BY SEQ_IN_INDEX))
FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE,INDEX_TYPE
UNION ALL
SELECT CONCAT('F:',k.TABLE_NAME,':',k.CONSTRAINT_NAME,':',k.COLUMN_NAME,':',k.REFERENCED_TABLE_NAME,':',k.REFERENCED_COLUMN_NAME,':',k.REFERENCED_TABLE_SCHEMA,':',REPLACE(r.UPDATE_RULE,'NO ACTION','RESTRICT'),':',REPLACE(r.DELETE_RULE,'NO ACTION','RESTRICT'))
FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r
ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.TABLE_NAME=r.TABLE_NAME AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME
WHERE k.CONSTRAINT_SCHEMA=DATABASE()
UNION ALL
SELECT CONCAT('X:TRIGGER:',TRIGGER_NAME) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
UNION ALL
SELECT CONCAT('X:CONSTRAINT:',TABLE_NAME,':',CONSTRAINT_NAME) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_TYPE NOT IN ('PRIMARY KEY','UNIQUE','FOREIGN KEY')
ORDER BY 1;
'@

try {
    $env:MYSQL_PWD = $env:MEDICAL_COURSE_DB_PASSWORD
    $identity = @(Read-Database 'SELECT DATABASE(), @@port, @@server_uuid')
    if ($identity.Count -ne 1) { throw 'Cannot identify target database; no DDL was attempted' }
    $parts = $identity[0] -split "`t"
    if ($parts.Count -ne 3 -or $parts[0] -cne $Database -or [int]$parts[1] -ne $Port -or $parts[2] -cne $ExpectedServerUuid) { throw 'Connected database, port, or server UUID does not match the explicit target' }
    $shape = @(Read-Database $shapeSql)
    $businessTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order')
    $businessShape = @($shape | Where-Object { $_ -notmatch '^[TCIF]:audit_event:' } | Sort-Object)
    if (($businessShape -join "`n") -cne ((Get-ExpectedShape $businessTables) -join "`n")) { throw 'Target is not the complete P0 course baseline; no backup or DDL was attempted' }
    $auditShape = @($shape | Where-Object { $_ -match '^[TCIF]:audit_event:' } | Sort-Object)
    if ($auditShape.Count) {
        if (($auditShape -join "`n") -cne ((Get-ExpectedShape @('audit_event')) -join "`n")) { throw 'Existing audit_event has a partial or foreign shape; refused repair, backup and DDL' }
        Write-Output 'Already upgraded: complete audit_event verified; no backup or writes attempted'
        return
    }
    if (Test-Path -LiteralPath $backupFull) { throw 'Backup destination already exists; no DDL was attempted' }
    if (-not (Test-Path -LiteralPath (Split-Path $backupFull -Parent) -PathType Container)) { throw 'Backup parent directory does not exist; no DDL was attempted' }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $dumpPath @connection --single-transaction --quick --set-gtid-purged=OFF "--result-file=$backupFull" $Database 2>$null | Out-Null
        $dumpExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($dumpExit -ne 0 -or -not (Test-Path -LiteralPath $backupFull) -or (Get-Item -LiteralPath $backupFull).Length -eq 0) { throw "Backup failed; no DDL was attempted. Partial backup retained at $backupFull" }
    $sql = Get-Content -LiteralPath $migration -Raw -Encoding UTF8
    try {
        $ErrorActionPreference = 'Continue'
        $sql | & $mysqlPath @connection --batch --skip-column-names "--database=$Database" 2>$null | Out-Null
        $migrationExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    try { $afterShape = @(Read-Database $shapeSql | Sort-Object) }
    catch { throw "Migration stopped after backup; actual structure could not be read. Inspect before retrying; do not DROP or restore over this database. Backup: $backupFull" }
    $actualAudit = @($afterShape | Where-Object { $_ -match '^[TCIF]:audit_event:' } | Sort-Object)
    if ($migrationExit -ne 0 -or ($afterShape -join "`n") -cne ((Get-ExpectedShape ($businessTables + 'audit_event')) -join "`n")) {
        throw "Migration stopped after backup. Actual audit structure: $($actualAudit -join '; '). Inspect before retrying; do not DROP or restore over this database. Backup: $backupFull"
    }
    Write-Output "Upgraded $Database by adding only audit_event; preserved backup at $backupFull"
} finally { $env:MYSQL_PWD = $previousPassword }
