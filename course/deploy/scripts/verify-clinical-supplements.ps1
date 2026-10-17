param(
    [Parameter(Mandatory=$true)][string]$FixturePath,
    [Parameter(Mandatory=$true)][string]$EvidenceRoot,
    [Parameter(Mandatory=$true)][string]$AccountsPath
)
# This is an incremental acceptance driver. HTTP success cannot attest the
# external Spec9 gates. All data below is synthetic and held only in memory.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\tests\attachments-fixture-ownership.ps1')
$required=@(
    'fixture-and-private-accounts','login-identities','fresh-appointment',
    'patient-complaint-version','patient-complaint-no-change','patient-complaint-stale',
    'draft-save-reopen','draft-private-no-order','unauthenticated-401','parent-authorization-403',
    'legacy-complete-no-draft','complete-draft-conflict','complete-and-draft-disappearance',
    'correction-chain','operation-key-lost-response-retry','operation-key-variant-conflict',
    'original-and-order-unchanged','patient-history-pagination','completed-complaint-readonly',
    'canceled-complaint-readonly','safe-errors','audit-clinical-redaction','container-log-redaction',
    'migration-empty-schema','migration-ten-table-baseline','migration-repeat-readonly',
    'migration-partial-refused','migration-foreign-refused','migration-backup-failure-refused',
    'migration-old-record-order-preserved','mysql-concurrency-draft','mysql-concurrency-complete-draft',
    'mysql-concurrency-complete-complaint','mysql-concurrency-correction','hospital-freeze-write-race',
    'account-disabled-rejected','hospital-frozen-read-write','four-clinical-states',
    'unique-record-and-order','success-audit-rollback','independent-failure-audit',
    'wildfly-ejb-jta-boundary','dual-container-bindings','minio-stopped-clinical-independence',
    'browser-chrome','browser-edge','browser-js-disabled','browser-keyboard',
    'portal-csrf','portal-escaping-chinese','portal-conflict-input-preserved',
    'full-clean-verify','full-slice-independent-review'
)
$gates=[ordered]@{};foreach($name in $required){$gates[$name]='unverified'}
$facts=[ordered]@{originalUnchanged=$false;orderUnchanged=$false;retryRecovered=$false;historyChainValid=$false}
$script:activeGate='fixture-and-private-accounts';$script:requestCount=0;$script:errorCount=0
$script:errorsSafe=$true;$script:secrets=@();$script:tokens=@{}
$completeDriverRun=$false;$summaryWritten=$false;$script:evidenceChild=$null
$script:logPolicyInitialized=$false;$script:hasDeclaredBoot=$false

