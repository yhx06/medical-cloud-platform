param(
    [string]$ApiBase = 'http://127.0.0.1:8080/paas/api',
    [string]$FixturePath = '',
    [string]$DemoPassword = 'Demo@123456'
)
$ErrorActionPreference = 'Stop'
$base = $ApiBase.TrimEnd('/')
$tenant = '11111111-1111-1111-1111-111111111111'
$department = '11111111-1111-1111-1111-111111111112'
$doctorId = '20000000-0000-0000-0000-000000000001'
if (-not $FixturePath) { $FixturePath = Join-Path ([IO.Path]::GetTempPath()) ("course-p0-" + [guid]::NewGuid() + '.json') }
$FixturePath = [IO.Path]::GetFullPath($FixturePath)
function Save-Fixture {
    # Atomic replacement retains the last checkpoint after an interrupted write.
    $temporary = "$FixturePath.tmp"
    [IO.File]::WriteAllText($temporary, ($script:fixture | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $FixturePath) { [IO.File]::Replace($temporary, $FixturePath, [NullString]::Value) }
    else { [IO.File]::Move($temporary, $FixturePath) }
}
if (Test-Path -LiteralPath $FixturePath) {
    try {
        $fixture = Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $parsed = [guid]::Empty
        $fixtureDate = [datetime]::MinValue
        if ($fixture.version -ne 1 -or -not [guid]::TryParse($fixture.runId, [ref]$parsed) -or
            $fixture.apiBase -cne $base -or $fixture.username -cne ('p0_' + $parsed.ToString('N')) -or
            $fixture.password -notmatch '^P0@[a-f0-9]{32}$' -or $fixture.date -isnot [string] -or
            -not [datetime]::TryParseExact($fixture.date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::None, [ref]$fixtureDate) -or
            $fixture.startTime -isnot [string] -or $fixture.startTime -notmatch '^09:[0-5]\d:[0-5]\d$' -or
            $fixture.slotPrepared -isnot [bool] -or $fixture.cancelDone -isnot [bool]) { throw 'bad fields' }
        foreach ($field in @('slotId','canceledAppointmentId','appointmentId')) {
            if (-not $fixture.PSObject.Properties[$field] -or
                ($fixture.$field -and -not [guid]::TryParse($fixture.$field, [ref]$parsed))) { throw 'bad ID' }
        }
        if ($fixture.cancelDone -and -not $fixture.canceledAppointmentId) { throw 'missing cancellation ID' }
    } catch { throw "Invalid fixture: $FixturePath. No HTTP request was sent." }
} else {
    $runId = [guid]::NewGuid()
    $today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow, 'China Standard Time').Date
    $fixture = [pscustomobject]@{version=1;runId=$runId.ToString();apiBase=$base;
        username=('p0_' + $runId.ToString('N'));password=('P0@' + [guid]::NewGuid().ToString('N'));
        date=$today.AddDays((Get-Random -Minimum 1 -Maximum 3651)).ToString('yyyy-MM-dd');
        startTime=('09:{0:00}:{1:00}' -f (Get-Random -Maximum 60),(Get-Random -Maximum 60));
        slotPrepared=$false;slotId='';canceledAppointmentId='';cancelDone=$false;appointmentId=''}
    Save-Fixture
}
Write-Host "Fictional P0 fixture (contains local demo credentials): $FixturePath"
function Invoke-CourseApi {
    param([string]$Method, [string]$Path, [string]$Token, [object]$Body)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{Uri="$base$Path";Method=$Method;Headers=$headers;TimeoutSec=20}
    if ($Method -in @('POST','PUT')) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $json = if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Compress }
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    $response = Invoke-RestMethod @arguments
    if ($response.code -ne 'OK') { throw "API returned $($response.code) at $Path" }
    return $response.data
}
function Assert-CourseStatus {
    param([int]$Expected, [string]$Method, [string]$Path, [string]$Token, [object]$Body)
    try { Invoke-CourseApi $Method $Path $Token $Body | Out-Null }
    catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq $Expected) { return }
        throw
    }
    throw "Expected HTTP $Expected at $Path, but request succeeded"
}
function Login([string]$Username, [string]$Password) {
    (Invoke-CourseApi POST '/auth/login' '' @{username=$Username;password=$Password}).accessToken
}
Assert-CourseStatus 401 POST '/auth/login' '' @{username='disabled_user';password=$DemoPassword}
$hospital = Login 'hospital_a' $DemoPassword
$doctor = Login 'doctor_a' $DemoPassword
$otherDoctor = Login 'doctor_b' $DemoPassword
# UUID account credentials recover a lost registration response.
try { $patient = Login $fixture.username $fixture.password }
catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
    Invoke-CourseApi POST '/auth/register' '' @{username=$fixture.username;password=$fixture.password} | Out-Null
    $patient = Login $fixture.username $fixture.password
}
$slots = @(Invoke-CourseApi GET '/hospital/slots' $hospital $null)
if (-not $fixture.slotPrepared) {
    $collision = @($slots | Where-Object { $_.doctorId -eq $doctorId -and
        ([string]$_.date).StartsWith($fixture.date) -and $_.startTime -eq $fixture.startTime })
    if ($collision.Count) { throw 'Fixture slot collision; use a new fixture path. Existing slot was not changed.' }
    $fixture.slotPrepared = $true
    Save-Fixture
}
if (-not $fixture.slotId) {
    $candidates = @($slots | Where-Object { $_.doctorId -eq $doctorId -and
        ([string]$_.date).StartsWith($fixture.date) -and $_.startTime -eq $fixture.startTime })
    if ($candidates.Count -gt 1) { throw 'Ambiguous fixture slot; inspect manually' }
    if ($candidates.Count -eq 1) { $slot = $candidates[0] }
    else { $slot = Invoke-CourseApi POST '/hospital/slots' $hospital @{
        departmentId=$department;doctorId=$doctorId;date=$fixture.date;startTime=$fixture.startTime;
        endTime='10:00:00';capacity=1;fee=39;status='PUBLISHED'} }
    $fixture.slotId = $slot.id
    Save-Fixture
}
$slot = @(Invoke-CourseApi GET '/hospital/slots' $hospital $null | Where-Object id -eq $fixture.slotId)[0]
if (-not $slot -or $slot.doctorId -ne $doctorId -or $slot.departmentId -ne $department -or
    -not ([string]$slot.date).StartsWith($fixture.date) -or $slot.startTime -ne $fixture.startTime -or
    $slot.capacity -ne 1 -or $slot.fee -ne 39) { throw 'Fixture slot ownership/snapshot mismatch' }
