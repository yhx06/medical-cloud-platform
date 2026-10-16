param(
    [Parameter(Mandatory=$true)][string]$FixturePath,
    [Parameter(Mandatory=$true)][string]$EvidenceRoot
)
# This entry point measures only the named patient content cases against a supplied
# owned fixture. It does not certify section 12 gate 1 or import previous results.
$ErrorActionPreference='Stop'
try {
    $repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    . (Join-Path $repo 'course/deploy/tests/attachments-fixture-ownership.ps1')
    $fixtureFull=[IO.Path]::GetFullPath($FixturePath)
    if(-not (Test-Path -LiteralPath $fixtureFull -PathType Leaf) -or
        ((Get-Item -LiteralPath $fixtureFull -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'FIXTURE_REFUSED'}
    $fixture=[IO.File]::ReadAllText($fixtureFull)|ConvertFrom-Json
    function Assert-ContentOwnership {
        foreach($part in @('mysql','storage','portal','paas')){Assert-OwnedAttachmentsFixture $script:fixture $part}
    }
    function Assert-ContentTarget([string]$Uri,[bool]$Storage=$false) {
        Assert-ContentOwnership
        $target=[uri]$Uri
        $port=$script:fixture.paas.port
        if($Storage){$port=$script:fixture.storage.port}
        if(-not $target.IsAbsoluteUri -or $target.Scheme -ne 'http' -or $target.Host -ne '127.0.0.1' -or
            $target.Port -ne $port -or $target.UserInfo -or $target.Fragment){throw 'TARGET_REFUSED'}
        if($Storage){
            $prefix='/'+$script:fixture.storage.bucket+'/'
            if(-not $target.AbsolutePath.StartsWith($prefix,[StringComparison]::Ordinal)){throw 'TARGET_REFUSED'}
        } elseif(-not $target.AbsolutePath.StartsWith('/paas/api/',[StringComparison]::Ordinal)){throw 'TARGET_REFUSED'}
    }
    Assert-ContentOwnership
    $api=([string]$fixture.paas.url).TrimEnd('/')+'/paas/api/'
    Assert-ContentTarget ($api+'auth/login')
    $evidenceFull=[IO.Path]::GetFullPath($EvidenceRoot)
    # Reject reparse ancestors as well as the leaf. Each run writes a new UUID
    # directory, preserving previous evidence even when the same root is supplied.
    $ancestor=$evidenceFull
    while($ancestor){
        if(Test-Path -LiteralPath $ancestor){if((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'EVIDENCE_REFUSED'}}
        $ancestor=Split-Path $ancestor -Parent
    }
    $run=Join-Path $evidenceFull ('content-'+[guid]::NewGuid().ToString('N'))
    Assert-ContentOwnership
    [void][IO.Directory]::CreateDirectory($run)
    $required=@('patient-jpg','patient-png','patient-pdf','exact-ten-mib','malformed-pdf','damaged-image','spoofed-type','digest-mismatch','empty-descriptor','oversize-descriptor')
    $passed=New-Object 'System.Collections.Generic.List[string]'
    $failed=New-Object 'System.Collections.Generic.List[string]'
    $executed=New-Object 'System.Collections.Generic.List[string]'
    $cases=New-Object 'System.Collections.Generic.List[object]'
    $ownedFiles=New-Object 'System.Collections.Generic.List[string]'
    $tokens=@{}
    function Require($Value){if(-not $Value){throw 'CASE_FAILED'}}
    function Sha([byte[]]$Bytes){
        $hash=[Security.Cryptography.SHA256]::Create()
        try{return ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
    }
    function Request([string]$Method,[string]$Uri,[string]$User,$Body,[byte[]]$Bytes,[bool]$Storage=$false){
        # Validate before login recursion too; never send credentials to a foreign target.
        Assert-ContentTarget $Uri $Storage
        $headers=@{}
        if($User){
            if(-not $tokens[$User]){
                $login=Request POST ($api+'auth/login') $null @{username=$User;password='Demo@123456'}
                Require ($login.status -eq 200 -and $login.body.data.accessToken)
                $tokens[$User]=$login.body.data.accessToken
            }
            $headers.Authorization='Bearer '+$tokens[$User]
        }
        $args=@{Uri=$Uri;Method=$Method;Headers=$headers;TimeoutSec=90;MaximumRedirection=0;UseBasicParsing=$true;ErrorAction='Stop'}
        if($null -ne $Bytes){$args.Body=$Bytes;$args.ContentType='application/octet-stream'}
        elseif($null -ne $Body){$args.Body=$Body|ConvertTo-Json -Depth 6;$args.ContentType='application/json'}
        # Every GET/POST/PUT, including signed GET, gets a fresh ownership check.
        Assert-ContentTarget $Uri $Storage
        try{
            $response=Invoke-WebRequest @args
            $parsed=$null;try{$parsed=$response.Content|ConvertFrom-Json}catch{}
            $raw=[byte[]]@()
            if($Storage){
                Require ($null -ne $response.RawContentStream)
                $stream=New-Object IO.MemoryStream
                try{$response.RawContentStream.Position=0;$response.RawContentStream.CopyTo($stream);$raw=$stream.ToArray()}finally{$stream.Dispose();$response.RawContentStream.Dispose()}
            }
            return @{status=[int]$response.StatusCode;body=$parsed;bytes=$raw}
        }catch{
            $status=0;if($_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode}
            return @{status=$status;body=$null;bytes=[byte[]]@()}
        }
    }
    function Api([string]$Method,[string]$Path,[string]$User,$Body,[byte[]]$Bytes){return Request $Method ($api+$Path) $User $Body $Bytes}
    function Write-ContentProgress {
        # A checkpoint is observational only. A new run always starts with empty
        # result lists; neither this file nor any older summary is read as proof.
        $progress=[ordered]@{
            executed=@($executed.ToArray());passed=@($passed.ToArray());failed=@($failed.ToArray())
            unexecuted=@($required|Where-Object {$executed -notcontains $_});complete=$false
        }
        $destination=Join-Path $run 'content-progress.json'
        Assert-ContentOwnership
        [IO.File]::WriteAllText($destination,($progress|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
    }
    function Case([string]$Name,[scriptblock]$Body){
        if($required -notcontains $Name){throw 'CASE_NAME_REFUSED'}
        $executed.Add($Name)
        $succeeded=$false
        try{& $Body|Out-Null;$passed.Add($Name);$cases.Add(@{name=$Name;passed=$true});$succeeded=$true}
        catch{$failed.Add($Name);$cases.Add(@{name=$Name;passed=$false})}
        # Keep writes outside the case catch: a lost checkpoint/ownership must
        # abort the remaining cases and must never print a success for this case.
        Write-ContentProgress
        if($succeeded){Write-Output ('CONTENT CASE '+$Name+' PASSED')}
        else{Write-Output ('CONTENT CASE '+$Name+' FAILED')}
    }
    function Image([string]$Kind){
        Add-Type -AssemblyName System.Drawing
        $bitmap=New-Object Drawing.Bitmap 2,2;$stream=New-Object IO.MemoryStream
        try{
            $format=[Drawing.Imaging.ImageFormat]::Png;if($Kind -eq 'jpg'){$format=[Drawing.Imaging.ImageFormat]::Jpeg}
            $bitmap.Save($stream,$format);return ,$stream.ToArray()
        }finally{$bitmap.Dispose();$stream.Dispose()}
    }
    function Pdf {
        # A complete one-page PDF with independently calculated xref offsets.
        $text="%PDF-1.4`n";$offsets=@(0)
        $objects=@('<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 20 20] >>')
        for($i=0;$i -lt $objects.Count;$i++){$offsets+=[Text.Encoding]::ASCII.GetByteCount($text);$text+=($i+1).ToString()+" 0 obj`n"+$objects[$i]+"`nendobj`n"}
        $xref=[Text.Encoding]::ASCII.GetByteCount($text);$text+="xref`n0 4`n0000000000 65535 f `n"
        foreach($offset in $offsets[1..3]){$text+=$offset.ToString('0000000000')+" 00000 n `n"}
        $text+="trailer`n<< /Size 4 /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n"
        return ,[Text.Encoding]::ASCII.GetBytes($text)
    }
    function Descriptor([byte[]]$Bytes,[string]$Name,[string]$Mime){
        return @{uploadKey=[guid]::NewGuid().ToString();fileName=$Name;contentType=$Mime;sizeBytes=$Bytes.Length;sha256=(Sha $Bytes)}
    }
    function Upload([byte[]]$Bytes,[string]$Name,[string]$Mime,[int]$Expected,[bool]$WrongDigest=$false){
        $descriptor=Descriptor $Bytes $Name $Mime
        if($WrongDigest){$descriptor.sha256=('0'*64);if($descriptor.sha256 -eq (Sha $Bytes)){$descriptor.sha256=('1'*64)}}
        $prepare=Api POST $preparePath patient_demo $descriptor
        Require ($prepare.status -eq 200)
        $id=[string]$prepare.body.data.uploadId
        Require ($id -match '^[0-9a-fA-F-]{36}$')
        $result=Api PUT ('files/uploads/'+$id+'/content') patient_demo $null $Bytes
        # Track any AVAILABLE response even when a negative case was wrongly accepted.
        if($result.status -eq 200){$ownedFiles.Add($id)}
        Require ($result.status -eq $Expected)
        if($Expected -eq 200){
            $link=Api POST ('files/'+$id+'/download-url') patient_demo
            Require ($link.status -eq 200 -and $link.body.data.url)
            $download=Request GET $link.body.data.url $null $null $null $true
            Require ($download.status -eq 200 -and $download.bytes.Length -eq $Bytes.Length -and (Sha $download.bytes) -eq (Sha $Bytes))
            $delete=Api POST ('patient/files/'+$id+'/delete') patient_demo
            Require ($delete.status -eq 200)
            [void]$ownedFiles.Remove($id)
        }
    }
    $setup=$false
    try{
        $slots=Api GET 'hospital/slots' hospital_a
        Require ($slots.status -eq 200)
        $day=(Get-Date).AddDays(90).Date
        for($i=0;$i -lt 201;$i++){
            if(@($slots.body.data|Where-Object {$_.doctorId -eq '20000000-0000-0000-0000-000000000001' -and $_.date -eq $day.ToString('yyyy-MM-dd') -and $_.startTime -eq '09:00:00'}).Count -eq 0){break}
            $day=$day.AddDays(1)
        }
        Require ($i -lt 201)
        $slot=Api POST 'hospital/slots' hospital_a @{departmentId='11111111-1111-1111-1111-111111111112';doctorId='20000000-0000-0000-0000-000000000001';date=$day.ToString('yyyy-MM-dd');startTime='09:00:00';endTime='10:00:00';capacity=1;fee=39;status='PUBLISHED'}
        Require ($slot.status -eq 200)
        $slotId=[string]$slot.body.data.id
        Require ($slotId -match '^[0-9a-fA-F-]{36}$' -and @($slots.body.data|Where-Object {$_.id -eq $slotId}).Count -eq 0 -and $slotId -notmatch '^aaaaaaa')
        $appointment=Api POST 'patient/appointments' patient_demo @{slotId=$slotId}
        Require ($appointment.status -eq 200 -and $appointment.body.data.status -eq 'BOOKED')
        $appointmentId=[string]$appointment.body.data.id
        Require ($appointmentId -match '^[0-9a-fA-F-]{36}$')
        $preparePath='patient/appointments/'+$appointmentId+'/files/uploads'
        $setup=$true
        $jpg=Image jpg;$png=Image png;$pdf=Pdf
        $exact=New-Object byte[] 10485760;[Array]::Copy($jpg,$exact,$jpg.Length)
        # JPEG permits trailing bytes; preserve a complete decodable small image
        # and pad the encoded body to the exact descriptor byte boundary.
        Case patient-jpg {Upload $jpg 'positive.jpg' 'image/jpeg' 200}
        Case patient-png {Upload $png 'positive.png' 'image/png' 200}
        Case patient-pdf {Upload $pdf 'positive.pdf' 'application/pdf' 200}
        Case exact-ten-mib {Upload $exact 'exact.jpg' 'image/jpeg' 200}
        Case malformed-pdf {Upload ([Text.Encoding]::ASCII.GetBytes('%PDF-1.4 broken')) 'malformed.pdf' 'application/pdf' 400}
        Case damaged-image {Upload ([byte[]]@(137,80,78,71,13,10,26,10,0,0,0,0)) 'damaged.png' 'image/png' 400}
        Case spoofed-type {Upload $jpg 'spoofed.png' 'image/png' 415}
        Case digest-mismatch {Upload $png 'digest-mismatch.png' 'image/png' 400 $true}
        Case empty-descriptor {
            $d=Descriptor ([byte[]]@()) 'empty.png' 'image/png';$r=Api POST $preparePath patient_demo $d;Require ($r.status -eq 400)
        }
        Case oversize-descriptor {
            $d=Descriptor $jpg 'oversize.jpg' 'image/jpeg';$d.sizeBytes=10485761;$r=Api POST $preparePath patient_demo $d;Require ($r.status -eq 413)
        }
    }catch{$failed.Add('setup-or-driver')}
    finally{
        foreach($id in @($ownedFiles.ToArray())){
            try{$r=Api POST ('patient/files/'+$id+'/delete') patient_demo;Require ($r.status -eq 200);[void]$ownedFiles.Remove($id)}catch{$failed.Add('cleanup')}
        }
    }
    $unexecuted=@($required|Where-Object {$executed -notcontains $_})
    $summary=[ordered]@{required=$required;executed=@($executed.ToArray());passed=@($passed.ToArray());failed=@($failed.ToArray());unexecuted=$unexecuted;cases=@($cases.ToArray());complete=$true;bookedFixturePrepared=$setup;ownAvailableFilesCleaned=($ownedFiles.Count -eq 0);fullGate1Accepted=$false;realHttpInvocation=$true;limitations=@('Doctor completed-record formats unverified','Restricted PDF cases unverified','Changed limits unverified','Image decompression limits unverified','Other section 12 gates unverified')}
    Assert-ContentOwnership
    [IO.File]::WriteAllText((Join-Path $run 'content-summary.json'),($summary|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    if($failed.Count -gt 0 -or $unexecuted.Count -gt 0){Write-Output 'CONTENT VERIFICATION FAILED';exit 1}
    Write-Output 'CONTENT CASES PASSED; FULL GATE1 UNVERIFIED';exit 0
}catch{Write-Output 'CONTENT VERIFICATION FAILED: FIXTURE_OR_DRIVER_REFUSED';exit 3}