function Require([bool]$Condition){if(-not $Condition){throw 'CLINICAL_CHECK_FAILED'}}
function Is-CanonicalUuid($Value){return ($Value -is [string] -and $Value -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')}
function Set-Gate([string]$Name,[string]$Status){
    if($Name -notin $required -or $Status -notin @('passed','failed','unverified')){throw 'GATE_REGISTRY_INVALID'}
    $gates[$Name]=$Status
}
function Invoke-Gate([string]$Name,[scriptblock]$Action){
    $script:activeGate=$Name
    try{& $Action;Set-Gate $Name 'passed'}catch{Set-Gate $Name 'failed';throw 'CLINICAL_GATE_FAILED'}
}
function Assert-ClinicalPath([string]$Path,[bool]$Existing=$true){
    Require (-not [string]::IsNullOrWhiteSpace($Path) -and [IO.Path]::IsPathRooted($Path))
    $full=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    Require ($full.StartsWith($script:runtimeRoot+'\',[StringComparison]::OrdinalIgnoreCase))
    if($Existing){Require (Test-Path -LiteralPath $full)}
    $cursor=$full
    while($cursor){
        if(Test-Path -LiteralPath $cursor){Require (-not ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint))}
        $parent=Split-Path $cursor -Parent
        if($parent -eq $cursor){break};$cursor=$parent
    }
    return $full
}
function Assert-ClinicalInstance {
    # A changed descriptor cannot redirect this run to another otherwise valid fixture.
    Assert-ClinicalDescriptor $script:fixtureFull
    Require ([IO.File]::ReadAllText($script:fixtureFull) -ceq $script:fixtureSnapshot)
    Assert-OwnedAttachmentsFixture $script:fixture 'mysql'
    Assert-OwnedAttachmentsFixture $script:fixture 'paas'
    [void](Assert-ClinicalPath $script:accountsFull)
    [void](Assert-ClinicalPath $script:evidenceParent $false)
    Assert-PrivateAccounts
    Assert-ClinicalPrivacy
    if($script:logPolicyInitialized){Assert-ClinicalLogSources}
}
function Assert-ClinicalDescriptor([string]$Path){
    # Existing creators keep their read-only descriptor in the SDD directory.
    # Only accounts and evidence are constrained to the owned runtime root.
    Require ([IO.Path]::IsPathRooted($Path) -and (Test-Path -LiteralPath $Path -PathType Leaf))
    $cursor=[IO.Path]::GetFullPath($Path)
    Require ((Get-Item -LiteralPath $cursor -Force).Length -le 65536)
    while($cursor){
        Require (-not ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint))
        $parent=Split-Path $cursor -Parent
        if($parent -eq $cursor){break};$cursor=$parent
    }
}
function Assert-PrivateAccounts {
    Require (Test-Path -LiteralPath $script:accountsFull -PathType Leaf)
    Assert-PrivateAcl $script:accountsFull $true
}
function Assert-PrivateAcl([string]$Path,[bool]$Protected=$false){
    $acl=Get-Acl -LiteralPath $Path
    if($Protected){Require $acl.AreAccessRulesProtected}
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    Require ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -in $allowed)
    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
        if($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow){Require ($rule.IdentityReference.Value -in $allowed)}
    }
}
function Assert-ClinicalPrivatePath([string]$Path){
    $cursor=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    Require ($cursor -ieq $script:privateBoundary -or $cursor.StartsWith($script:privateBoundary+'\',[StringComparison]::OrdinalIgnoreCase))
    while($cursor){
        if(Test-Path -LiteralPath $cursor){Assert-PrivateAcl $cursor}
        if($cursor -ieq $script:privateBoundary){return}
        $cursor=Split-Path $cursor -Parent
    }
    throw 'PRIVATE_BOUNDARY_INVALID'
}
function Assert-ClinicalPrivacy {
    # A private common child is sufficient even when the fixture runtime itself
    # is broad. Every existing descendant/target must preserve that boundary.
    $boundary=Split-Path $script:accountsFull -Parent
    while($boundary -and $script:evidenceParent -ine $boundary -and -not $script:evidenceParent.StartsWith($boundary+'\',[StringComparison]::OrdinalIgnoreCase)){$boundary=Split-Path $boundary -Parent}
    while($boundary -and ($boundary -ieq $script:runtimeRoot -or $boundary.StartsWith($script:runtimeRoot+'\',[StringComparison]::OrdinalIgnoreCase)) -and -not (Get-Acl -LiteralPath $boundary).AreAccessRulesProtected){$boundary=Split-Path $boundary -Parent}
    Require ($boundary -and ($boundary -ieq $script:runtimeRoot -or $boundary.StartsWith($script:runtimeRoot+'\',[StringComparison]::OrdinalIgnoreCase)))
    Require (Test-Path -LiteralPath $boundary -PathType Container)
    Assert-PrivateAcl $boundary $true
    $script:privateBoundary=$boundary
    Assert-ClinicalPrivatePath $script:accountsFull
    Assert-ClinicalPrivatePath $script:evidenceParent
    if(Test-Path -LiteralPath $script:evidenceParent){Require (Test-Path -LiteralPath $script:evidenceParent -PathType Container)}
}
function Initialize-ClinicalLogPolicy {
    $roles=@('paasFiles','portalAccessFiles','portalOtherFiles','rootPaasCaptures','rootPortalCaptures','bootPaasCaptures','bootPortalCaptures')
    $script:logEncodings=@{};foreach($role in $roles){$script:logEncodings[$role]='UTF-8'}
    $policyProperties=@($script:fixture.clinical.PSObject.Properties|Where-Object{$_.Name -ceq 'logPolicy'})
    $bootProperties=@($script:fixture.clinical.PSObject.Properties|Where-Object{$_.Name -ceq 'bootEvidence'})
    $script:hasDeclaredBoot=($bootProperties.Count -eq 1)
    if($policyProperties.Count){
        $policy=$script:fixture.clinical.logPolicy
        Require ($policy -is [pscustomobject] -and $policy.encodings -is [pscustomobject])
        Require ((($policy.version -is [int]) -or ($policy.version -is [long])) -and $policy.version -eq 1)
        $policyKeys=@($policy.PSObject.Properties.Name)
        Require ($policyKeys.Count -eq 2 -and @($policyKeys|Where-Object{$_ -cnotin @('version','encodings')}).Count -eq 0)
        $keys=@($policy.encodings.PSObject.Properties.Name)
        Require ($keys.Count -eq 7 -and @($keys|Where-Object{$_ -cnotin $roles}).Count -eq 0)
        foreach($role in $roles){
            $encoding=$policy.encodings.$role
            Require ($encoding -is [string] -and ($encoding -cin @('UTF-8','GBK') -or ($role -ceq 'portalAccessFiles' -and $encoding -ceq 'ISO-8859-1')))
            $script:logEncodings[$role]=$encoding
        }
        Require $script:hasDeclaredBoot
    }
    Assert-ClinicalLogSources
    $script:logPolicyInitialized=$true
}
function Assert-ClinicalLogSources {
    if(-not $script:hasDeclaredBoot){return}
    $boot=$script:fixture.clinical.bootEvidence
    Require ($boot -is [string] -and (Test-Path -LiteralPath $boot -PathType Container))
    [void](Assert-ClinicalPath $boot);Assert-ClinicalPrivatePath $boot
    foreach($name in @('paas.stdout.log','paas.stderr.log','portal.stdout.log','portal.stderr.log')){
        $path=Join-Path $boot $name
        Require ((Test-Path -LiteralPath $path -PathType Leaf) -and (Get-Item -LiteralPath $path -Force) -is [IO.FileInfo])
        [void](Assert-ClinicalPath $path);Assert-ClinicalPrivatePath $path
    }
}
function Invoke-ClinicalTransport($Uri,$Method,$Token,$Json){
    # Bounded .NET transport works in Windows PowerShell 5.1, denies redirects,
    # ignores machine proxies, and never exposes WebException.Response text.
    $response=$null;$inputStream=$null;$outputStream=$null;$bufferStream=$null
    try{
        $request=[Net.HttpWebRequest]::Create($Uri)
        $request.Method=$Method;$request.AllowAutoRedirect=$false;$request.Proxy=$null
        $request.Timeout=15000;$request.ReadWriteTimeout=15000;$request.Accept='application/json'
        $request.ContentType='application/json; charset=utf-8'
        if($Token){$request.Headers['Authorization']='Bearer '+$Token}
        if($null -ne $Json){
            $bytes=(New-Object Text.UTF8Encoding($false,$true)).GetBytes($Json)
            Require ($bytes.Length -le 1048576)
            $request.ContentLength=$bytes.Length;$outputStream=$request.GetRequestStream()
            $outputStream.Write($bytes,0,$bytes.Length);$outputStream.Dispose();$outputStream=$null
        }
        try{$response=$request.GetResponse()}catch [Net.WebException]{
            if(-not $_.Exception.Response){throw 'HTTP_TRANSPORT_FAILED'}
            $response=$_.Exception.Response
        }
        Require ([int]$response.StatusCode -lt 300 -or [int]$response.StatusCode -ge 400)
        Require ($response.ContentLength -le 1048576)
        $inputStream=$response.GetResponseStream();$bufferStream=New-Object IO.MemoryStream
        $buffer=New-Object byte[] 8192
        while(($read=$inputStream.Read($buffer,0,$buffer.Length)) -gt 0){
            Require ($bufferStream.Length+$read -le 1048576);$bufferStream.Write($buffer,0,$read)
        }
        $text=(New-Object Text.UTF8Encoding($false,$true)).GetString($bufferStream.ToArray())
        return @{Status=[int]$response.StatusCode;Text=$text}
    }catch{throw 'HTTP_TRANSPORT_FAILED'}finally{
        if($outputStream){$outputStream.Dispose()};if($inputStream){$inputStream.Dispose()}
        if($bufferStream){$bufferStream.Dispose()};if($response){$response.Dispose()}
    }
}
function Assert-NoSecret([string]$Text){
    foreach($secret in $script:secrets){if($secret -and $Text.Contains($secret)){throw 'CLINICAL_REDACTION_FAILED'}}
}
function Assert-NoSecretValue($Value,[int]$Depth=0){
    Require ($Depth -le 32)
    if($null -eq $Value){return}
    if($Value -is [string]){Assert-NoSecret $Value;return}
    if($Value -is [Collections.IDictionary]){
        foreach($key in $Value.Keys){Assert-NoSecret ([string]$key);Assert-NoSecretValue $Value[$key] ($Depth+1)}
        return
    }
    if($Value -is [Collections.IEnumerable]){
        foreach($item in $Value){Assert-NoSecretValue $item ($Depth+1)}
        return
    }
    if($Value -is [ValueType]){return}
    foreach($property in $Value.PSObject.Properties){
        Assert-NoSecret $property.Name;Assert-NoSecretValue $property.Value ($Depth+1)
    }
}
function Open-ClinicalLogRead([string]$Path){
    return [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
}
function Read-ClinicalLogText([string]$Path,[string]$EncodingName='UTF-8',[string]$Role='paasFiles'){
    $stream=$null;$memory=$null
    try{
        $stream=Open-ClinicalLogRead $Path
        $initialLength=$stream.Length;Require ($initialLength -le 16777216)
        $memory=New-Object IO.MemoryStream;$buffer=New-Object byte[] 8192
        while(($read=$stream.Read($buffer,0,$buffer.Length)) -gt 0){
            Require ($memory.Length+$read -le 16777216);$memory.Write($buffer,0,$read)
        }
        $finalLength=$stream.Length
        Require ($finalLength -le 16777216 -and $finalLength -eq $memory.Length -and $finalLength -ge $initialLength)
        $bytes=$memory.ToArray()
        $encoding=switch -CaseSensitive($EncodingName){
            'UTF-8'{New-Object Text.UTF8Encoding($false,$true)}
            'GBK'{[Text.Encoding]::GetEncoding(936,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)}
            'ISO-8859-1'{Require ($Role -ceq 'portalAccessFiles');[Text.Encoding]::GetEncoding(28591,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)}
            default{throw 'LOG_ENCODING_INVALID'}
        }
        $decoded=$encoding.GetString($bytes);$roundtrip=$encoding.GetBytes($decoded)
        Require ([Collections.StructuralComparisons]::StructuralEqualityComparer.Equals($bytes,$roundtrip))
        return $decoded
    }finally{if($memory){$memory.Dispose()};if($stream){$stream.Dispose()}}
}
function Invoke-Clinical([string]$Method,[string]$Path,[string]$Account,$Body,[int]$Expected=200){
    Require ($Path -cmatch '^(auth/login|patient/appointments(?:/[0-9a-f-]{36}(?:/(?:complaint|record(?:/(?:details|corrections))?|order))?)?|doctor/(?:appointments(?:/[0-9a-f-]{36}/(?:clinical-workspace|draft|start|complete))?|records/[0-9a-f-]{36}(?:/corrections)?)|platform/audit-events)(?:\?[A-Za-z0-9=&-]+)?$')
    $json=if($null -ne $Body){$Body|ConvertTo-Json -Depth 12 -Compress}else{$null}
    $token=if($Account){$script:tokens[$Account]}else{$null}
    Assert-ClinicalInstance
    $result=Invoke-ClinicalTransport ('http://127.0.0.1:'+$script:fixture.paas.port+'/paas/api/'+$Path) $Method $token $json
    $script:requestCount++
    Require ($result.Text -is [string] -and [Text.Encoding]::UTF8.GetByteCount($result.Text) -le 1048576)
    Require ([int]$result.Status -eq $Expected)
    if($Expected -ne 200){
        $script:errorCount++
        try{
            Assert-NoSecret $result.Text
            $errorBody=$result.Text|ConvertFrom-Json
            Assert-NoSecretValue $errorBody
            Require ($errorBody.code -cmatch '^[A-Z][A-Z0-9_]{0,79}$' -and (Is-CanonicalUuid $errorBody.requestId))
            Require ($errorBody.message -is [string] -and $errorBody.message.Length -le 160)
            Require ($result.Text -notmatch '(?i)java\.|exception|select\s|insert\s|jdbc:|password|bearer ')
            Require (@($errorBody.PSObject.Properties.Name|Where-Object{$_ -notin @('code','message','requestId')}).Count -eq 0)
        }catch{$script:errorsSafe=$false;throw 'UNSAFE_ERROR_RESPONSE'}
        return $null
    }
    $parsed=$result.Text|ConvertFrom-Json
    Require ($parsed.code -ceq 'OK')
    return $parsed.data
}
function Content-Same($Left,$Right){
    if($null -eq $Left -or $null -eq $Right){return $false}
    foreach($field in @('complaint','diagnosis','advice','notes')){if($Left.$field -cne $Right.$field){return $false}}
    return $true
}
function Copy-ClinicalContent($Value){
    return @{complaint=$Value.complaint;diagnosis=$Value.diagnosis;advice=$Value.advice;notes=$Value.notes}
}
function Assert-CorrectionResponse($Item,$Request,$Before){
    Require ((Is-CanonicalUuid $Item.id) -and $Item.sequence -eq ($Request.expectedRevision+1))
    Require ($Item.doctorId -ceq $script:accounts.doctor.id -and $Item.reason -ceq $Request.reason)
    Require ((Content-Same $Item.before $Before) -and (Content-Same $Item.after $Request.after))
    $parsedTime=[datetimeoffset]::MinValue
    Require ($Item.createdAt -is [string] -and $Item.createdAt -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?\+08:00$')
    Require ([datetimeoffset]::TryParse($Item.createdAt,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$parsedTime))
}
function New-ExpectedCorrection($Item,$Request,$Before){
    # Only server-generated identity/time come from the validated response;
    # all clinical expectations come from submitted requests in this run.
    return [pscustomobject]@{id=$Item.id;sequence=$Request.expectedRevision+1;before=(Copy-ClinicalContent $Before);after=(Copy-ClinicalContent $Request.after);reason=$Request.reason;doctorId=$script:accounts.doctor.id;createdAt=$Item.createdAt}
}
function Assert-PersistedCorrection($Item,$Expected){
    Require ($Item.id -ceq $Expected.id -and $Item.sequence -eq $Expected.sequence -and $Item.doctorId -ceq $Expected.doctorId -and $Item.createdAt -ceq $Expected.createdAt -and $Item.reason -ceq $Expected.reason)
    Require ((Content-Same $Item.before $Expected.before) -and (Content-Same $Item.after $Expected.after))
}
function Snapshot($Value){return ($Value|ConvertTo-Json -Depth 15 -Compress)}
function New-Content([int]$Version){return @{complaint=$script:clinicalCanary+':complaint-'+$Version;diagnosis=$script:clinicalCanary+':diagnosis-'+$Version;advice=$script:clinicalCanary+':advice-'+$Version;notes=$script:clinicalCanary+':notes-'+$Version}}
function Assert-FreshAppointment([string]$Id){
    Require (Is-CanonicalUuid $Id)
    $rows=@(Invoke-Clinical 'GET' 'doctor/appointments' 'doctor' $null)
    $matches=@($rows|Where-Object{$_.id -ceq $Id})
    Require ($matches.Count -eq 1 -and $matches[0].status -ceq 'BOOKED')
    Require ($matches[0].doctorId -ceq $script:accounts.doctor.id -and $matches[0].patientId -ceq $script:accounts.patient.id -and $matches[0].tenantId -ceq $script:accounts.doctor.tenantId)
    $w=Invoke-Clinical 'GET' "doctor/appointments/$Id/clinical-workspace" 'doctor' $null
    Require ($w.appointmentId -ceq $Id -and $w.status -ceq 'BOOKED' -and $null -eq $w.draft -and $null -eq $w.recordId)
    Require ($w.patientComplaint.revision -eq 0 -and $null -eq $w.patientComplaint.description -and $null -eq $w.patientComplaint.updatedAt)
    $null=Invoke-Clinical 'GET' "patient/appointments/$Id/order" 'patient' $null 403
}
function Write-ClinicalSummary {
    # A missing registry entry cannot vanish from the required set or counts.
    foreach($name in $required){if(-not $gates.Contains($name)){$gates[$name]='unverified'}}
    $executed=@();$passed=@();$failed=@();$skipped=@();$unverified=@()
    foreach($name in $required){
        switch($gates[$name]){'passed'{$executed+=$name;$passed+=$name} 'failed'{$executed+=$name;$failed+=$name} default{$unverified+=$name}}
    }
    $accepted=($completeDriverRun -and $passed.Count -eq $required.Count -and $failed.Count -eq 0 -and $skipped.Count -eq 0 -and $unverified.Count -eq 0)
    $summary=[ordered]@{schemaVersion=1;completeDriverRun=[bool]$completeDriverRun;wholeTask6Accepted=[bool]$accepted;required=$required;executed=$executed;passed=$passed;failed=$failed;skipped=$skipped;unverified=$unverified;counts=[ordered]@{required=$required.Count;executed=$executed.Count;passed=$passed.Count;failed=$failed.Count;skipped=0;unverified=$unverified.Count;requests=$script:requestCount};facts=$facts}
    Assert-ClinicalInstance
    [void][IO.Directory]::CreateDirectory($script:evidenceParent)
    Assert-ClinicalInstance
    $script:evidenceChild=Join-Path $script:evidenceParent ([guid]::NewGuid().ToString())
    [void][IO.Directory]::CreateDirectory($script:evidenceChild)
    Assert-ClinicalInstance
    [void](Assert-ClinicalPath $script:evidenceChild)
    Assert-ClinicalPrivatePath $script:evidenceChild
    $summaryText=$summary|ConvertTo-Json -Depth 8
    Assert-NoSecret $summaryText;Assert-NoSecretValue $summary
    Assert-ClinicalInstance
    $summaryPath=Join-Path $script:evidenceChild 'clinical-supplements-summary.json'
    [void](Assert-ClinicalPath $summaryPath $false);Assert-ClinicalPrivatePath $summaryPath
    $summaryStream=$null
    try{
        $summaryStream=New-Object IO.FileStream($summaryPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        Assert-ClinicalInstance;Assert-ClinicalPrivatePath $summaryPath
        $summaryBytes=(New-Object Text.UTF8Encoding($false)).GetBytes($summaryText)
        $summaryStream.Write($summaryBytes,0,$summaryBytes.Length)
    }finally{if($summaryStream){$summaryStream.Dispose()}}
    $script:summaryWritten=$true
}

try{
    Invoke-Gate 'fixture-and-private-accounts' {
        Require ([IO.Path]::IsPathRooted($FixturePath) -and [IO.Path]::IsPathRooted($AccountsPath) -and [IO.Path]::IsPathRooted($EvidenceRoot))
        $script:fixtureFull=[IO.Path]::GetFullPath($FixturePath);Assert-ClinicalDescriptor $script:fixtureFull
        $script:fixtureSnapshot=[IO.File]::ReadAllText($script:fixtureFull)
        $script:fixture=$script:fixtureSnapshot|ConvertFrom-Json
        $script:runtimeRoot=[IO.Path]::GetFullPath($script:fixture.runtimeRoot).TrimEnd('\','/')
        $script:accountsFull=[IO.Path]::GetFullPath($AccountsPath);$script:evidenceParent=[IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\','/')
        Assert-ClinicalInstance;Assert-PrivateAccounts
        Require ((Get-Item -LiteralPath $script:accountsFull).Length -le 65536)
        $script:accounts=[IO.File]::ReadAllText($script:accountsFull)|ConvertFrom-Json
        Require ($script:accounts.ownerId -ceq $script:fixture.ownerId)
        $ids=@();$users=@()
        foreach($name in @('patient','otherPatient','doctor','otherDoctor','crossDoctor','hospital','platform')){
            $a=$script:accounts.$name
            Require ($a -and $a.username -is [string] -and $a.username -cmatch '^[A-Za-z0-9_.-]{1,100}$' -and $a.password -is [string] -and $a.password.Length -gt 0 -and $a.password.Length -le 1000 -and (Is-CanonicalUuid $a.id))
            Require ($null -eq $a.tenantId -or (Is-CanonicalUuid $a.tenantId))
            Require ($a.id -notin $ids -and $a.username -notin $users);$ids+=$a.id;$users+=$a.username;$script:secrets+=$a.password
        }
        Require ($script:accounts.doctor.tenantId -and $script:accounts.doctor.tenantId -ceq $script:accounts.otherDoctor.tenantId -and $script:accounts.doctor.tenantId -ceq $script:accounts.hospital.tenantId)
        Require ($script:accounts.crossDoctor.tenantId -and $script:accounts.crossDoctor.tenantId -cne $script:accounts.doctor.tenantId)
        $script:clinicalCanary='CLINICAL-'+[guid]::NewGuid().ToString('N');$script:secrets+=$script:clinicalCanary
        $script:apt=$script:accounts.appointmentId;Require (Is-CanonicalUuid $script:apt)
        foreach($optional in @('legacyAppointmentId','canceledAppointmentId')){
            if($script:accounts.$optional){Require ((Is-CanonicalUuid $script:accounts.$optional) -and $script:accounts.$optional -cne $script:apt)}
        }
        Require (-not $script:accounts.legacyAppointmentId -or $script:accounts.legacyAppointmentId -cne $script:accounts.canceledAppointmentId)
        Initialize-ClinicalLogPolicy
    }
    Invoke-Gate 'login-identities' {
        foreach($name in @('patient','otherPatient','doctor','otherDoctor','crossDoctor','hospital','platform')){
            $account=$script:accounts.$name
            $login=Invoke-Clinical 'POST' 'auth/login' '' @{username=$account.username;password=$account.password}
            $expectedRole=switch($name){'hospital'{'HOSPITAL_ADMIN'} 'platform'{'PLATFORM_ADMIN'} {$_ -in @('patient','otherPatient')}{'PATIENT'} default{'DOCTOR'}}
            Require ($login.username -ceq $account.username -and $login.role -ceq $expectedRole -and $login.accessToken -is [string] -and $login.accessToken.Length -gt 0 -and $login.accessToken.Length -le 16384)
            $script:tokens[$name]=$login.accessToken;$script:secrets+=$login.accessToken
        }
    }
    Invoke-Gate 'fresh-appointment' {Assert-FreshAppointment $script:apt}
    $patientPath="patient/appointments/$script:apt";$doctorPath="doctor/appointments/$script:apt"
    Invoke-Gate 'unauthenticated-401' {
        $null=Invoke-Clinical 'GET' "$doctorPath/clinical-workspace" '' $null 401
        $null=Invoke-Clinical 'GET' "$patientPath/complaint" '' $null 401
        $null=Invoke-Clinical 'PUT' "$patientPath/complaint" '' @{} 401
        $null=Invoke-Clinical 'PUT' "$doctorPath/draft" '' @{} 401
        $null=Invoke-Clinical 'POST' "$doctorPath/complete" '' @{} 401
        $null=Invoke-Clinical 'GET' "$patientPath/record/corrections?page=invalid&pageSize=6" '' $null 401
    }
    Invoke-Gate 'patient-complaint-version' {
        $script:complaint=Invoke-Clinical 'PUT' "$patientPath/complaint" 'patient' @{description=$script:clinicalCanary+':patient';expectedRevision=0}
        Require ($script:complaint.revision -eq 1 -and $script:complaint.description -ceq ($script:clinicalCanary+':patient') -and $script:complaint.updatedAt -and $script:complaint.writable)
        $read=Invoke-Clinical 'GET' "$patientPath/complaint" 'patient' $null
        Require ((Snapshot $read) -ceq (Snapshot $script:complaint))
    }
    Invoke-Gate 'patient-complaint-no-change' {
        $same=Invoke-Clinical 'PUT' "$patientPath/complaint" 'patient' @{description=$script:complaint.description;expectedRevision=1}
        Require ($same.revision -eq 1 -and $same.updatedAt -ceq $script:complaint.updatedAt)
    }
    Invoke-Gate 'patient-complaint-stale' {$null=Invoke-Clinical 'PUT' "$patientPath/complaint" 'patient' @{description=$script:complaint.description;expectedRevision=0} 409}
    Invoke-Gate 'draft-save-reopen' {
        $null=Invoke-Clinical 'POST' "$doctorPath/start" 'doctor' $null
        $script:content=New-Content 0;$draftBody=@{};foreach($field in $script:content.Keys){$draftBody[$field]=$script:content[$field]};$draftBody.expectedRevision=0
        $script:draft=Invoke-Clinical 'PUT' "$doctorPath/draft" 'doctor' $draftBody
        $workspace=Invoke-Clinical 'GET' "$doctorPath/clinical-workspace" 'doctor' $null
        Require ($script:draft.revision -eq 1 -and $script:draft.savedAt -and $workspace.draft.revision -eq 1 -and (Content-Same $workspace.draft.content $script:content))
        Require ($workspace.patientComplaint.description -ceq $script:complaint.description -and $workspace.patientComplaint.revision -eq 1)
        $null=Invoke-Clinical 'PUT' "$doctorPath/draft" 'doctor' $draftBody 409
    }
    Invoke-Gate 'draft-private-no-order' {
        $null=Invoke-Clinical 'GET' "$patientPath/order" 'patient' $null 403
        foreach($name in @('patient','otherPatient','otherDoctor','crossDoctor','hospital','platform')){
            $null=Invoke-Clinical 'GET' "$doctorPath/clinical-workspace" $name $null 403
            $null=Invoke-Clinical 'PUT' "$doctorPath/draft" $name @{expectedRevision=1;diagnosis=$script:clinicalCanary} 403
        }
    }
    if($script:accounts.legacyAppointmentId){
        Invoke-Gate 'legacy-complete-no-draft' {
            $legacy=$script:accounts.legacyAppointmentId;Assert-FreshAppointment $legacy
            $null=Invoke-Clinical 'POST' "doctor/appointments/$legacy/start" 'doctor' $null
            $old=Invoke-Clinical 'POST' "doctor/appointments/$legacy/complete" 'doctor' @{diagnosis=$script:clinicalCanary+':legacy';advice=$script:clinicalCanary+':legacy-advice'}
            Require (Is-CanonicalUuid $old.id)
            Require ($old.appointmentId -ceq $legacy -and $old.doctorId -ceq $script:accounts.doctor.id -and $old.patientId -ceq $script:accounts.patient.id)
            Require ($null -eq $old.complaint -and $null -eq $old.notes -and $old.diagnosis -ceq ($script:clinicalCanary+':legacy') -and $old.advice -ceq ($script:clinicalCanary+':legacy-advice'))
        }
    }
    Invoke-Gate 'complete-draft-conflict' {
        $null=Invoke-Clinical 'POST' "$doctorPath/complete" 'doctor' $script:content 409
        $wrong=@{};foreach($field in $script:content.Keys){$wrong[$field]=$script:content[$field]};$wrong.expectedDraftRevision=0
        $null=Invoke-Clinical 'POST' "$doctorPath/complete" 'doctor' $wrong 409
    }
    Invoke-Gate 'complete-and-draft-disappearance' {
        $body=@{};foreach($field in $script:content.Keys){$body[$field]=$script:content[$field]};$body.expectedDraftRevision=1
        $script:record=Invoke-Clinical 'POST' "$doctorPath/complete" 'doctor' $body
        $script:recordId=$script:record.id;Require (Is-CanonicalUuid $script:recordId)
        Require ($script:record.appointmentId -ceq $script:apt -and $script:record.doctorId -ceq $script:accounts.doctor.id -and $script:record.patientId -ceq $script:accounts.patient.id)
        Require (Content-Same $script:record $script:content)
        $workspace=Invoke-Clinical 'GET' "$doctorPath/clinical-workspace" 'doctor' $null
        Require ($workspace.status -ceq 'COMPLETED' -and $null -eq $workspace.draft -and $workspace.recordId -ceq $script:recordId)
        $script:originalSnapshot=Snapshot (Invoke-Clinical 'GET' "$patientPath/record" 'patient' $null)
        $script:orderSnapshot=Snapshot (Invoke-Clinical 'GET' "$patientPath/order" 'patient' $null)
    }
    $recordPath="doctor/records/$script:recordId"
    Invoke-Gate 'parent-authorization-403' {
        foreach($name in @('otherPatient','otherDoctor','crossDoctor','hospital','platform')){
            $null=Invoke-Clinical 'GET' "$patientPath/complaint" $name $null 403
            $null=Invoke-Clinical 'PUT' "$patientPath/complaint" $name @{description=$script:clinicalCanary;expectedRevision=1} 403
            $null=Invoke-Clinical 'GET' "$patientPath/record/details" $name $null 403
            $null=Invoke-Clinical 'GET' "$patientPath/record/corrections?page=invalid&pageSize=6" $name $null 403
        }
        foreach($name in @('patient','otherPatient','otherDoctor','crossDoctor','hospital','platform')){
            $null=Invoke-Clinical 'GET' $recordPath $name $null 403
            $null=Invoke-Clinical 'GET' "$recordPath/corrections?page=invalid&pageSize=6" $name $null 403
            $null=Invoke-Clinical 'POST' "$recordPath/corrections" $name @{expectedRevision=0;operationKey=[guid]::NewGuid().ToString();after=$script:content;reason=$script:clinicalCanary} 403
        }
    }
    Invoke-Gate 'correction-chain' {
        $first=Invoke-Clinical 'GET' $recordPath 'doctor' $null
        Require ($first.currentRevision -eq 0 -and (Content-Same $first.original $script:content) -and (Content-Same $first.effective $script:content))
        $script:retryBody=@{expectedRevision=0;operationKey=[guid]::NewGuid().ToString();after=(New-Content 1);reason=$script:clinicalCanary+':reason-1'}
        # Intentionally discard the successful committed response. Recovery below
        # must locate the original history item, even after a newer correction.
        $null=Invoke-Clinical 'POST' "$recordPath/corrections" 'doctor' $script:retryBody
        $history=Invoke-Clinical 'GET' "$patientPath/record/corrections" 'patient' $null
        Require ($history.total -eq 1 -and @($history.items).Count -eq 1)
        $script:lostItem=$history.items[0]
        Assert-CorrectionResponse $script:lostItem $script:retryBody $script:content
        $script:expectedCorrections=New-Object Collections.ArrayList
        [void]$script:expectedCorrections.Add((New-ExpectedCorrection $script:lostItem $script:retryBody $script:content))
        $script:secondRequest=@{expectedRevision=1;operationKey=[guid]::NewGuid().ToString();after=(New-Content 2);reason=$script:clinicalCanary+':reason-2'}
        $script:second=Invoke-Clinical 'POST' "$recordPath/corrections" 'doctor' $script:secondRequest
        Assert-CorrectionResponse $script:second $script:secondRequest $script:retryBody.after
        Require ($script:second.id -cne $script:lostItem.id)
        [void]$script:expectedCorrections.Add((New-ExpectedCorrection $script:second $script:secondRequest $script:retryBody.after))
        $details=Invoke-Clinical 'GET' $recordPath 'doctor' $null
        Require ($details.currentRevision -eq 2 -and (Content-Same $details.original $script:content) -and (Content-Same $details.effective $script:secondRequest.after) -and -not (Content-Same $details.original $details.effective))
    }
    Invoke-Gate 'operation-key-lost-response-retry' {
        $retry=Invoke-Clinical 'POST' "$recordPath/corrections" 'doctor' $script:retryBody
        Assert-PersistedCorrection $retry $script:expectedCorrections[0]
        $history=Invoke-Clinical 'GET' "$patientPath/record/corrections" 'patient' $null
        Require ($history.total -eq 2);$facts.retryRecovered=$true
    }
    Invoke-Gate 'operation-key-variant-conflict' {
        $variant=@{expectedRevision=0;operationKey=$script:retryBody.operationKey;after=$script:retryBody.after;reason=$script:clinicalCanary+':variant'}
        $null=Invoke-Clinical 'POST' "$recordPath/corrections" 'doctor' $variant 409
    }
    Invoke-Gate 'patient-history-pagination' {
        for($revision=3;$revision -le 6;$revision++){
            $request=@{expectedRevision=$revision-1;operationKey=[guid]::NewGuid().ToString();after=(New-Content $revision);reason=$script:clinicalCanary+':reason-'+$revision}
            $expectedBefore=$script:expectedCorrections[$revision-2].after
            $item=Invoke-Clinical 'POST' "$recordPath/corrections" 'doctor' $request
            Assert-CorrectionResponse $item $request $expectedBefore
            Require ($item.id -cnotin @($script:expectedCorrections|ForEach-Object{$_.id}))
            [void]$script:expectedCorrections.Add((New-ExpectedCorrection $item $request $expectedBefore))
        }
        $page=Invoke-Clinical 'GET' "$patientPath/record/corrections" 'patient' $null
        Require ($page.total -eq 6 -and $page.page -eq 1 -and $page.pageSize -eq 5 -and @($page.items).Count -eq 5)
        for($i=0;$i -lt 5;$i++){Assert-PersistedCorrection $page.items[$i] $script:expectedCorrections[$i]}
        $max=Invoke-Clinical 'GET' "$patientPath/record/corrections?page=1&pageSize=5" 'patient' $null
        Require ((Snapshot $max) -ceq (Snapshot $page))
        $last=Invoke-Clinical 'GET' "$patientPath/record/corrections?page=2&pageSize=5" 'patient' $null
        Require ($last.total -eq 6 -and $last.page -eq 2 -and $last.pageSize -eq 5 -and @($last.items).Count -eq 1)
        Assert-PersistedCorrection $last.items[0] $script:expectedCorrections[5]
        $persisted=@($page.items)+@($last.items);$prior=$script:content
        for($i=0;$i -lt 6;$i++){
            Assert-PersistedCorrection $persisted[$i] $script:expectedCorrections[$i]
            Require (Content-Same $persisted[$i].before $prior)
            $prior=$script:expectedCorrections[$i].after
        }
        $null=Invoke-Clinical 'GET' "$patientPath/record/corrections?pageSize=6" 'patient' $null 400
        $patientDetails=Invoke-Clinical 'GET' "$patientPath/record/details" 'patient' $null
        Require ($patientDetails.currentRevision -eq 6 -and (Content-Same $patientDetails.original $script:content) -and (Content-Same $patientDetails.effective $script:expectedCorrections[5].after))
        $facts.historyChainValid=$true
    }
    Invoke-Gate 'original-and-order-unchanged' {
        $facts.originalUnchanged=((Snapshot (Invoke-Clinical 'GET' "$patientPath/record" 'patient' $null)) -ceq $script:originalSnapshot)
        $facts.orderUnchanged=((Snapshot (Invoke-Clinical 'GET' "$patientPath/order" 'patient' $null)) -ceq $script:orderSnapshot)
        Require ($facts.originalUnchanged -and $facts.orderUnchanged)
    }
    Invoke-Gate 'completed-complaint-readonly' {
        $read=Invoke-Clinical 'GET' "$patientPath/complaint" 'patient' $null
        Require (-not $read.writable)
        $null=Invoke-Clinical 'PUT' "$patientPath/complaint" 'patient' @{description=$script:clinicalCanary+':denied';expectedRevision=$read.revision} 409
    }
    if($script:accounts.canceledAppointmentId){
        Invoke-Gate 'canceled-complaint-readonly' {
            $id=$script:accounts.canceledAppointmentId
            $rows=@(Invoke-Clinical 'GET' 'patient/appointments' 'patient' $null)
            $match=@($rows|Where-Object{$_.id -ceq $id})
            Require ($match.Count -eq 1 -and $match[0].status -ceq 'CANCELED' -and $match[0].doctorId -ceq $script:accounts.doctor.id -and $match[0].tenantId -ceq $script:accounts.doctor.tenantId)
            $read=Invoke-Clinical 'GET' "patient/appointments/$id/complaint" 'patient' $null
            Require (-not $read.writable)
            $null=Invoke-Clinical 'PUT' "patient/appointments/$id/complaint" 'patient' @{description=$script:clinicalCanary+':denied';expectedRevision=$read.revision} 409
        }
    }
    Invoke-Gate 'safe-errors' {Require ($script:errorsSafe -and $script:errorCount -gt 10)}
    Invoke-Gate 'audit-clinical-redaction' {
        $pageNumber=1;$seen=0;$total=$null;$observedTypes=@();$observedNoChange=$false
        do{
            Require ($pageNumber -le 100)
            $audit=Invoke-Clinical 'GET' "platform/audit-events?page=$pageNumber&size=100" 'platform' $null
            Require ($audit.total -ge 0 -and @($audit.items).Count -le 100)
            if($null -eq $total){$total=[long]$audit.total}else{Require ($total -eq [long]$audit.total)}
            Assert-NoSecret (Snapshot $audit)
            Assert-NoSecretValue $audit
            foreach($item in $audit.items){$observedTypes+=$item.eventType;if($item.result -ceq 'NO_CHANGE'){$observedNoChange=$true}}
            $seen+=@($audit.items).Count
            Require ($seen -le $total -and ($seen -eq $total -or @($audit.items).Count -gt 0))
            $pageNumber++
        }while($seen -lt $total)
        foreach($type in @('APPOINTMENT_COMPLAINT_UPDATE','CONSULTATION_DRAFT_SAVE','CONSULTATION_COMPLETE','RECORD_CORRECTION_APPEND')){Require ($type -in $observedTypes)}
        Require $observedNoChange
    }
    # Require both container log directories and the creator's four launcher
    # captures. A missing set stays unverified; a failed read cannot become clean.
    $script:activeGate='container-log-redaction'
    Assert-ClinicalInstance
    $paasLog=Join-Path $script:fixture.paas.dataDirectory 'log\server.log'
    $paasLogs=Split-Path $paasLog -Parent
    $portalLogs=if($script:fixture.portal){Join-Path $script:fixture.portal.dataDirectory 'logs'}else{$null}
    if($portalLogs){Assert-OwnedAttachmentsFixture $script:fixture 'portal';[void](Assert-ClinicalPath $portalLogs $false)}
    $launcherPaths=@('paas.out.log','paas.err.log','portal.out.log','portal.err.log'|ForEach-Object{Join-Path $script:runtimeRoot $_})
    $launchersPresent=(@($launcherPaths|Where-Object{-not (Test-Path -LiteralPath $_ -PathType Leaf)}).Count -eq 0)
    if($launchersPresent -and (Test-Path -LiteralPath $paasLog -PathType Leaf) -and $portalLogs -and (Test-Path -LiteralPath $portalLogs -PathType Container)){
        [void](Assert-ClinicalPath $paasLogs)
        $portalLogFiles=@(Get-ChildItem -LiteralPath $portalLogs -File)
        $paasLogFiles=@(Get-ChildItem -LiteralPath $paasLogs -File)
        $captureFiles=@(Get-ChildItem -LiteralPath $script:runtimeRoot -File|Where-Object{$_.Name -match '(?i)(paas|portal).*\.log(?:\..*)?$'})
        if($portalLogFiles.Count -gt 0){
            Invoke-Gate 'container-log-redaction' {
                Assert-ClinicalInstance;Assert-OwnedAttachmentsFixture $script:fixture 'portal'
                [void](Assert-ClinicalPath $portalLogs)
                $sources=New-Object Collections.ArrayList
                foreach($file in $paasLogFiles){[void]$sources.Add(@{path=$file.FullName;role='paasFiles'})}
                foreach($file in $portalLogFiles){
                    $role=if($file.Name -cmatch '^localhost_access_log\..+\.txt$'){'portalAccessFiles'}else{'portalOtherFiles'}
                    [void]$sources.Add(@{path=$file.FullName;role=$role})
                }
                foreach($file in $captureFiles){
                    $isPaas=$file.Name -match '(?i)paas';$isPortal=$file.Name -match '(?i)portal'
                    Require ($isPaas -xor $isPortal)
                    $role=if($isPaas){'rootPaasCaptures'}else{'rootPortalCaptures'}
                    [void]$sources.Add(@{path=$file.FullName;role=$role})
                }
                if($script:hasDeclaredBoot){
                    foreach($component in @('paas','portal')){
                        foreach($streamName in @('stdout','stderr')){
                            $role=if($component -ceq 'paas'){'bootPaasCaptures'}else{'bootPortalCaptures'}
                            [void]$sources.Add(@{path=(Join-Path $script:fixture.clinical.bootEvidence ($component+'.'+$streamName+'.log'));role=$role})
                        }
                    }
                }
                Require ($sources.Count -le 100)
                $seenPaths=@{}
                foreach($source in $sources){
                    $logPath=$source.path;Require (-not $seenPaths.ContainsKey($logPath));$seenPaths[$logPath]=$true
                    Assert-ClinicalInstance
                    Assert-OwnedAttachmentsFixture $script:fixture 'portal'
                    [void](Assert-ClinicalPath $logPath)
                    Require ((Get-Item -LiteralPath $logPath).Length -le 16777216)
                    Assert-NoSecret (Read-ClinicalLogText $logPath $script:logEncodings[$source.role] $source.role)
                    Assert-ClinicalInstance
                }
            }
        }
    }
    $completeDriverRun=$true
}catch{
    if($script:activeGate -in $required -and $gates[$script:activeGate] -ne 'failed'){Set-Gate $script:activeGate 'failed'}
}
try{Write-ClinicalSummary}catch{
    # Lost ownership means even the failure summary is forbidden.
    Write-Output 'CLINICAL VERIFICATION ABORTED: ownership or evidence precondition failed'
    exit 3
}
if(-not $summaryWritten){Write-Output 'CLINICAL VERIFICATION ABORTED';exit 3}
if(@($gates.Values|Where-Object{$_ -eq 'failed'}).Count){Write-Output 'CLINICAL VERIFICATION FAILED';exit 1}
if(-not $completeDriverRun -or @($gates.Values|Where-Object{$_ -ne 'passed'}).Count){Write-Output 'CLINICAL VERIFICATION INCOMPLETE: required gates remain unverified';exit 4}
Write-Output 'CLINICAL VERIFICATION PASSED';exit 0