$catalogPath = "/catalog/slots?tenantId=$tenant&departmentId=$department&doctorId=$doctorId&date=$($fixture.date)"
$appointments = @(Invoke-CourseApi GET '/patient/appointments' $patient $null | Where-Object slotId -eq $fixture.slotId)
if (-not $fixture.cancelDone) {
    $canceled = @($appointments | Where-Object status -eq 'CANCELED')
    if ($canceled.Count) { $fixture.canceledAppointmentId = $canceled[0].id }
    else {
        $booked = @($appointments | Where-Object status -eq 'BOOKED')
        if ($booked.Count) { $booking = $booked[0] }
        else {
            $available = @(Invoke-CourseApi GET $catalogPath '' $null | Where-Object id -eq $fixture.slotId)
            if ($available.Count -ne 1 -or $available[0].remaining -ne 1) { throw 'Filtered catalog did not expose the new slot' }
            $booking = Invoke-CourseApi POST '/patient/appointments' $patient @{slotId=$fixture.slotId}
        }
        $fixture.canceledAppointmentId = $booking.id
        Save-Fixture
        Invoke-CourseApi POST "/patient/appointments/$($booking.id)/cancel" $patient $null | Out-Null
    }
    $available = @(Invoke-CourseApi GET $catalogPath '' $null | Where-Object id -eq $fixture.slotId)
    if ($available.Count -ne 1 -or $available[0].remaining -ne 1) { throw 'Cancellation did not release capacity' }
    $fixture.cancelDone = $true
    Save-Fixture
}
$active = @(Invoke-CourseApi GET '/patient/appointments' $patient $null | Where-Object { $_.slotId -eq $fixture.slotId -and $_.status -ne 'CANCELED' })
if ($active.Count -gt 1) { throw 'More than one active fixture appointment' }
if ($active.Count) { $booking = $active[0] }
else { $booking = Invoke-CourseApi POST '/patient/appointments' $patient @{slotId=$fixture.slotId} }
$fixture.appointmentId = $booking.id
Save-Fixture
$appointmentId = $booking.id
if ($appointmentId -eq $fixture.canceledAppointmentId) { throw 'Rebooking reused the canceled appointment' }
Assert-CourseStatus 409 POST '/patient/appointments' $patient @{slotId=$fixture.slotId}
Assert-CourseStatus 409 POST "/patient/appointments/$($fixture.canceledAppointmentId)/cancel" $patient $null
$fullSlot = @(Invoke-CourseApi GET $catalogPath '' $null | Where-Object id -eq $fixture.slotId)
if ($fullSlot.Count) { throw 'A full slot remains in the available catalog' }
Assert-CourseStatus 403 POST "/doctor/appointments/$appointmentId/start" $otherDoctor $null
if ($booking.status -eq 'BOOKED') { Invoke-CourseApi POST "/doctor/appointments/$appointmentId/start" $doctor $null | Out-Null }
if ($booking.status -ne 'COMPLETED') {
    Invoke-CourseApi POST "/doctor/appointments/$appointmentId/complete" $doctor @{
        diagnosis='Fictional classroom diagnosis';advice='Fictional advice, not medical care';
        complaint='P0 demonstration only';notes=$fixture.runId} | Out-Null
}
Assert-CourseStatus 409 POST "/doctor/appointments/$appointmentId/complete" $doctor @{diagnosis='Do not overwrite';advice='Do not overwrite'}
$record = Invoke-CourseApi GET "/patient/appointments/$appointmentId/record" $patient $null
$order = Invoke-CourseApi GET "/patient/appointments/$appointmentId/order" $patient $null
if (-not $record.id -or $record.appointmentId -ne $appointmentId -or $record.notes -ne $fixture.runId -or
    -not $record.completedAt -or $order.amount -ne 39 -or $order.appointmentId -ne $appointmentId) {
    throw 'Record or appointment fee snapshot mismatch'
}
$doctorRecord = @(Invoke-CourseApi GET '/doctor/records' $doctor $null | Where-Object id -eq $record.id)
$otherDoctorRecord = @(Invoke-CourseApi GET '/doctor/records' $otherDoctor $null | Where-Object id -eq $record.id)
if ($doctorRecord.Count -ne 1 -or $otherDoctorRecord.Count) { throw 'Doctor record history/isolation failed' }
Assert-CourseStatus 403 GET '/hospital/orders?status=PAID' $patient $null
Assert-CourseStatus 403 POST "/patient/orders/$($order.id)/pay" $hospital $null
$paid = Invoke-CourseApi POST "/patient/orders/$($order.id)/pay" $patient $null
$paidAgain = Invoke-CourseApi POST "/patient/orders/$($order.id)/pay" $patient $null
if ($paid.status -ne 'PAID' -or $paidAgain.id -ne $order.id -or $paidAgain.status -ne 'PAID' -or
    -not $paid.paidAt -or $paidAgain.paidAt -cne $paid.paidAt) { throw 'Payment idempotency/time preservation failed' }
$hospitalOrder = @(Invoke-CourseApi GET '/hospital/orders?status=PAID' $hospital $null | Where-Object id -eq $order.id)
if ($hospitalOrder.Count -ne 1 -or $hospitalOrder[0].appointmentId -ne $appointmentId) { throw 'Hospital paid order missing' }
[pscustomobject]@{Result='PASS';FixturePath=$FixturePath;RunId=$fixture.runId;SlotId=$fixture.slotId;
    CanceledAppointmentId=$fixture.canceledAppointmentId;AppointmentId=$appointmentId;RecordId=$record.id;
    OrderId=$order.id;DisabledLogin=401;DuplicateBooking=409;CrossTenantDoctor=403;
    Payment=$paid.status;PaidAt=$paid.paidAt;CompletedAt=$record.completedAt;
    DoctorRecord=$doctorRecord[0].id;HospitalOrder=$hospitalOrder[0].id}
