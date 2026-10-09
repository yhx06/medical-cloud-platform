[CmdletBinding()]
param([string]$PaasBaseUrl,[string]$PortalBaseUrl,[string]$FixturePath,[switch]$DatabaseOnly,[switch]$FaultsOnly,
 [string]$MySqlCli='D:/MySQL/bin/mysql.exe',[string]$MySqlServer='D:/MySQL/bin/mysqld.exe',
 [string]$MavenCli='D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
if(-not $DatabaseOnly) {
 foreach($url in @($PaasBaseUrl,$PortalBaseUrl)) {
  $uri=$null
  if(-not [Uri]::TryCreate($url,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -in @(3318,8180,8188,10090) -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'Dedicated explicit loopback container URLs required' }
 }
 if($FixturePath -notmatch '^[A-Za-z]:[\\/]' -or $FixturePath.StartsWith('\\') -or -not (Test-Path -LiteralPath $FixturePath -PathType Leaf)) { throw 'Existing absolute local fixture required; UNC/relative paths refused' }
 $fixture=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
 $uuid='^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
 if($fixture.tenantId -notmatch $uuid -or $fixture.otherTenantId -notmatch $uuid -or $fixture.emptyTenantId -notmatch $uuid -or $fixture.tenantName -notmatch '^course_audit_acceptance_[a-f0-9]{32}$') { throw 'Only a dedicated fictional audit fixture is accepted' }
 foreach($role in @('platform','hospital','hospitalOther','doctor','patient','patientOther')) {
  if($fixture.accounts.$role.id -notmatch $uuid -or $fixture.accounts.$role.username -notmatch '^qa_[A-Za-z]+_[a-f0-9]{32}$' -or -not $fixture.accounts.$role.password) { throw 'Dedicated audit account validation failed' }
 }
 if($fixture.database.host -ne '127.0.0.1' -or $fixture.database.port -in @(3318,8180,8188,10090) -or $fixture.database.uuid -notmatch $uuid -or [IO.Path]::GetFileName((Split-Path $fixture.database.datadir -Parent)) -notmatch '^course-audit-acceptance-[a-f0-9]{32}$') { throw 'Only the owned disposable SQL instance is accepted' }
 if($fixture.paasBaseUrl.TrimEnd('/') -ne $PaasBaseUrl.TrimEnd('/') -or $fixture.portalBaseUrl.TrimEnd('/') -ne $PortalBaseUrl.TrimEnd('/')) { throw 'Explicit container URLs differ from the fixture' }
 $mysql=(Resolve-Path -LiteralPath $MySqlCli).Path
 $run=[guid]::NewGuid().ToString('N')
 $evidence=Join-Path (Split-Path $FixturePath -Parent) ('http-'+$run)
 New-Item -ItemType Directory -Path $evidence | Out-Null
 $base=$PaasBaseUrl.TrimEnd('/')
 $priorPassword=$env:MYSQL_PWD
 $env:MYSQL_PWD=$fixture.database.password
 $ledger=New-Object Collections.Generic.List[object]
 $eventNames=New-Object 'Collections.Generic.HashSet[string]'
 $tokens=@{}
 $triggers=@(('qa_audit_success_'+$run.Substring(0,12)),('qa_audit_login_'+$run.Substring(0,12)),('qa_audit_failed_'+$run.Substring(0,12)))
 if($triggers.Count -ne 3 -or @($triggers | Where-Object { $_ -notmatch '^qa_audit_(success|login|failed)_[a-f0-9]{12}$' }).Count) { throw 'Exact independent fault-trigger identifiers required' }
 function Check($condition,[string]$message) { if(-not $condition) { throw $message } }
 function Sql([string]$sql) {
  if($sql -match '^CREATE TRIGGER ') { $sql="DELIMITER //`n"+$sql+"//`nDELIMITER ;" }
  $rows=@(& $mysql --no-defaults --protocol=TCP --host=127.0.0.1 ("--port="+$fixture.database.port) ("--user="+$fixture.database.user) --database=medical_cloud_course --batch --skip-column-names "--execute=$sql" 2>&1)
  if($LASTEXITCODE -ne 0) {
   $numbers=@($rows | ForEach-Object { if($_.ToString() -match '^ERROR ([0-9]+)') { $Matches[1] } })
   throw ('Dedicated acceptance SQL failed; diagnostic numbers='+($numbers -join ',')+'; native/clinical text withheld')
  }; return $rows
 }
 function Assert-Database {
  $identity=@(Sql 'SELECT @@server_uuid,@@port,@@datadir')
  $parts=$identity[0] -split [char]9
  Check ($parts.Count -eq 3 -and $parts[0] -eq $fixture.database.uuid -and [int]$parts[1] -eq $fixture.database.port -and [IO.Path]::GetFullPath($parts[2]).TrimEnd('\','/') -ieq [IO.Path]::GetFullPath($fixture.database.datadir).TrimEnd('\','/')) 'Acceptance database identity mismatch'
 }
 function Request([string]$method,[string]$path,[string]$token,$body=$null) {
  $request=[Net.HttpWebRequest]::Create($base+$path)
  $request.Method=$method; $request.Timeout=20000; $request.AllowAutoRedirect=$false
  $request.Accept='application/json'; $request.Headers['X-Request-ID']='00000000-0000-4000-8000-000000000001'
  if($token) { $request.Headers['Authorization']='Bearer '+$token }
  if($method -in @('POST','PUT')) {
   $request.ContentType='application/json; charset=utf-8'
   $bytes=[Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6 -Compress))
   $request.ContentLength=$bytes.Length
   $stream=$request.GetRequestStream(); try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
  }
  $timer=[Diagnostics.Stopwatch]::StartNew()
  try { $response=$request.GetResponse() } catch [Net.WebException] { $response=$_.Exception.Response; if(-not $response) { throw 'Actual HTTP transport failed; credentials/response withheld' } }
  try {
   $reader=[IO.StreamReader]::new($response.GetResponseStream(),[Text.Encoding]::UTF8)
   try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
   $json=$text | ConvertFrom-Json
   return [pscustomobject]@{status=[int]$response.StatusCode;body=$json;requestId=$response.Headers['X-Request-ID'];milliseconds=$timer.ElapsedMilliseconds}
  } finally { $timer.Stop(); $response.Dispose() }
 }
 function Reconcile($response,[string]$event,[string]$actor,[string]$tenant,[string]$object,[string]$result='SUCCESS') {
  Check ($response.requestId -match $uuid -and $response.requestId -ne '00000000-0000-4000-8000-000000000001') 'Server correlation ID missing or trusts the client'
  if($result -eq 'FAILED') { Check ($response.body.requestId -eq $response.requestId) 'Error/header correlation differs' }
  $rows=@(Sql ("SELECT CONCAT_WS('|',request_id,IFNULL(actor_id,'NULL'),IFNULL(tenant_id,'NULL'),event_type,IFNULL(object_id,'NULL'),result,reason_code) FROM audit_event WHERE request_id='"+$response.requestId+"'"))
  Check ($rows.Count -eq 1) 'Request must have exactly one committed event'
  $parts=$rows[0] -split '\|'
  $reason=switch($result) {'SUCCESS' {'OK'} 'NO_CHANGE' {'UNCHANGED'} default {'INTERNAL_ERROR'}}
  $expected=@($response.requestId,$actor,$tenant,$event,$object,$result,$reason) -join '|'
  Check (($parts -join '|') -ceq $expected) 'Committed audit scalar reconciliation failed'
  $eventNames.Add($event) | Out-Null
  $ledger.Add([pscustomobject]@{event=$event;status=$response.status;requestId=$response.requestId;result=$result;singleCommittedEvent=$true;actorMatched=$true;tenantMatched=$true;objectMatched=$true;reasonMatched=$true;serverGeneratedId=$true})
  $ledger | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $evidence 'safe-reconciliation.json') -Encoding utf8
  Write-Host "Reconciled $event $result HTTP=$($response.status) requestId=$($response.requestId)"
 }
 function Action([string]$event,[string]$method,[string]$path,[string]$role,$body=$null,[string]$object=$null,[string]$tenant=$fixture.tenantId,[string]$result='SUCCESS') {
  $response=Request $method $path $tokens[$role] $body
  Check ($response.status -eq 200 -and $response.body.code -eq 'OK') "Expected successful $event; actual HTTP=$($response.status)"
  if(-not $object) { $object=if($event -eq 'DOCTOR_CREATE') {$response.body.data.userId} else {$response.body.data.id} }
  Reconcile $response $event $fixture.accounts.$role.id $tenant $object $result
  return $response.body.data
 }
 function Business-Digest {
  $digests=@()
  foreach($table in @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order')) {
   $columns=@(Sql "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$table' ORDER BY ORDINAL_POSITION")
   $parts=@(); foreach($column in $columns) { Check ($column -match '^[a-z_]+$') 'Unexpected business identifier'; $parts+="IFNULL(HEX($column),'NULL')" }
   # Hash every row independently: GROUP_CONCAT's server limit must not hide changes.
   $rowHashes=@(Sql ("SELECT SHA2(CONCAT_WS(':',"+($parts -join ',')+"),256) FROM $table ORDER BY "+$columns[0]))
   $hasher=[Security.Cryptography.SHA256]::Create()
   try { $hash=[BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($rowHashes -join '|')))).Replace('-','') } finally { $hasher.Dispose() }
   $digests+=($table+'|'+$rowHashes.Count+'|'+$hash)
  }; return ($digests -join '|')
 }
 function Query-Check([string]$path,[string]$role,[int]$status) {
  $response=Request GET $path $(if($role) {$tokens[$role]} else {''})
  Check ($response.status -eq $status) "Actual authorization/query HTTP mismatch; expected=$status actual=$($response.status)"
  if($status -eq 200) {
   $page=$response.body.data
   Check ($page.timezone -eq 'Asia/Shanghai') 'Actual query timezone mismatch'
   foreach($item in $page.items) {
    $fields=@($item.PSObject.Properties.Name)
    Check (-not (@($fields | Where-Object { $_ -in @('actorId','objectId','diagnosis','complaint','advice','notes','passwordHash','accessToken') }).Count)) 'Private field leaked in audit DTO'
   }
  }; return $response
 }
 try {
  Assert-Database
  Check (@(Sql "SELECT COUNT(*) FROM tenant WHERE id='$($fixture.tenantId)' AND name='$($fixture.tenantName)'")[0] -eq '1') 'Dedicated hospital identity mismatch'
  foreach($role in @('platform','hospital','hospitalOther','doctor','patient','patientOther')) {
   $response=Request POST '/auth/login' '' @{username=$fixture.accounts.$role.username;password=$fixture.accounts.$role.password}
   Check ($response.status -eq 200 -and $response.body.data.accessToken) "Actual login failed for fixture role $role; HTTP=$($response.status)"
   $tokens[$role]=$response.body.data.accessToken
   $tenant=switch($role) {hospital {$fixture.tenantId} doctor {$fixture.tenantId} hospitalOther {$fixture.otherTenantId} default {'NULL'}}
   Reconcile $response LOGIN $fixture.accounts.$role.id $tenant $fixture.accounts.$role.id
  }
  if($FaultsOnly) {
   Check ($fixture.resources.departmentId -match $uuid -and $fixture.resources.doctorUserId -match $uuid) 'Dedicated clinical directory fixture required'
   # Completed appointments are still non-canceled: use a fresh virtual slot, not an already booked one.
   $faultDate=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'China Standard Time').AddDays(20+[int]@(Sql 'SELECT COUNT(*) FROM schedule_slot')[0]).ToString('yyyy-MM-dd')
   $faultSlot=Action SLOT_CREATE POST '/hospital/slots' hospital @{departmentId=$fixture.resources.departmentId;doctorId=$fixture.resources.doctorUserId;date=$faultDate;startTime='09:00:00';endTime='10:00:00';capacity=10;fee=39.10;status='PUBLISHED'}
   $faultEvents=New-Object 'Collections.Generic.HashSet[string]'
   function Clinical-Fault([string]$event,[string]$method,[string]$path,[string]$role,$body=$null) {
    $before=Business-Digest
    Sql ("CREATE TRIGGER "+$triggers[0]+" BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.actor_id='"+$fixture.accounts.$role.id+"' AND NEW.event_type='$event' AND NEW.result IN ('SUCCESS','NO_CHANGE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_CLINICAL_AUDIT_FAULT'; END IF; END") | Out-Null
    try {
     $response=Request $method $path $tokens[$role] $body
     Check ($response.status -eq 500) "Expected actual $event audit failure500; HTTP=$($response.status)"
     Reconcile $response $event $fixture.accounts.$role.id $fixture.tenantId NULL FAILED
     $after=Business-Digest
     Check ($after -ceq $before) "$event success-journal failure changed original business rows"
     # Production business EJB is over, and all relevant original business locks can be reacquired independently.
     Sql ("SET SESSION innodb_lock_wait_timeout=1; START TRANSACTION; SELECT id FROM tenant WHERE id='"+$fixture.tenantId+"' FOR UPDATE; SELECT id FROM schedule_slot WHERE id='"+$faultSlot.id+"' FOR UPDATE; SELECT id FROM appointment WHERE tenant_id='"+$fixture.tenantId+"' FOR UPDATE; SELECT id FROM payment_order WHERE tenant_id='"+$fixture.tenantId+"' FOR UPDATE; COMMIT") | Out-Null
     $faultEvents.Add($event) | Out-Null
     $ledger.Add([pscustomobject]@{check='clinical-success-insert-failure';event=$event;requestId=$response.requestId;status=500;allEightBusinessTablesUnchanged=$true;beforeDigest=$before;afterDigest=$after;singleIndependentFailedNullObject=$true;businessLocksReleased=$true})
     $ledger | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $evidence 'safe-reconciliation.json') -Encoding utf8
    } finally { Sql ("DROP TRIGGER IF EXISTS "+$triggers[0]) | Out-Null }
   }
   Clinical-Fault APPOINTMENT_BOOK POST '/patient/appointments' patient @{slotId=$faultSlot.id}
   $appointment=Action APPOINTMENT_BOOK POST '/patient/appointments' patient @{slotId=$faultSlot.id}
   Clinical-Fault APPOINTMENT_CANCEL POST ("/patient/appointments/"+$appointment.id+'/cancel') patient
   Clinical-Fault CONSULTATION_START POST ("/doctor/appointments/"+$appointment.id+'/start') doctor
   Action CONSULTATION_START POST ("/doctor/appointments/"+$appointment.id+'/start') doctor $null $appointment.id | Out-Null
   $completion=@{diagnosis='Fictional rollback course record';advice='Course fixture';complaint='Fictional';notes='Course only'}
   Clinical-Fault CONSULTATION_COMPLETE POST ("/doctor/appointments/"+$appointment.id+'/complete') doctor $completion
   Action CONSULTATION_COMPLETE POST ("/doctor/appointments/"+$appointment.id+'/complete') doctor $completion | Out-Null
   $orderResponse=Request GET ("/patient/appointments/"+$appointment.id+'/order') $tokens.patient
   Check ($orderResponse.status -eq 200) 'Targeted fault scenario order unavailable'
   Clinical-Fault PAYMENT_PAY POST ("/patient/orders/"+$orderResponse.body.data.id+'/pay') patient
   Check ($faultEvents.Count -eq 5) 'Not every clinical audit fault executed'
   return [pscustomobject]@{rollbackChecks=$faultEvents.Count;faultEventNames=@($faultEvents);failures=0;skipped=0;evidence=$evidence}
  }
  Action TENANT_STATUS_CHANGE PUT ("/platform/tenants/"+$fixture.otherTenantId+'/status') platform @{status='DISABLED';expectedStatus='ACTIVE'} $fixture.otherTenantId $fixture.otherTenantId | Out-Null
  Action TENANT_STATUS_CHANGE PUT ("/platform/tenants/"+$fixture.otherTenantId+'/status') platform @{status='ACTIVE';expectedStatus='DISABLED'} $fixture.otherTenantId $fixture.otherTenantId | Out-Null
  $department=Action DEPARTMENT_CREATE POST '/hospital/departments' hospital @{name=('Audit '+$run)}
  Action DEPARTMENT_UPDATE PUT ("/hospital/departments/"+$department.id) hospital @{name=('Audit updated '+$run);status='ACTIVE'} $department.id | Out-Null
  $doctor=Action DOCTOR_CREATE POST '/hospital/doctors' hospital @{username=('qa_created_'+$run);displayName='Acceptance doctor';departmentId=$department.id}
  Action DOCTOR_UPDATE PUT ("/hospital/doctors/"+$doctor.userId) hospital @{displayName='Acceptance doctor updated';departmentId=$department.id;status='ACTIVE'} $doctor.userId | Out-Null
  $date=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'China Standard Time').AddDays(5).ToString('yyyy-MM-dd')
  $slotBody=@{departmentId=$fixture.resources.departmentId;doctorId=$fixture.resources.doctorUserId;date=$date;startTime='09:00:00';endTime='10:00:00';capacity=10;fee=39.10;status='PUBLISHED'}
  # Each run uses a distinct future date when the fixed slot time is already occupied.
  $existing=@(Sql "SELECT COUNT(*) FROM schedule_slot WHERE doctor_id='$($fixture.resources.doctorUserId)' AND schedule_date='$date' AND start_time='09:00:00'")[0]
  if($existing -ne '0') { $date=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'China Standard Time').AddDays(5+[int]@(Sql 'SELECT COUNT(*) FROM schedule_slot')[0]).ToString('yyyy-MM-dd'); $slotBody.date=$date }
  $slot=Action SLOT_CREATE POST '/hospital/slots' hospital $slotBody
  $slotBody.capacity=9
  Action SLOT_UPDATE PUT ("/hospital/slots/"+$slot.id) hospital $slotBody $slot.id | Out-Null
  $canceled=Action APPOINTMENT_BOOK POST '/patient/appointments' patient @{slotId=$slot.id}
  Action APPOINTMENT_CANCEL POST ("/patient/appointments/"+$canceled.id+'/cancel') patient $null $canceled.id | Out-Null
  $appointment=Action APPOINTMENT_BOOK POST '/patient/appointments' patient @{slotId=$slot.id}
  Action CONSULTATION_START POST ("/doctor/appointments/"+$appointment.id+'/start') doctor $null $appointment.id | Out-Null
  $record=Action CONSULTATION_COMPLETE POST ("/doctor/appointments/"+$appointment.id+'/complete') doctor @{diagnosis='Fictional course record';advice='Course fixture';complaint='Fictional';notes='Course only'}
  $orderResponse=Request GET ("/patient/appointments/"+$appointment.id+'/order') $tokens.patient
  Check ($orderResponse.status -eq 200) 'Generated order unavailable'
  $order=$orderResponse.body.data
  Action PAYMENT_PAY POST ("/patient/orders/"+$order.id+'/pay') patient $null $order.id | Out-Null
  $paid=@(Sql "SELECT DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i:%s') FROM payment_order WHERE id='$($order.id)'")[0]
  Action PAYMENT_PAY POST ("/patient/orders/"+$order.id+'/pay') patient $null $order.id $fixture.tenantId NO_CHANGE | Out-Null
  Check (@(Sql "SELECT DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i:%s') FROM payment_order WHERE id='$($order.id)'")[0] -ceq $paid) 'Repeated payment changed committed first time'
  $beforeQueries=@(Sql 'SELECT COUNT(*) FROM audit_event')[0]
  $default=Query-Check '/hospital/audit-events' hospital 200
  Check ($default.body.data.page -eq 1 -and $default.body.data.size -eq 20) 'Actual default paging mismatch'
  $today=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'China Standard Time')
  Check ($default.body.data.from -eq $today.AddDays(-29).ToString('yyyy-MM-dd') -and $default.body.data.to -eq $today.ToString('yyyy-MM-dd')) 'Actual Shanghai default range mismatch'
  $page=Query-Check '/hospital/audit-events?page=1&size=1' hospital 200
  Check ($page.body.data.total -gt 1 -and @($page.body.data.items).Count -eq 1) 'Actual audit pagination absent'
  $all=Query-Check '/platform/audit-events' platform 200
  $selected=Query-Check ("/platform/audit-events?tenantId="+$fixture.tenantId+'&eventType=PAYMENT_PAY&result=NO_CHANGE') platform 200
  Check ($selected.body.data.total -gt 0) 'Combined actual filter empty'
  foreach($item in $selected.body.data.items) { Check ($item.tenantId -eq $fixture.tenantId -and $item.eventType -eq 'PAYMENT_PAY' -and $item.result -eq 'NO_CHANGE') 'Combined filter/scope mismatch' }
  $empty=Query-Check ("/platform/audit-events?tenantId="+$fixture.emptyTenantId) platform 200
  Check ($empty.body.data.total -eq 0 -and @($empty.body.data.items).Count -eq 0) 'Dedicated empty audit state is not empty'
  Query-Check '/hospital/audit-events?page=wrong' patient 403 | Out-Null
  Query-Check '/hospital/audit-events?from=wrong&page=wrong' '' 401 | Out-Null
  foreach($path in @('/hospital/audit-events?page=1&page=2','/hospital/audit-events?page=2147483647&size=2','/hospital/audit-events?tenantId=untrusted','/platform/audit-events?size=101')) { Query-Check $path $(if($path -like '/platform/*') {'platform'} else {'hospital'}) 400 | Out-Null }
  $other=Query-Check '/hospital/audit-events' hospitalOther 200
  foreach($item in $other.body.data.items) { Check ($item.tenantId -eq $fixture.otherTenantId) 'Cross-hospital event leaked' }
  Check (@(Sql 'SELECT COUNT(*) FROM audit_event')[0] -eq $beforeQueries) 'Audit reads generated new events'
  Action TENANT_STATUS_CHANGE PUT ("/platform/tenants/"+$fixture.tenantId+'/status') platform @{status='DISABLED';expectedStatus='ACTIVE'} $fixture.tenantId | Out-Null
  $history=Query-Check '/hospital/audit-events' hospital 200
  Check ($history.body.data.total -gt 0) 'Disabled hospital audit history unavailable'
  Action TENANT_STATUS_CHANGE PUT ("/platform/tenants/"+$fixture.tenantId+'/status') platform @{status='ACTIVE';expectedStatus='DISABLED'} $fixture.tenantId | Out-Null
  # Actual production AuditJournal failure through the production REST/EJB proxies.
  $before=Business-Digest
  Sql ("CREATE TRIGGER "+$triggers[0]+" BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.actor_id='"+$fixture.accounts.hospital.id+"' AND NEW.event_type='DEPARTMENT_UPDATE' AND NEW.result IN ('SUCCESS','NO_CHANGE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_AUDIT_SUCCESS_FAULT'; END IF; END") | Out-Null
  $failure=Request PUT ("/hospital/departments/"+$department.id) $tokens.hospital @{name=('Must rollback '+$run);status='ACTIVE'}
  Check ($failure.status -eq 500) 'Actual journal failure did not return 500'
  Reconcile $failure DEPARTMENT_UPDATE $fixture.accounts.hospital.id $fixture.tenantId NULL FAILED
  Check ((Business-Digest) -ceq $before) 'Audit insert failure changed an original business table'
  Sql ("SET SESSION innodb_lock_wait_timeout=1; START TRANSACTION; SELECT id FROM tenant WHERE id='"+$fixture.tenantId+"' FOR UPDATE; SELECT id FROM department WHERE id='"+$department.id+"' FOR UPDATE; COMMIT") | Out-Null
  $ledger.Add([pscustomobject]@{check='business-EJB-ended-locks-released';requestId=$failure.requestId;independentFailed=$true;originalEightTablesUnchanged=$true})
  Sql ("DROP TRIGGER "+$triggers[0]) | Out-Null
  $before=Business-Digest
  Sql ("CREATE TRIGGER "+$triggers[1]+" BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.actor_id='"+$fixture.accounts.patient.id+"' AND NEW.event_type='LOGIN' AND NEW.result='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_AUDIT_LOGIN_FAULT'; END IF; END") | Out-Null
  $denied=Request POST '/auth/login' '' @{username=$fixture.accounts.patient.username;password=$fixture.accounts.patient.password}
  Check ($denied.status -eq 500 -and -not $denied.body.data.accessToken) 'Failed login journal returned a token'
  Reconcile $denied LOGIN NULL NULL NULL FAILED
  Check ((Business-Digest) -ceq $before) 'Failed success-login journal mutated business rows'
  Sql ("DROP TRIGGER "+$triggers[1]) | Out-Null
  Sql ("CREATE TRIGGER "+$triggers[2]+" BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.event_type='LOGIN' AND NEW.result='FAILED' AND NEW.actor_id IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_AUDIT_FAILED_FAULT'; END IF; END") | Out-Null
  $wrong=Request POST '/auth/login' '' @{username=$fixture.accounts.patient.username;password=[guid]::NewGuid().ToString('N')}
  Check ($wrong.status -eq 401 -and $wrong.body.code -eq 'UNAUTHORIZED' -and -not $wrong.body.data.accessToken -and $wrong.body.requestId -eq $wrong.requestId) 'Failed-journal failure replaced original 401 or exposed token'
  Check (@(Sql ("SELECT COUNT(*) FROM audit_event WHERE request_id='"+$wrong.requestId+"'"))[0] -eq '0') 'Failed-only trigger unexpectedly persisted'
  $ledger.Add([pscustomobject]@{check='failed-journal-keeps-original-401';requestId=$wrong.requestId;status=$wrong.status;tokenDenied=$true})
  Sql ("DROP TRIGGER "+$triggers[2]) | Out-Null
  $warningLog=$fixture.diagnosticsLogPath
  Check ($warningLog -and (Test-Path -LiteralPath $warningLog)) 'Actual WildFly warning log required'
  $warning=@(Select-String -LiteralPath $warningLog -Pattern ([regex]::Escape('requestId='+$wrong.requestId+' type=AUDIT_WRITE_FAILED')))
  Check ($warning.Count -gt 0) 'Actual safe failed-journal warning absent'
  $ledger.Add([pscustomobject]@{check='safe-runtime-warning';requestId=$wrong.requestId;type='AUDIT_WRITE_FAILED'})
  $fixture | Add-Member -NotePropertyName lastScenario -NotePropertyValue @{departmentId=$department.id;doctorId=$doctor.userId;slotId=$slot.id;appointmentId=$appointment.id;canceledAppointmentId=$canceled.id;recordId=$record.id;orderId=$order.id;run=$run} -Force
  $fixture | ConvertTo-Json -Depth 8 | Out-File -LiteralPath $FixturePath -Encoding utf8
  $ledger | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $evidence 'safe-reconciliation.json') -Encoding utf8
  Check ($eventNames.Count -eq 13) 'Not every mandatory audit event executed'
  return [pscustomobject]@{events=$eventNames.Count;eventNames=@($eventNames);failures=0;skipped=0;evidence=$evidence}
 } finally {
  try {
   $ledger | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $evidence 'safe-reconciliation.json') -Encoding utf8
   Assert-Database
   foreach($trigger in $triggers) { Sql ("DROP TRIGGER IF EXISTS "+$trigger) | Out-Null }
   $ledger | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $evidence 'safe-reconciliation.json') -Encoding utf8
  } finally { $env:MYSQL_PWD=$priorPassword }
 }
}
$evidence=Join-Path $repo ('.superpowers/sdd/2026-10-04-medical-cloud-course-audit/task7/db-'+[guid]::NewGuid().ToString('N'))
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('course-audit-acceptance-'+[guid]::NewGuid().ToString('N'))
$data=Join-Path $scratch 'data'
$server=[IO.Path]::GetFullPath((Resolve-Path -LiteralPath $MySqlServer).Path)
$mysql=(Resolve-Path -LiteralPath $MySqlCli).Path
$maven=(Resolve-Path -LiteralPath $MavenCli).Path
$prior=@{}
foreach($name in @('COURSE_MYSQL_TEST_URL','COURSE_MYSQL_TEST_USER','COURSE_MYSQL_TEST_PASSWORD','MYSQL_PWD')) { $prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
function Get-OwnedProcesses {
 @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" -ErrorAction Stop | Where-Object { $_.CommandLine -match ('(?:^|\s)--datadir="?' +[regex]::Escape($data)+'"?(?:\s|$)') })
}
function Invoke-OwnedSql([string]$sql) {
 $output=@(& $mysql --no-defaults --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$port" --user=root --batch --skip-column-names "--execute=$sql" 2>&1)
 if($LASTEXITCODE -ne 0) { throw 'Disposable SQL failed; native text withheld' }; return $output
}
function Assert-OwnedIdentity {
 $owned=@(Get-OwnedProcesses)
 if($owned.Count -lt 1 -or $owned.Count -gt 2) { throw 'Disposable process count mismatch; retained data' }
 foreach($process in $owned) {
  if([IO.Path]::GetFullPath($process.ExecutablePath) -ine $server -or $process.CommandLine -notmatch ('(?:^|\s)--port='+$port+'(?:\s|$)') -or ($process.ProcessId -ne $launcher.Id -and $process.ParentProcessId -ne $launcher.Id)) { throw 'Binary/port/ancestry mismatch; shutdown refused' }
 }
 $identity=@(Invoke-OwnedSql 'SELECT @@server_uuid,@@port,@@datadir')[0] -split [char]9
 if($identity.Count -ne 3 -or $identity[0] -ne $serverUuid -or [int]$identity[1] -ne $port -or [IO.Path]::GetFullPath($identity[2]).TrimEnd('\','/') -ine [IO.Path]::GetFullPath($data).TrimEnd('\','/')) { throw 'SQL UUID/port/datadir mismatch; shutdown refused' }
}
function Invoke-RealTests([string]$classes,[string]$prefix,[hashtable]$counts,[hashtable]$names) {
 Assert-OwnedIdentity
 $database=$prefix+[guid]::NewGuid().ToString('N')
 Invoke-OwnedSql "CREATE DATABASE $database CHARACTER SET utf8mb4" | Out-Null
 $env:COURSE_MYSQL_TEST_URL="jdbc:mysql://127.0.0.1:$port/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
 $env:COURSE_MYSQL_TEST_USER='root'; $env:COURSE_MYSQL_TEST_PASSWORD=''
 $reports=Join-Path $repo 'course/paas/target/surefire-reports'
 foreach($class in $counts.Keys) {
  $old=Join-Path $reports "TEST-com.medcloud.course.paas.$class.xml"
  if(Test-Path -LiteralPath $old) { Move-Item -LiteralPath $old -Destination (Join-Path $evidence ("prior-$class.xml")) }
 }
 $log=Join-Path $evidence (($classes -replace ',','-')+'.log')
 & $maven -f (Join-Path $repo 'course/pom.xml') "-Dtest=$classes" '-Dsurefire.failIfNoSpecifiedTests=false' test *> $log
 $commandExit=$LASTEXITCODE
 foreach($class in $counts.Keys) {
  $path=Join-Path $reports "TEST-com.medcloud.course.paas.$class.xml"
  if(-not (Test-Path -LiteralPath $path)) { throw "Missing fresh report for $class; Maven exit=$commandExit; see $log" }
  [xml]$result=Get-Content -LiteralPath $path -Raw
  Copy-Item -LiteralPath $path -Destination $evidence
  $suite=$result.testsuite
  Write-Host "$class tests=$($suite.tests) failures=$($suite.failures) errors=$($suite.errors) skipped=$($suite.skipped); Maven exit=$commandExit; evidence=$log"
  if([int]$suite.tests -lt $counts[$class] -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) { throw "Real database acceptance failed for $class; counts retained" }
  foreach($name in $names[$class]) { if(@($suite.testcase | Where-Object name -eq $name).Count -ne 1) { throw "Required executed case absent: $name" } }
 }
 if($commandExit -ne 0) { throw "Real database Maven failed; see $log" }
}
try {
 New-Item -ItemType Directory -Path $data,$evidence | Out-Null
 $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
 try { $listener.Start(); $port=$listener.LocalEndpoint.Port } finally { $listener.Stop() }
 if($port -in @(3318,8180,8188,10090)) { throw 'Refused protected runtime port' }
 $base=Split-Path (Split-Path $server -Parent) -Parent
 $env:MYSQL_PWD=''
 & $server --no-defaults --initialize-insecure "--basedir=$base" "--datadir=$data" *> (Join-Path $evidence 'initialize.log')
 if($LASTEXITCODE -ne 0) { throw 'Disposable MySQL initialization failed' }
 $launcher=Start-Process -FilePath $server -ArgumentList @('--no-defaults',('--basedir="'+$base+'"'),('--datadir="'+$data+'"'),'--bind-address=127.0.0.1',"--port=$port",'--mysqlx=0','--console') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $evidence 'server.err')
 $ready=$false
 for($attempt=0;$attempt -lt 60;$attempt++) {
  & $mysql --no-defaults --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$port" --user=root --execute='SELECT 1' 2>$null | Out-Null
  if($LASTEXITCODE -eq 0) { $ready=$true; break }; Start-Sleep -Milliseconds 500
 }
 if(-not $ready) { throw 'Disposable MySQL did not start; retained datadir' }
 $serverUuid=@(Invoke-OwnedSql 'SELECT @@server_uuid')[0]
 Assert-OwnedIdentity
 [pscustomobject]@{binary=$server;datadir=$data;port=$port;uuid=$serverUuid;launcherPid=$launcher.Id;ownedPids=@((Get-OwnedProcesses).ProcessId)} | ConvertTo-Json | Out-File -LiteralPath (Join-Path $evidence 'identity.json') -Encoding utf8
 Invoke-RealTests 'MySqlAuditTransactionTest' 'course_audit_test_' @{MySqlAuditTransactionTest=8} @{MySqlAuditTransactionTest=@('committedSuccessVisible','rolledBackMutationHasNoSuccess','forcedSuccessInsertFailureRollsBackBusiness','duplicateRequestEventIsRejected','failureStoresNoUntrustedObject','shanghaiMidnightRangeCorrect','repeatPaymentKeepsTimestampAndNoChange','countAndRowsShareRepeatableReadSnapshot')}
 Invoke-RealTests 'MySqlTenantLifecycleTest,MySqlStatisticsTest' 'course_platform_test_' @{MySqlTenantLifecycleTest=24;MySqlStatisticsTest=4} @{MySqlTenantLifecycleTest=@();MySqlStatisticsTest=@('actualDateDatetimeCohortsEventsMoneyAndZeroHospitalMatchHandCheckedFixture','realNullTimesStayNullAndRemainSeparateAllTimeSupplement','shanghaiMidnightBoundsUseMysqlWallDateEvenWithForeignJvmTimezone','reportUsesOneRepeatableReadSnapshot')}
 Invoke-RealTests 'MySqlSlotSnapshotTest' 'course_slot_test_' @{MySqlSlotSnapshotTest=1} @{MySqlSlotSnapshotTest=@('bookingCommittedAfterAdminSnapshotPreventsSlotWithdrawal')}
} finally {
 try {
  $resolved=[IO.Path]::GetFullPath($scratch); $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
  if(-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^course-audit-acceptance-[a-f0-9]{32}$') { throw 'Refused unresolved cleanup path' }
  if(@(Get-OwnedProcesses).Count) {
   if(-not $serverUuid) { throw 'No verified UUID; shutdown refused; retained data' }
   Assert-OwnedIdentity
   Invoke-OwnedSql 'SHUTDOWN' | Out-Null
   for($attempt=0;$attempt -lt 40;$attempt++) { if(@(Get-OwnedProcesses).Count -eq 0) { break }; Start-Sleep -Milliseconds 250 }
  }
  if(@(Get-OwnedProcesses).Count -ne 0) { throw 'Owned MySQL children remain; retained data' }
  Write-Host "Exact disposable shutdown verified; zero owned children; retained datadir: $data"
  if(Test-Path -LiteralPath $evidence) { 'Owned MySQL children: 0; datadir retained' | Out-File -LiteralPath (Join-Path $evidence 'shutdown.txt') -Encoding utf8 }
 } finally { foreach($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') } }
}
