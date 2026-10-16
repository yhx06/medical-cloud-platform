# Deployment: 001 -> 002 -> 003 -> 004. The unchanged strict 003 upgrader
# refuses the new appointment columns after 004. Use this latest entry point
# to verify complete clinical structure; do not weaken or rerun 003 after 004.
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
if ($Port -in @(3318,8180,8188,10090)) { throw 'Protected original or container port; no SQL was attempted' }
if ([string]::IsNullOrWhiteSpace($HostName)) { throw 'Explicit host required; no SQL was attempted' }
$parsedUuid = [guid]::Empty
if (-not [guid]::TryParseExact($ExpectedServerUuid, 'D', [ref]$parsedUuid) -or $parsedUuid -eq [guid]::Empty) { throw 'Explicit nonempty canonical server UUID required; no SQL was attempted' }
if ($Database -cne 'medical_cloud_course') { throw 'Only medical_cloud_course may be upgraded' }
if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER)) { throw 'Set MEDICAL_COURSE_DB_USER before upgrading' }
if ($BackupPath -notmatch '^[A-Za-z]:[\\/]' -or $BackupPath -match '^\\\\') { throw 'Backup must be an absolute local disk file path' }
$mysqlPath = (Resolve-Path -LiteralPath $MySqlCli).Path
$dumpPath = (Resolve-Path -LiteralPath $MySqlDumpCli).Path
$backupFull = [IO.Path]::GetFullPath($BackupPath)
$migration = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../migrations/004_clinical_supplements.sql')).Path
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
    file_metadata = 'id:varchar(36):NO tenant_id:varchar(36):NO appointment_id:varchar(36):NO patient_id:varchar(36):NO doctor_id:varchar(36):NO uploader_id:varchar(36):NO record_id:varchar(36):YES source_type:varchar(32):NO file_name:varchar(255):NO content_type:varchar(64):NO size_bytes:bigint:NO sha256:varchar(64):NO object_key:varchar(128):NO upload_key:varchar(36):NO state:varchar(32):NO created_at:datetime(6):NO updated_at:datetime(6):NO lease_until:datetime(6):YES deleted_at:datetime(6):YES attempt_token:varchar(36):YES retry_count:int:NO next_retry_at:datetime(6):YES version:bigint:NO'
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
    file_metadata = 'PRIMARY:0:id uk_attachment_upload:0:uploader_id,upload_key uk_attachment_object:0:object_key idx_attachment_listing:1:appointment_id,source_type,state,created_at,id idx_attachment_tenant:1:tenant_id,state idx_attachment_cleanup:1:state,next_retry_at,id idx_attachment_lease:1:state,lease_until,id fk_attachment_patient:1:patient_id fk_attachment_doctor:1:doctor_id fk_attachment_record:1:record_id'
    audit_event = 'PRIMARY:0:id uk_audit_request_event:0:request_id,event_type idx_audit_recorded:1:recorded_at,id idx_audit_tenant:1:tenant_id,recorded_at,id idx_audit_event_result:1:event_type,result,recorded_at,id'
}
$foreignKeys = @(
    'file_metadata:fk_attachment_tenant:tenant_id:tenant:id',
    'file_metadata:fk_attachment_appointment:appointment_id:appointment:id',
    'file_metadata:fk_attachment_patient:patient_id:user_account:id',
    'file_metadata:fk_attachment_doctor:doctor_id:user_account:id',
    'file_metadata:fk_attachment_uploader:uploader_id:user_account:id',
    'file_metadata:fk_attachment_record:record_id:clinical_record:id',
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

$clinicalColumns = @{
    clinical_draft = 'id:varchar(36):NO appointment_id:varchar(36):NO tenant_id:varchar(36):NO doctor_id:varchar(36):NO patient_id:varchar(36):NO complaint:varchar(500):YES diagnosis:varchar(500):YES advice:varchar(2000):YES notes:varchar(2000):YES saved_at:datetime(6):NO revision:bigint:NO'
    record_correction = 'id:varchar(36):NO record_id:varchar(36):NO appointment_id:varchar(36):NO tenant_id:varchar(36):NO doctor_id:varchar(36):NO patient_id:varchar(36):NO sequence_number:bigint:NO before_complaint:varchar(500):YES before_diagnosis:varchar(500):NO before_advice:varchar(2000):NO before_notes:varchar(2000):YES after_complaint:varchar(500):YES after_diagnosis:varchar(500):NO after_advice:varchar(2000):NO after_notes:varchar(2000):YES reason:varchar(2000):NO created_at:datetime(6):NO operation_key:varchar(36):NO'
}
$columns.clinical_draft = $clinicalColumns.clinical_draft
$columns.record_correction = $clinicalColumns.record_correction
$indexes.clinical_draft = 'PRIMARY:0:id uk_clinical_draft_appointment:0:appointment_id fk_clinical_draft_tenant:1:tenant_id fk_clinical_draft_doctor:1:doctor_id fk_clinical_draft_patient:1:patient_id'
$indexes.record_correction = 'PRIMARY:0:id uk_record_correction_sequence:0:record_id,sequence_number uk_record_correction_operation:0:doctor_id,operation_key fk_record_correction_appointment:1:appointment_id fk_record_correction_tenant:1:tenant_id fk_record_correction_patient:1:patient_id'
$foreignKeys += @(
    'clinical_draft:fk_clinical_draft_appointment:appointment_id:appointment:id',
    'clinical_draft:fk_clinical_draft_tenant:tenant_id:tenant:id',
    'clinical_draft:fk_clinical_draft_doctor:doctor_id:user_account:id',
    'clinical_draft:fk_clinical_draft_patient:patient_id:user_account:id',
    'record_correction:fk_record_correction_record:record_id:clinical_record:id',
    'record_correction:fk_record_correction_appointment:appointment_id:appointment:id',
    'record_correction:fk_record_correction_tenant:tenant_id:tenant:id',
    'record_correction:fk_record_correction_doctor:doctor_id:user_account:id',
    'record_correction:fk_record_correction_patient:patient_id:user_account:id'
)

function Get-ExpectedShape([string[]]$tables, [bool]$clinical = $false) {
    $expected = @()
    foreach ($table in $tables) {
        $expected += "T:${table}:BASE TABLE:InnoDB:utf8mb4_unicode_ci"
        $ordinal = 0
        $tableColumns = $columns[$table]
        if ($clinical -and $table -eq 'appointment') { $tableColumns += ' patient_complaint:varchar(500):YES complaint_updated_at:datetime(6):YES complaint_revision:bigint:NO' }
        foreach ($column in ($tableColumns -split ' ')) {
            $ordinal++
            $parts = $column -split ':'
            $collation = if ($parts[1] -like 'varchar*') { 'utf8mb4:utf8mb4_unicode_ci' } else { '~:~' }
            if ($table -eq 'file_metadata' -and $parts[0] -in @('sha256','object_key','upload_key','attempt_token')) { $collation = 'ascii:ascii_bin' }
            $default = if ($table -eq 'appointment' -and $parts[0] -eq 'complaint_revision') { 'VALUE:0' } else { 'NULL' }
            $expected += "C:${table}:${ordinal}:${column}:${default}::$collation"
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
SELECT CONCAT('C:',TABLE_NAME,':',ORDINAL_POSITION,':',COLUMN_NAME,':',REPLACE(REPLACE(COLUMN_TYPE,'bigint(20)','bigint'),'int(11)','int'),':',IS_NULLABLE,':',IF(COLUMN_DEFAULT IS NULL,'NULL',CONCAT('VALUE:',COLUMN_DEFAULT)),':',EXTRA,':',IFNULL(CHARACTER_SET_NAME,'~'),':',IFNULL(COLLATION_NAME,'~'))
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
    $baselineTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order','audit_event','file_metadata')
    $completeTables = $baselineTables + @('clinical_draft','record_correction')
    $observed = @($shape | Sort-Object) -join "`n"
    $baseline = (Get-ExpectedShape $baselineTables) -join "`n"
    $complete = (Get-ExpectedShape $completeTables $true) -join "`n"
    if ($observed -ceq $complete) {
        Write-Output 'Already upgraded: complete clinical structure verified; no backup or writes attempted. Use this latest upgrader after 004; old strict 003 intentionally refuses it.'
        return
    }
    if ($observed -cne $baseline) { throw 'Target is not the exact complete ten-table 003 baseline or complete 004 structure; partial or foreign structure refused without backup or DDL' }
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
    $actualClinical = @($afterShape | Where-Object { $_ -match '^[TCIF]:(clinical_draft|record_correction):|^C:appointment:(11|12|13):' })
    if ($migrationExit -ne 0 -or ($afterShape -join "`n") -cne $complete) {
        throw "Migration stopped after backup. Actual clinical structure: $($actualClinical -join '; '). Inspect before retrying; do not DROP or restore over this database. Backup: $backupFull"
    }
    Write-Output "Upgraded $Database by adding only three appointment columns, clinical_draft and record_correction; preserved backup at $backupFull. Old strict 003 now refuses this shape; use the latest clinical upgrader."
} finally { $env:MYSQL_PWD = $previousPassword }
