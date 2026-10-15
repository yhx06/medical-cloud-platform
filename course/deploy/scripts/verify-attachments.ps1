param(
    [Parameter(Mandatory = $true)][string]$FixturePath,
    [Parameter(Mandatory = $true)][string]$EvidenceRoot,
    [string[]]$SkipGate = @()
)
# Attachment acceptance driver: runs the design section 12 gates against one owned fixture
# and writes a redacted JSON summary. A failed gate or a skipped mandatory gate exits non-zero.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
. (Join-Path $repo 'course\deploy\tests\attachments-fixture-ownership.ps1')

$fixtureFull = [IO.Path]::GetFullPath($FixturePath)
$evidenceFull = [IO.Path]::GetFullPath($EvidenceRoot)
if (-not (Test-Path -LiteralPath $fixtureFull -PathType Leaf)) { throw 'Fixture identity missing' }
if ((Get-Item -LiteralPath $fixtureFull -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Fixture identity is a reparse point' }
$fixture = [IO.File]::ReadAllText($fixtureFull) | ConvertFrom-Json
foreach ($component in @('mysql','storage','portal','paas')) { Assert-OwnedAttachmentsFixture $fixture $component }
[void][IO.Directory]::CreateDirectory($evidenceFull)

$mandatory = @('app-health','uploads-three-formats','pagination-recovery','limits','matrix','audit-redaction','storage-private-signing')
$found = New-Object 'System.Collections.Generic.List[string]'
$executed = New-Object 'System.Collections.Generic.List[string]'
$passed = New-Object 'System.Collections.Generic.List[string]'
$failed = New-Object 'System.Collections.Generic.List[string]'
$skipped = New-Object 'System.Collections.Generic.List[string]'
$limitations = New-Object 'System.Collections.Generic.List[string]'

function Register-Gate([string]$Name) { [void]$script:found.Add($Name) }
function Write-Evidence([string]$Name, $Payload) {
    Assert-OwnedAttachmentsFixture $script:fixture 'mysql'
    [IO.File]::WriteAllText((Join-Path $script:evidenceFull ($Name + '.json')), ($Payload | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
}
function Assert-True([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Invoke-Gate([string]$Name, [scriptblock]$Body) {
    Register-Gate $Name
    if ($script:SkipGate -contains $Name) { [void]$script:skipped.Add($Name); return }
    [void]$script:executed.Add($Name)
    try {
        & $Body | Out-Null
        [void]$script:passed.Add($Name)
        Write-Output ('PASSED ' + $Name)
    } catch {
        [void]$script:failed.Add($Name)
        Write-Evidence ('failure-' + $Name) @{ message = $_.Exception.Message }
        Write-Output ('FAILED ' + $Name + ': ' + $_.Exception.Message)
    }
}

# ---------- HTTP helpers: status-code style, never throwing on 4xx/5xx ----------
$paas = "http://127.0.0.1:$($fixture.paas.port)/paas/api"
$portal = "http://127.0.0.1:$($fixture.portal.port)/portal"
$tokens = @{}
function Get-Token([string]$User) {
    if ($tokens[$User]) { return $tokens[$User] }
    $result = Send-Request 'POST' "$paas/auth/login" $null (@{ username = $User; password = 'Demo@123456' })
    Assert-True ($result.status -eq 200) "login failed for $User ($($result.status))"
    $tokens[$User] = $result.body.data.accessToken
    return $tokens[$User]
}
function Send-Request([string]$Method, [string]$Uri, [string]$User, $Body, [byte[]]$Binary, [string]$BinaryType) {
    $headers = @{}
    if ($User) { $headers.Authorization = 'Bearer ' + (Get-Token $User) }
    $arguments = @{ Uri = $Uri; Method = $Method; Headers = $headers; TimeoutSec = 90; ErrorAction = 'Stop' }
    if ($Binary) { $arguments.Body = $Binary; $arguments.ContentType = $BinaryType }
    elseif ($null -ne $Body) { $arguments.Body = ($Body | ConvertTo-Json -Depth 5); $arguments.ContentType = 'application/json' }
    try {
        $response = Invoke-WebRequest @arguments -UseBasicParsing
        $text = $response.Content
        $parsed = $null
        try { $parsed = $text | ConvertFrom-Json } catch { }
        return @{ status = [int]$response.StatusCode; body = $parsed; raw = $text }
    } catch {
        $status = 0
        $text = ''
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }
        $parsed = $null
        try { $parsed = $text | ConvertFrom-Json } catch { }
        return @{ status = $status; body = $parsed; raw = $text }
    }
}
function Api([string]$Method, [string]$Path, [string]$User, $Body, [byte[]]$Binary, [string]$BinaryType) {
    return Send-Request $Method "$paas/$Path" $User $Body $Binary $BinaryType
}
function New-TestBytes([string]$Kind) {
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object Drawing.Bitmap 2,2
    $stream = New-Object IO.MemoryStream
    if ($Kind -eq 'jpg') { $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Jpeg) }
    elseif ($Kind -eq 'png') { $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png) }
    else {
        $bitmap.Dispose(); $stream.Dispose()
        $pdf = "%PDF-1.4`n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj`n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj`n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 20 20]>>endobj`ntrailer<</Root 1 0 R>>`n%%EOF`n"
        return [Text.Encoding]::ASCII.GetBytes($pdf)
    }
    $bitmap.Dispose()
    return $stream.ToArray()
}
function Get-Sha256([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant() } finally { $sha.Dispose() }
}
function New-UploadDescriptor([byte[]]$Bytes, [string]$Name, [string]$Mime) {
    return @{ uploadKey = [guid]::NewGuid().ToString(); fileName = $Name; contentType = $Mime; sizeBytes = $Bytes.Length; sha256 = (Get-Sha256 $Bytes) }
}
function Complete-Upload([string]$User, [string]$PreparePath, [byte[]]$Bytes, [string]$Name, [string]$Mime) {
    $prepared = Api POST $PreparePath $User (New-UploadDescriptor $Bytes $Name $Mime)
    Assert-True ($prepared.status -eq 200) "prepare $Name failed ($($prepared.status) $($prepared.raw))"
    $uploadId = $prepared.body.data.uploadId
    $content = Api PUT ("files/uploads/$uploadId/content") $User $null $Bytes 'application/octet-stream'
    Assert-True ($content.status -eq 200) "content $Name failed ($($content.status) $($content.raw))"
    return $uploadId
}

$tenantA = '11111111-1111-1111-1111-111111111111'
$slotA = 'aaaaaaa1-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
$appointment = $null
$preparePath = $null
$uploadIds = New-Object 'System.Collections.Generic.List[string]'

Write-Output "fixture owned: $($fixture.ownerId); paas=$($fixture.paas.port) portal=$($fixture.portal.port) storage=$($fixture.storage.port)"

Invoke-Gate 'app-health' {
    $page = Send-Request 'GET' "$portal/login" $null
    Assert-True ($page.status -eq 200) "portal login page $($page.status)"
    foreach ($user in @('patient_demo','doctor_a','doctor_b','hospital_a','platform_admin')) { [void](Get-Token $user) }
    $anonymous = Api GET 'patient/appointments' $null
    Assert-True ($anonymous.status -eq 401 -or $anonymous.status -eq 403) "anonymous appointments must be rejected, got $($anonymous.status)"
}

Invoke-Gate 'uploads-three-formats' {
    $book = Api POST 'patient/appointments' 'patient_demo' @{ slotId = $script:slotA }
    if ($book.status -eq 200) {
        $script:appointment = $book.body.data.id
    } else {
        $existing = Api GET 'patient/appointments?page=1&pageSize=20' 'patient_demo'
        Assert-True ($existing.status -eq 200) "existing appointments lookup failed ($($existing.status))"
        $match = @($existing.body.data | Where-Object { $_.slotId -eq $script:slotA }) | Select-Object -First 1
        if (-not $match) { $match = @($existing.body.data)[0] }
        Assert-True ($null -ne $match) "booking failed ($($book.status) $($book.raw)) and no existing appointment found"
        $script:appointment = $match.id
    }
    Assert-True ([bool]$script:appointment) 'booking response must carry the appointment id'
    $script:preparePath = "patient/appointments/$($script:appointment)/files/uploads"
    $jpg = New-TestBytes 'jpg'
    $png = New-TestBytes 'png'
    [void]$script:uploadIds.Add((Complete-Upload 'patient_demo' $script:preparePath $jpg 'photo.jpg' 'image/jpeg'))
    [void]$script:uploadIds.Add((Complete-Upload 'patient_demo' $script:preparePath $png 'scan.png' 'image/png'))
    $oversize = New-Object byte[] 10485761
    [Array]::Copy($jpg, 0, $oversize, 0, [Math]::Min($jpg.Length, $oversize.Length))
    $tooLarge = Api POST $script:preparePath 'patient_demo' (New-UploadDescriptor $oversize 'big.jpg' 'image/jpeg')
    Assert-True ($tooLarge.status -eq 413) "oversize descriptor must be 413, got $($tooLarge.status)"
    $wrongType = Api POST $script:preparePath 'patient_demo' (New-UploadDescriptor $jpg 'note.txt' 'text/plain')
    Assert-True ($wrongType.status -eq 400) "wrong type must be 400, got $($wrongType.status)"
    Write-Evidence 'upload-classes-formats' @{ appointmentId = $script:appointment; uploads = @($script:uploadIds) }
}

Invoke-Gate 'pagination-recovery' {
    $page1 = Api GET "patient/appointments/$($script:appointment)/files?page=1&pageSize=1" 'patient_demo'
    Assert-True ($page1.status -eq 200 -and $page1.body.data.items.Count -le 1) 'page size honoured'
    Assert-True ($page1.body.data.total -ge 2) 'total counts both published files'
    $page2 = Api GET "patient/appointments/$($script:appointment)/files?page=2&pageSize=1" 'patient_demo'
    Assert-True ($page2.status -eq 200 -and $page2.body.data.items.Count -ge 1) 'second page navigable'
    $first = $page1.body.data.items[0].fileId
    $operation = Api GET "files/uploads/$first" 'patient_demo'
    Assert-True ($operation.status -eq 200 -and $operation.body.data.uploadId -eq $first) 'operation recoverable by id without listing'
}

Invoke-Gate 'limits' {
    $bytes = New-TestBytes 'png'
    $one = Api POST $script:preparePath 'patient_demo' (New-UploadDescriptor $bytes 'pending1.png' 'image/png')
    $two = Api POST $script:preparePath 'patient_demo' (New-UploadDescriptor $bytes 'pending2.png' 'image/png')
    Assert-True ($one.status -eq 200 -and $two.status -eq 200) 'first two pending prepares accepted'
    $three = Api POST $script:preparePath 'patient_demo' (New-UploadDescriptor $bytes 'pending3.png' 'image/png')
    Assert-True ($three.status -eq 429) "third pending prepare must be 429, got $($three.status)"
    foreach ($pending in @($one, $two)) {
        $content = Api PUT ("files/uploads/$($pending.body.data.uploadId)/content") 'patient_demo' $null $bytes 'application/octet-stream'
        Assert-True ($content.status -eq 200) "pending upload completion failed ($($content.status))"
    }
}

Invoke-Gate 'matrix' {
    $anonymous = Api GET "patient/appointments/$($script:appointment)/files?page=bad&pageSize=bad" $null
    Assert-True ($anonymous.status -eq 401) "anonymous must be 401 before pagination, got $($anonymous.status)"
    $foreignDoctor = Api GET "patient/appointments/$($script:appointment)/files?page=bad&pageSize=bad" 'doctor_b'
    Assert-True ($foreignDoctor.status -eq 403) "other-tenant doctor must be 403 before pagination, got $($foreignDoctor.status)"
    $assigned = Api GET "doctor/appointments/$($script:appointment)/files?page=1&pageSize=20" 'doctor_a'
    Assert-True ($assigned.status -eq 200) "assigned doctor may list via the doctor route, got $($assigned.status)"
    $foreignId = [guid]::NewGuid().ToString()
    foreach ($check in @(
        @{ Method = 'GET'; Path = "files/uploads/$foreignId" },
        @{ Method = 'POST'; Path = "patient/files/$foreignId/delete" },
        @{ Method = 'POST'; Path = "files/$foreignId/download-url" })) {
        $result = Api $check.Method $check.Path 'patient_demo' $null
        Assert-True ($result.status -ge 400) "foreign id must fail on $($check.Path), got $($result.status)"
    }
}

Invoke-Gate 'audit-redaction' {
    $events = Api GET 'hospital/audit-events?page=1&size=50' 'hospital_a'
    Assert-True ($events.status -eq 200) "audit list $($events.status)"
    $payload = ($events | ConvertTo-Json -Depth 8)
    Assert-True ($payload -notmatch 'X-Amz-Signature') 'no signature material in audit output'
    Assert-True ($payload -notmatch 'objectKey|storageKey') 'no object keys in audit output'
}

Invoke-Gate 'storage-private-signing' {
    $endpoint = $script:fixture.storage.internalEndpoint
    $bucket = $script:fixture.storage.bucket
    $anonymous = Send-Request 'GET' "$endpoint/$bucket" $null
    Assert-True ($anonymous.status -eq 403) "anonymous list must be denied, got $($anonymous.status)"
    $fileId = $script:uploadIds[0]
    $download = Api POST "files/$fileId/download-url" 'patient_demo'
    Assert-True ($download.status -eq 200 -and $download.body.data.url) 'signed url returned'
    $content = Send-Request 'GET' $download.body.data.url $null
    Assert-True ($content.status -eq 200) "signed url readable, got $($content.status)"
    $tampered = $download.body.data.url -replace 'X-Amz-Signature=[0-9a-fA-F]', 'X-Amz-Signature=0'
    $denied = Send-Request 'GET' $tampered $null
    Assert-True ($denied.status -ge 400) "tampered signature denied, got $($denied.status)"
    Write-Evidence 'signed-download' @{ fileId = $fileId; expiresAt = $download.body.data.expiresAt }
}

Write-Output ('summary found=' + $found.Count + ' executed=' + $executed.Count + ' passed=' + $passed.Count + ' failed=' + $failed.Count + ' skipped=' + $skipped.Count)
$javaVersion = (cmd /c '"D:\Java\jdk1.8.0_341\bin\java.exe" -version 2>&1' | Select-Object -First 1)
$summary = [ordered]@{
    found = @($found); executed = @($executed); passed = @($passed); failed = @($failed); skipped = @($skipped)
    mandatorySkipped = @($skipped | Where-Object { $mandatory -contains $_ })
    environment = [ordered]@{
        ownerId = $fixture.ownerId; paasPort = $fixture.paas.port; portalPort = $fixture.portal.port; storagePort = $fixture.storage.port
        java = "$javaVersion"; fixture = $fixtureFull
    }
    limitations = @($limitations)
}
Assert-OwnedAttachmentsFixture $fixture 'mysql'
$summaryPath = Join-Path $evidenceFull 'attachments-verification-summary.json'
[IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output $summaryPath
if ($failed.Count -gt 0) { Write-Output 'VERIFICATION FAILED'; exit 1 }
if (@($skipped | Where-Object { $mandatory -contains $_ }).Count -gt 0) { Write-Output 'VERIFICATION FAILED: mandatory gate skipped'; exit 2 }
Write-Output 'VERIFICATION PASSED'
exit 0
