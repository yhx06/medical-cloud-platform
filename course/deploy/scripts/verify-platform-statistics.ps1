param(
    [Parameter(Mandatory=$true)][string]$PaasBaseUrl,
    [Parameter(Mandatory=$true)][string]$FixturePath
)
$ErrorActionPreference='Stop'
$base=$PaasBaseUrl.TrimEnd('/')
if ($FixturePath -notmatch '^[A-Za-z]:[\\/]' -or -not [IO.Path]::IsPathRooted($FixturePath) -or
    -not (Test-Path -LiteralPath $FixturePath -PathType Leaf)) { throw 'Fixture must be an explicit existing local absolute JSON file' }
$fixtureFile = Get-Item -LiteralPath $FixturePath
if ($fixtureFile.PSProvider.Name -ne 'FileSystem' -or $fixtureFile.FullName -notmatch '^[A-Za-z]:[\\/]') {
    throw 'Fixture must be an explicit existing local absolute JSON file'
}
$fixture=Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
$uuid='^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$'
if ($fixture.tenantId -notmatch $uuid -or $fixture.tenantId -in @('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222') -or
    $fixture.tenantName -notmatch '^course_stats_acceptance_[a-f0-9]{32}$') { throw 'Refused non-dedicated acceptance hospital' }
foreach($key in @('slotId','departmentId','doctorUserId','bookedAppointmentId','inProgressAppointmentId','completedAppointmentId','unpaidOrderId','paidOrderId')) {
    if ($fixture.resources.$key -notmatch $uuid) { throw "Invalid fixture resource $key" }
}
foreach($role in @('platform','hospital','doctor','patient')) {
    if (-not $fixture.accounts.$role.username -or -not $fixture.accounts.$role.password) { throw "Missing fixture account $role" }
}
function Api([string]$method,[string]$path,[string]$token,$body=$null) {
    $args=@{Uri="$base$path";Method=$method;TimeoutSec=20;Headers=@{}}
    if($token) { $args.Headers.Authorization="Bearer $token" }
    if($method -in @('POST','PUT')) { $args.ContentType='application/json; charset=utf-8'; $args.Body=[Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress)) }
    try { $result=Invoke-RestMethod @args } catch { throw "HTTP request failed ($method $path): $([int]$_.Exception.Response.StatusCode)" }
    if($result.code -ne 'OK') { throw "Unexpected response code at $path" }; return $result.data
}
function Reject([int]$status,[string]$method,[string]$path,[string]$token,$body=$null) {
    try { Api $method $path $token $body | Out-Null }
    catch { if($_.Exception.Message -eq "HTTP request failed ($method $path): $status") { return }; throw }
    throw "Expected HTTP $status at $path"
}
function Login([string]$role) { (Api POST '/auth/login' '' @{username=$fixture.accounts.$role.username;password=$fixture.accounts.$role.password}).accessToken }
function Check($condition,[string]$message) { if(-not $condition) { throw $message } }
function Target {
    $found=$null; $page=1
    do {
        $list=Api GET "/platform/tenants?page=$page&size=100" $tokens.platform
        foreach($tenant in $list.items) { if($tenant.id -eq $fixture.tenantId) { $found=$tenant } }
        $page++
    } while (($page-1)*100 -lt $list.total)
    Check ($found -and $found.name -ceq $fixture.tenantName -and $found.status -in @('ACTIVE','DISABLED')) 'Dedicated hospital ID/name/status verification failed'
    return $found
}
function Change([string]$status) { $current=Target; Api PUT "/platform/tenants/$($fixture.tenantId)/status" $tokens.platform @{status=$status;expectedStatus=$current.status} | Out-Null }
function Snapshot {
    $value=[ordered]@{}
    foreach($entry in @(@('appointments','/patient/appointments','patient'),@('orders','/hospital/orders','hospital'),@('departments','/hospital/departments','hospital'),@('doctors','/hospital/doctors','hospital'),@('slots','/hospital/slots','hospital'),@('records','/doctor/records','doctor'))) {
        $rows=@(Api GET $entry[1] $tokens.($entry[2]) | Sort-Object id,userId)
        # State/amount/time evidence; writable metadata legitimately changes on disable.
        $value[$entry[0]]=@($rows | Select-Object id,userId,status,amount,fee,paidAt,completedAt,startedAt,name,displayName,departmentId,date,startTime,endTime,capacity)
    }
    return ($value | ConvertTo-Json -Depth 8 -Compress)
}
function Metrics($report) {
    $names=@('appointmentTotal','appointmentBooked','appointmentInProgress','appointmentCanceled','appointmentCompleted','consultationStarted','consultationCompleted','orderGenerated','paidOrderCount','orderAmount','paidRevenue')
    Check ((($report.metrics.PSObject.Properties.Name | Sort-Object) -join ',') -ceq (($names | Sort-Object) -join ',')) 'Statistics metrics whitelist mismatch'
    foreach($name in $names) {
        [decimal]$days=0; [decimal]$hospitals=0
        foreach($day in $report.daily) { $days += [decimal]$day.metrics.$name }
        foreach($hospital in $report.hospitals) { $hospitals += [decimal]$hospital.metrics.$name }
        Check ($days -eq [decimal]$report.metrics.$name -and $hospitals -eq $days) "Inconsistent statistic $name"
    }
    Check ($report.metrics.appointmentTotal -eq ($report.metrics.appointmentBooked+$report.metrics.appointmentInProgress+$report.metrics.appointmentCanceled+$report.metrics.appointmentCompleted)) 'Cohort counts mismatch'
    $json=$report | ConvertTo-Json -Depth 12 -Compress
    Check ($json -notmatch '"(?:patientId|patientUsername|doctorId|appointmentId|diagnosis|complaint|advice|notes|passwordHash|accessToken)"') 'Private field in aggregate response'
}
$tokens=@{}; foreach($role in @('platform','hospital','doctor','patient')) { $tokens[$role]=Login $role }
$original=(Target).status
$r=$fixture.resources
try {
    Change ACTIVE
    $context=Api GET '/hospital/context' $tokens.hospital
    Check ($context.id -eq $fixture.tenantId -and $context.businessWritable) 'Hospital account affiliation mismatch'
    Check ((Api GET '/doctor/context' $tokens.doctor).id -eq $fixture.tenantId) 'Doctor account affiliation mismatch'
    $appointments=@(Api GET '/patient/appointments' $tokens.patient)
    foreach($id in @($r.bookedAppointmentId,$r.inProgressAppointmentId,$r.completedAppointmentId)) {
        Check (@($appointments | Where-Object { $_.id -eq $id -and $_.tenantId -eq $fixture.tenantId }).Count -eq 1) 'Fixture appointment ownership mismatch'
    }
    $orders=@(Api GET '/hospital/orders' $tokens.hospital)
    foreach($id in @($r.unpaidOrderId,$r.paidOrderId)) {
        $owned=@($orders | Where-Object id -eq $id)
        Check ($owned.Count -eq 1) 'Fixture order hospital ownership mismatch'
        Check ((Api GET "/patient/appointments/$($owned[0].appointmentId)/order" $tokens.patient).id -eq $id) 'Fixture order patient ownership mismatch'
    }
    $slot=@(Api GET '/hospital/slots' $tokens.hospital | Where-Object id -eq $r.slotId)[0]
    $department=@(Api GET '/hospital/departments' $tokens.hospital | Where-Object id -eq $r.departmentId)[0]
    $doctor=@(Api GET '/hospital/doctors' $tokens.hospital | Where-Object userId -eq $r.doctorUserId)[0]
    Check ($slot -and $department -and $doctor -and $slot.doctorId -eq $r.doctorUserId -and $slot.departmentId -eq $r.departmentId) 'Fixture resource ownership mismatch'
    $today=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow,'China Standard Time').ToString('yyyy-MM-dd')
    $statsPath="?from=$today&to=$today"
    $beforeReport=Api GET "/hospital/statistics$statsPath" $tokens.hospital; Metrics $beforeReport
    Reject 403 GET '/platform/statistics' $tokens.patient
    Reject 403 GET '/hospital/orders' $tokens.platform
    Reject 400 GET "/hospital/statistics${statsPath}&tenantId=$($fixture.tenantId)" $tokens.hospital
    Reject 400 PUT "/platform/tenants/$($fixture.tenantId)/status" $tokens.platform @{status='ACTIVE';expectedStatus='ACTIVE';name='forbidden'}
    $before=Snapshot
    Change DISABLED
    # Old tokens and new logins both retain original historical authority.
    foreach($role in @('hospital','doctor','patient')) { $fresh=Login $role; Check ([bool]$fresh) 'Disabled hospital new login failed' }
    Check (-not (Api GET '/hospital/context' $tokens.hospital).businessWritable) 'Disabled context remains writable'
    Check (@(Api GET '/catalog/hospitals' '' | Where-Object id -eq $fixture.tenantId).Count -eq 0) 'Disabled hospital remains in public directory'
    Api GET "/patient/appointments/$($r.completedAppointmentId)/record" $tokens.patient | Out-Null
    Api GET "/patient/appointments/$($r.completedAppointmentId)/order" $tokens.patient | Out-Null
    $slotBody=@{departmentId=$r.departmentId;doctorId=$r.doctorUserId;date=$slot.date;startTime=$slot.startTime;endTime=$slot.endTime;capacity=$slot.capacity;fee=$slot.fee;status=$slot.status}
    $doctorBody=@{displayName=$doctor.displayName;departmentId=$doctor.departmentId;status=$doctor.status}
    foreach($write in @(
        @('POST','/patient/appointments','patient',@{slotId=$r.slotId}),
        @('POST',"/patient/appointments/$($r.bookedAppointmentId)/cancel",'patient',@{}),
        @('POST',"/doctor/appointments/$($r.bookedAppointmentId)/start",'doctor',@{}),
        @('POST',"/doctor/appointments/$($r.inProgressAppointmentId)/complete",'doctor',@{diagnosis='fictional';advice='course'}),
        @('POST',"/patient/orders/$($r.unpaidOrderId)/pay",'patient',@{}),
        @('POST',"/patient/orders/$($r.paidOrderId)/pay",'patient',@{}),
        @('POST','/hospital/departments','hospital',@{name='Blocked fixture department'}),
        @('PUT',"/hospital/departments/$($r.departmentId)",'hospital',@{name=$department.name;status=$department.status}),
        @('POST','/hospital/doctors','hospital',@{username=('blocked_'+[guid]::NewGuid().ToString('N'));displayName='Blocked';departmentId=$r.departmentId}),
        @('PUT',"/hospital/doctors/$($r.doctorUserId)",'hospital',$doctorBody),
        @('POST','/hospital/slots','hospital',$slotBody),
        @('PUT',"/hospital/slots/$($r.slotId)",'hospital',$slotBody)
    )) { Reject 409 $write[0] $write[1] $tokens.($write[2]) $write[3] }
    Check ((Snapshot) -ceq $before) 'Frozen write changed business rows/state/time/money'
    $disabledReport=Api GET "/hospital/statistics$statsPath" $tokens.hospital; Metrics $disabledReport
    Check (($disabledReport.metrics | ConvertTo-Json -Compress) -ceq ($beforeReport.metrics | ConvertTo-Json -Compress)) 'Freeze changed historical aggregates'
    Metrics (Api GET "/platform/statistics$statsPath" $tokens.platform)
    Change ACTIVE
    Check ((Api GET '/hospital/context' $tokens.hospital).businessWritable) 'Reenabled context did not recover'
    Api PUT "/hospital/departments/$($r.departmentId)" $tokens.hospital @{name=$department.name;status=$department.status} | Out-Null
    $paid=Api POST "/patient/orders/$($r.unpaidOrderId)/pay" $tokens.patient @{}
    Check ($paid.status -eq 'PAID') 'Reenabled unexpired payment failed'
    $afterReport=Api GET "/hospital/statistics$statsPath" $tokens.hospital; Metrics $afterReport
    [pscustomobject]@{Result='PASS';FrozenWrites=12;HistoricalRead='PASS';NewLogin='PASS';Reenable='PASS';Metrics=$afterReport.metrics} | ConvertTo-Json -Depth 4
} finally {
    Change $original
    Check ((Target).status -eq $original) 'Failed to restore original fixture hospital status'
}
