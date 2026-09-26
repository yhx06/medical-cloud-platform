param(
    [string]$ApiBase = 'http://127.0.0.1:8080/paas/api',
    [string]$SlotId = 'aaaaaaa1-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    [string]$AppointmentId = '',
    [string]$DemoPassword = 'Demo@123456'
)

$ErrorActionPreference = 'Stop'
$base = $ApiBase.TrimEnd('/')

function Invoke-CourseApi {
    param([string]$Method, [string]$Path, [string]$Token, [object]$Body)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $arguments = @{Uri = "$base$Path"; Method = $Method; Headers = $headers; TimeoutSec = 15}
    if ($Method -eq 'POST') { $arguments.ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $Body) {
        $arguments.Body = ($Body | ConvertTo-Json -Compress)
    } elseif ($Method -eq 'POST') {
        $arguments.Body = '{}'
    }
    $response = Invoke-RestMethod @arguments
    if ($response.code -ne 'OK') { throw "API returned $($response.code) at $Path" }
    return $response.data
}

function Assert-CourseStatus {
    param([int]$Expected, [string]$Method, [string]$Path, [string]$Token, [object]$Body)
    try {
        Invoke-CourseApi -Method $Method -Path $Path -Token $Token -Body $Body | Out-Null
    } catch {
        $actual = [int]$_.Exception.Response.StatusCode
        if ($actual -eq $Expected) { return }
        throw "Expected HTTP $Expected at $Path, received $actual"
    }
    throw "Expected HTTP $Expected at $Path, but request succeeded"
}

Assert-CourseStatus -Expected 401 -Method POST -Path '/auth/login' -Token $null `
    -Body @{username='disabled_user';password=$DemoPassword}
$patient = Invoke-CourseApi -Method POST -Path '/auth/login' -Token $null `
    -Body @{username='patient_demo';password=$DemoPassword}
$doctorA = Invoke-CourseApi -Method POST -Path '/auth/login' -Token $null `
    -Body @{username='doctor_a';password=$DemoPassword}
$doctorB = Invoke-CourseApi -Method POST -Path '/auth/login' -Token $null `
    -Body @{username='doctor_b';password=$DemoPassword}

if (-not $AppointmentId) {
    $booking = Invoke-CourseApi -Method POST -Path '/patient/appointments' -Token $patient.accessToken `
        -Body @{slotId=$SlotId}
    $AppointmentId = $booking.id
}
Assert-CourseStatus -Expected 409 -Method POST -Path '/patient/appointments' -Token $patient.accessToken `
    -Body @{slotId=$SlotId}
if (-not $AppointmentId) { throw 'Booking did not return an appointment id' }
$appointmentId = $AppointmentId

Assert-CourseStatus -Expected 403 -Method POST -Path "/doctor/appointments/$appointmentId/start" `
    -Token $doctorB.accessToken -Body $null
Invoke-CourseApi -Method POST -Path "/doctor/appointments/$appointmentId/start" `
    -Token $doctorA.accessToken -Body $null | Out-Null
$record = Invoke-CourseApi -Method POST -Path "/doctor/appointments/$appointmentId/complete" `
    -Token $doctorA.accessToken -Body @{diagnosis='演示：上呼吸道感染';advice='休息、补水；症状持续请线下就医'}
Assert-CourseStatus -Expected 409 -Method POST -Path "/doctor/appointments/$appointmentId/complete" `
    -Token $doctorA.accessToken -Body @{diagnosis='覆盖';advice='覆盖'}

$ownRecord = Invoke-CourseApi -Method GET -Path "/patient/appointments/$appointmentId/record" `
    -Token $patient.accessToken -Body $null
$order = Invoke-CourseApi -Method GET -Path "/patient/appointments/$appointmentId/order" `
    -Token $patient.accessToken -Body $null
if ($ownRecord.id -ne $record.id -or $order.amount -ne 39) {
    throw 'Record or fee does not match the appointment snapshot'
}
$paid = Invoke-CourseApi -Method POST -Path "/patient/orders/$($order.id)/pay" `
    -Token $patient.accessToken -Body $null
$paidAgain = Invoke-CourseApi -Method POST -Path "/patient/orders/$($order.id)/pay" `
    -Token $patient.accessToken -Body $null
if ($paid.status -ne 'PAID' -or $paidAgain.id -ne $order.id) {
    throw 'Payment idempotency failed'
}

[pscustomobject]@{
    Result = 'PASS'
    AppointmentId = $appointmentId
    RecordId = $record.id
    OrderId = $order.id
    DisabledLogin = 401
    DuplicateBooking = 409
    CrossTenantDoctor = 403
    Payment = $paid.status
}
