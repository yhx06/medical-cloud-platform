param([string]$Scenario = 'all')
$ErrorActionPreference = 'Stop'
$verify = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\scripts\verify-clinical-supplements.ps1'))
if (-not (Test-Path -LiteralPath $verify -PathType Leaf)) {
    Write-Output 'FAIL: missingRequiredGateCannotPass: clinical acceptance entry point is missing'
    exit 1
}
# Keep orchestration, path checks, guards, assertions, registry, evidence, and exit real.
# Replace only the external HTTP transport by its AST extent; OS observations are
# command-boundary doubles. No production mock mode exists.
if (-not ('ClinicalAcceptanceHost' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Globalization;
using System.IO;
using System.Management.Automation.Host;
public sealed class ClinicalAcceptanceHost : PSHost {
    public int ExitCode = -1;
    public override string Name { get { return "ClinicalAcceptanceTests"; } }
    public override Version Version { get { return new Version(1,0); } }
    private readonly Guid id = Guid.NewGuid();
    public override Guid InstanceId { get { return id; } }
    public override PSHostUserInterface UI { get { return null; } }
    public override CultureInfo CurrentCulture { get { return CultureInfo.InvariantCulture; } }
    public override CultureInfo CurrentUICulture { get { return CultureInfo.InvariantCulture; } }
    public override void SetShouldExit(int code) { ExitCode = code; }
    public override void EnterNestedPrompt() { throw new NotSupportedException(); }
    public override void ExitNestedPrompt() { }
    public override void NotifyBeginApplication() { }
    public override void NotifyEndApplication() { }
}
// Deterministic external-stream boundary: length is small at open, then reads
// grow one byte over16MiB. The production bounded reader/decoder remain real.
public sealed class ClinicalGrowingLogStream : Stream {
    private long position;
    private const long total = 16777217;
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return false; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return Math.Max(1, position); } }
    public override long Position { get { return position; } set { throw new NotSupportedException(); } }
    public override int Read(byte[] buffer, int offset, int count) {
        int take = (int)Math.Min(count, total-position);
        for (int i=0; i<take; i++) buffer[offset+i]=65;
        position += take; return take;
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer,int offset,int count) { throw new NotSupportedException(); }
}
// Model append after the zero-byte Read: Length then proves bytes that the
// completed snapshot did not contain. Keep the production reader/gate real.
public sealed class ClinicalEofAppendLogStream : Stream {
    private bool delivered;
    private bool eof;
    private readonly byte[] appendedSecret;
    private readonly bool overLimit;
    public ClinicalEofAppendLogStream(string secret, bool overLimit) {
        appendedSecret = System.Text.Encoding.UTF8.GetBytes(secret);
        this.overLimit = overLimit;
    }
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return false; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return !eof ? 1 : (overLimit ? 16777217 : 1 + appendedSecret.Length); } }
    public override long Position { get { return delivered ? 1 : 0; } set { throw new NotSupportedException(); } }
    public override int Read(byte[] buffer, int offset, int count) {
        if (!delivered) { buffer[offset] = 65; delivered = true; return 1; }
        eof = true; return 0;
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer,int offset,int count) { throw new NotSupportedException(); }
}
'@
}
$harness = {
    param($Verify,$Root,$Case)
    $ErrorActionPreference='Stop'
    $global:testCase=$Case; $global:requests=0; $global:mutations=0
    $global:guardSeen=$false; $global:requestUnguarded=$false
    $global:canary='CLINICAL-CANARY-' + [guid]::NewGuid().ToString('N')
    $global:retryRecovered=$false; $global:retryAfterAdvance=$false
    $global:apt='11111111-1111-4111-8111-111111111111'
    $global:rid='22222222-2222-4222-8222-222222222222'
    $global:owner='0123456789abcdef0123456789abcdef'
    [void][IO.Directory]::CreateDirectory($Root)
    function global:Set-ClinicalTestPublicAcl([string]$Path){
        $isDirectory=(Get-Item -LiteralPath $Path).PSIsContainer
        $accessSection=[Security.AccessControl.AccessControlSections]::Access
        $publicAcl=if($isDirectory){[IO.Directory]::GetAccessControl($Path,$accessSection)}else{[IO.File]::GetAccessControl($Path,$accessSection)}
        $publicSid=New-Object Security.Principal.SecurityIdentifier('S-1-1-0')
        $publicRule=New-Object Security.AccessControl.FileSystemAccessRule($publicSid,'ReadAndExecute','Allow')
        $publicAcl.AddAccessRule($publicRule)
        if($isDirectory){[IO.Directory]::SetAccessControl($Path,$publicAcl)}else{[IO.File]::SetAccessControl($Path,$publicAcl)}
    }
    # A broad runtime with a private clinical child models the actual fixture.
    Set-ClinicalTestPublicAcl $Root
    $global:privateRoot=Join-Path $Root 'clinical-application';[void][IO.Directory]::CreateDirectory($global:privateRoot)
    $privateAcl=New-Object Security.AccessControl.DirectorySecurity
    $privateAcl.SetAccessRuleProtection($true,$false)
    $privateSid=[Security.Principal.WindowsIdentity]::GetCurrent().User;$privateAcl.SetOwner($privateSid)
    $privateRule=New-Object Security.AccessControl.FileSystemAccessRule($privateSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $privateAcl.AddAccessRule($privateRule);Set-Acl -LiteralPath $global:privateRoot -AclObject $privateAcl
    $global:evidencePath=Join-Path $global:privateRoot 'evidence';[void][IO.Directory]::CreateDirectory($global:evidencePath)
    if($Case -eq 'public-evidence-outside-boundary'){$global:evidencePath=Join-Path $Root 'public-evidence';[void][IO.Directory]::CreateDirectory($global:evidencePath)}
    if($Case -eq 'public-evidence-ancestor'){
        $publicAncestor=Join-Path $global:privateRoot 'public-ancestor';[void][IO.Directory]::CreateDirectory($publicAncestor);Set-ClinicalTestPublicAcl $publicAncestor
        $global:evidencePath=Join-Path $publicAncestor 'evidence'
    }
    if($Case -eq 'public-evidence-target'){Set-ClinicalTestPublicAcl $global:evidencePath}
    [IO.File]::WriteAllText((Join-Path $Root 'owner.id'),$global:owner)
    $data=Join-Path $Root 'paas-data'; [void][IO.Directory]::CreateDirectory($data)
    $exe=Join-Path $Root 'java.exe'; [IO.File]::WriteAllText($exe,'test boundary only')
    $global:process=[pscustomobject]@{ProcessId=23459;CreationDate=[datetime]'2030-01-01T00:00:00Z';ExecutablePath=$exe;CommandLine=('"'+$exe+'" "'+$data+'" -Djboss.socket.binding.port-offset=10001')}
    $fixture=@{ownerId=$global:owner;runtimeRoot=$Root;paas=@{pid=23459;created=$global:process.CreationDate;executable=$exe;dataDirectory=$data;port=18081;managementPort=19991;url='http://127.0.0.1:18081'}}
    $portalData=Join-Path $Root 'portal-data';[void][IO.Directory]::CreateDirectory((Join-Path $portalData 'conf'))
    [IO.File]::WriteAllText((Join-Path $portalData 'conf\server.xml'),'<Server port="18083"><Service><Connector port="18082" address="127.0.0.1" /></Service></Server>')
    $global:portalProcess=[pscustomobject]@{ProcessId=23460;CreationDate=$global:process.CreationDate;ExecutablePath=$exe;CommandLine=('"'+$exe+'" "'+$portalData+'"')}
    $fixture.portal=@{pid=23460;created=$global:process.CreationDate;executable=$exe;dataDirectory=$portalData;port=18082;shutdownPort=18083;url='http://127.0.0.1:18082'}
    $mysqlData=Join-Path $Root 'mysql-data';[void][IO.Directory]::CreateDirectory($mysqlData)
    [IO.File]::WriteAllText((Join-Path $mysqlData 'auto.cnf'),"[auto]`nserver-uuid=77777777-7777-4777-8777-777777777777`n")
    $global:mysqlProcess=[pscustomobject]@{ProcessId=23461;CreationDate=$global:process.CreationDate;ExecutablePath=$exe;CommandLine=('"'+$exe+'" "'+$mysqlData+'" --port=45678')}
    $fixture.mysql=@{pid=23461;created=$global:process.CreationDate;executable=$exe;dataDirectory=$mysqlData;port=45678;host='127.0.0.1';serverUuid='77777777-7777-4777-8777-777777777777'}
    [void][IO.Directory]::CreateDirectory((Join-Path $data 'log'));[void][IO.Directory]::CreateDirectory((Join-Path $portalData 'logs'))
    if($Case -ne 'logs-unavailable'){
        [IO.File]::WriteAllText((Join-Path $data 'log\server.log'),'Safe container log')
        [IO.File]::WriteAllText((Join-Path $portalData 'logs\catalina.log'),'Safe portal log')
        foreach($capture in @('paas.out.log','paas.err.log','portal.out.log','portal.err.log')){[IO.File]::WriteAllText((Join-Path $Root $capture),'Safe launcher capture')}
    }
    $global:liveLogWriter=$null
    if($Case -in @('live-writer-sharing','unreadable-log')){
        $share=if($Case -eq 'unreadable-log'){[IO.FileShare]::None}else{[IO.FileShare]::Read}
        $global:liveLogWriter=[IO.File]::Open((Join-Path $data 'log\server.log'),[IO.FileMode]::Open,[IO.FileAccess]::Write,$share)
    }
    $encodingCases=@('declared-encodings-clean','gbk-password-log','latin1-password-access-log','invalid-utf8-without-policy','invalid-declared-gbk','bad-policy-version','missing-policy-role','unknown-policy-role','invalid-policy-encoding','latin1-wrong-role','wrong-policy-shape','policy-no-boot')
    $bootCases=@('boot-no-policy-clean','boot-only-canary','boot-no-policy-leak','boot-missing-file','boot-no-policy-missing','boot-foreign','boot-reparse-directory','boot-nonprivate','boot-file-directory','boot-public-file','boot-empty-source','boot-source-changed')
    if($Case -in ($encodingCases+$bootCases)){
        $global:bootLogRoot=Join-Path $global:privateRoot 'boot-encoding'
        if($Case -eq 'boot-foreign'){$global:bootLogRoot=Join-Path (Split-Path $Root -Parent) ('foreign-boot-'+[guid]::NewGuid().ToString('N'))}
        if($Case -eq 'boot-reparse-directory'){
            $actualBoot=Join-Path $global:privateRoot 'boot-real';[void][IO.Directory]::CreateDirectory($actualBoot)
            [void](New-Item -ItemType Junction -Path $global:bootLogRoot -Target $actualBoot)
        }else{[void][IO.Directory]::CreateDirectory($global:bootLogRoot)}
        foreach($capture in @('paas.stdout.log','paas.stderr.log','portal.stdout.log','portal.stderr.log')){
            if($capture -eq 'portal.stderr.log' -and $Case -in @('boot-missing-file','boot-no-policy-missing')){continue}
            $capturePath=Join-Path $global:bootLogRoot $capture
            if($capture -eq 'portal.stderr.log' -and $Case -eq 'boot-file-directory'){[void][IO.Directory]::CreateDirectory($capturePath)}
            else{[IO.File]::WriteAllText($capturePath,'Safe current capture')}
        }
        $policy=@{version=1;encodings=@{paasFiles='GBK';portalAccessFiles='ISO-8859-1';portalOtherFiles='UTF-8';rootPaasCaptures='GBK';rootPortalCaptures='GBK';bootPaasCaptures='GBK';bootPortalCaptures='GBK'}}
        $fixture.clinical=@{bootEvidence=$global:bootLogRoot;logPolicy=$policy}
        if($Case -in @('boot-no-policy-clean','boot-no-policy-leak','boot-no-policy-missing')){$fixture.clinical.Remove('logPolicy')}
        if($Case -eq 'boot-nonprivate'){Set-ClinicalTestPublicAcl $global:bootLogRoot}
        if($Case -eq 'boot-public-file'){Set-ClinicalTestPublicAcl (Join-Path $global:bootLogRoot 'portal.stderr.log')}
        if($Case -eq 'boot-empty-source'){$fixture.clinical.bootEvidence=''}
        switch($Case){
            'bad-policy-version'{$policy.version=2}
            'missing-policy-role'{$policy.encodings.Remove('portalOtherFiles')}
            'unknown-policy-role'{$policy.encodings.extraSource='UTF-8'}
            'invalid-policy-encoding'{$policy.encodings.bootPaasCaptures='UTF-16'}
            'latin1-wrong-role'{$policy.encodings.paasFiles='ISO-8859-1'}
            'wrong-policy-shape'{$fixture.clinical.logPolicy='invalid'}
            'policy-no-boot'{$fixture.clinical.Remove('bootEvidence')}
            'invalid-utf8-without-policy'{$fixture.clinical.Remove('logPolicy')}
        }
        $global:strictGbk=[Text.Encoding]::GetEncoding(936,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)
        $global:strictLatin1=[Text.Encoding]::GetEncoding(28591,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)
        if($Case -in @('declared-encodings-clean','gbk-password-log','latin1-password-access-log','invalid-utf8-without-policy')){
            $safeNonAscii='Safe '+[char]0x533b+[char]0x9662
            [IO.File]::WriteAllText((Join-Path $data 'log\server.log'),$safeNonAscii,$global:strictGbk)
            [IO.File]::WriteAllText((Join-Path $portalData 'logs\catalina.log'),$safeNonAscii,(New-Object Text.UTF8Encoding($false,$true)))
            [IO.File]::WriteAllText((Join-Path $portalData 'logs\localhost_access_log.2030-01-01.txt'),('Safe caf'+[char]0x00e9),$global:strictLatin1)
            foreach($capture in @('paas.out.log','paas.err.log','portal.out.log','portal.err.log')){[IO.File]::WriteAllText((Join-Path $Root $capture),$safeNonAscii,$global:strictGbk)}
            foreach($capture in @('paas.stdout.log','paas.stderr.log','portal.stdout.log','portal.stderr.log')){[IO.File]::WriteAllText((Join-Path $global:bootLogRoot $capture),$safeNonAscii,$global:strictGbk)}
        }
        if($Case -eq 'invalid-declared-gbk'){[IO.File]::WriteAllBytes((Join-Path $data 'log\server.log'),[byte[]]@(0x81))}
    }
    if ($Case -eq 'foreign-fixture') { $fixture.ownerId='ffffffffffffffffffffffffffffffff' }
    if ($Case -eq 'protected-port') { $fixture.paas.port=8180 }
    $fixturePath=Join-Path $Root 'fixture.json'; [IO.File]::WriteAllText($fixturePath,($fixture|ConvertTo-Json -Depth 8))
    $global:descriptorPath=$fixturePath
    if($Case -eq 'outside-descriptor'){
        $fixturePath=Join-Path (Split-Path $Root -Parent) ('descriptor-'+[guid]::NewGuid().ToString('N')+'.json')
        [IO.File]::WriteAllText($fixturePath,($fixture|ConvertTo-Json -Depth 8))
    }
    if($Case -eq 'reparse-descriptor'){
        $junction=Join-Path (Split-Path $Root -Parent) ('junction-'+[guid]::NewGuid().ToString('N'))
        [void](New-Item -ItemType Junction -Path $junction -Target $Root)
        $fixturePath=Join-Path $junction 'fixture.json'
    }
    $global:accountMap=@{}; $accounts=@{ownerId=$global:owner;appointmentId=$global:apt}
    if($Case -in @('legacy-prerequisite','legacy-wrong-record')){$accounts.legacyAppointmentId='44444444-4444-4444-8444-444444444444'}
    if($Case -eq 'canceled-prerequisite'){$accounts.canceledAppointmentId='55555555-5555-4555-8555-555555555555'}
    $index=1
    foreach ($name in @('patient','otherPatient','doctor','otherDoctor','crossDoctor','hospital','platform')) {
        $index++; $tenant=if ($name -eq 'crossDoctor') {'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'} elseif ($name -in @('patient','otherPatient','platform')) {$null} else {'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'}
        $accounts[$name]=@{username=$name;password='SYNTHETIC-PASSWORD';id=('00000000-0000-4000-8000-'+$index.ToString('000000000000'));tenantId=$tenant}
        if($Case -in @('escaped-password-error','escaped-password-audit')){$accounts[$name].password='synth"42\quoted'}
        if($Case -eq 'gbk-password-log'){$accounts[$name].password='synth'+[char]0x533b+[char]0x9662+'42'}
        if($Case -eq 'latin1-password-access-log'){$accounts[$name].password='synth'+[char]0x00e9+'42'}
        $global:accountMap[$name]=$accounts[$name]
    }
    $accountsDirectory=$global:privateRoot
    if($Case -eq 'nested-private-boundary'){
        $accountsDirectory=Join-Path $global:privateRoot 'inherited-private';[void][IO.Directory]::CreateDirectory($accountsDirectory)
        $global:evidencePath=Join-Path $accountsDirectory 'evidence';[void][IO.Directory]::CreateDirectory($global:evidencePath)
    }
    $global:accountsPath=Join-Path $accountsDirectory 'accounts.json'; [IO.File]::WriteAllText($global:accountsPath,($accounts|ConvertTo-Json -Depth 8))
    $acl=New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true,$false)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow')
    $acl.AddAccessRule($rule); Set-Acl -LiteralPath $accountsPath -AclObject $acl
    if($Case -eq 'public-accounts-target'){Set-ClinicalTestPublicAcl $global:accountsPath}
    function global:Get-CimInstance {
        param($ClassName,$Filter,$ErrorAction)
        $global:guardSeen=$true
        if ($global:testCase -eq 'revoke-next-request' -and $global:requests -ge 1) { return $null }
        if ($global:testCase -eq 'revoke-mysql-next-request' -and $global:requests -ge 1 -and $Filter -eq 'ProcessId=23461') { return $null }
        if($Filter -eq 'ProcessId=23460'){return $global:portalProcess}
        if($Filter -eq 'ProcessId=23461'){return $global:mysqlProcess}
        return $global:process
    }
    function global:Get-NetTCPConnection {
        param($LocalAddress,$LocalPort,$State,$ErrorAction)
        return [pscustomobject]@{OwningProcess=$(if($LocalPort -in @(18082,18083)){23460}elseif($LocalPort -eq 45678){23461}else{23459});LocalPort=$LocalPort}
    }
    $global:complaint=@{appointmentId=$global:apt;description=$null;updatedAt=$null;revision=0;writable=$true}
    $global:status='BOOKED'; $global:draft=$null; $global:original=$null
    $global:effective=$null; $global:corrections=New-Object Collections.ArrayList
    $global:keys=@{}; $global:order=$null
    $global:transport={
        param($Uri,$Method,$Token,$Json)
        if (-not $global:guardSeen) { $global:requestUnguarded=$true }
        $global:guardSeen=$false; $global:requests++
        if ($Method -ne 'GET') { $global:mutations++ }
        if ($global:testCase -eq 'transport-secret-error') { throw $global:canary }
        $path=([uri]$Uri).AbsolutePath -replace '^/paas/api/',''; $query=([uri]$Uri).Query
        $inputBody=if ($Json) {$Json|ConvertFrom-Json} else {$null}
        $role=$Token -replace '^token-','' -replace '-boundary$',''; $status=200; $result=$null
        if ($path -eq 'auth/login') {
            $name=$inputBody.username; $r=switch ($name) {'hospital' {'HOSPITAL_ADMIN'} 'platform' {'PLATFORM_ADMIN'} {$_ -in @('patient','otherPatient')} {'PATIENT'} default {'DOCTOR'}}
            $result=@{accessToken=('token-'+$name+'-boundary');username=$name;role=$r}
            if ($global:testCase -eq 'wrong-login-role') {$result.role='PLATFORM_ADMIN'}
            if ($global:testCase -eq 'revoke-private-boundary') {Set-ClinicalTestPublicAcl $global:privateRoot}
            if ($global:testCase -eq 'revoke-private-accounts') {Set-ClinicalTestPublicAcl $global:accountsPath}
            if ($global:testCase -eq 'boot-source-changed') {
                $changedFixture=[IO.File]::ReadAllText($global:descriptorPath)|ConvertFrom-Json
                $changedFixture.clinical.logPolicy.encodings.bootPaasCaptures='UTF-8'
                [IO.File]::WriteAllText($global:descriptorPath,($changedFixture|ConvertTo-Json -Depth 12))
            }
        } elseif (-not $Token) {$status=401
            if($global:testCase -eq 'unauth-bypass' -and $path -match '/complaint$'){$status=200;$result=@{revision=0}}
        } elseif ($path -eq 'platform/audit-events') {
            if($query -notmatch '^\?page=[1-9][0-9]*&size=100$'){throw 'Wrong audit query contract'}
            $auditItems=@('APPOINTMENT_COMPLAINT_UPDATE','CONSULTATION_DRAFT_SAVE','CONSULTATION_COMPLETE','RECORD_CORRECTION_APPEND'|ForEach-Object{@{eventType=$_;result='SUCCESS'}})
            $auditItems+=@{eventType='RECORD_CORRECTION_APPEND';result='NO_CHANGE'}
            $result=@{items=$auditItems;total=5;page=1;size=100;from='2030-01-01';to='2030-01-01';timezone='Asia/Shanghai'}
            if ($global:testCase -eq 'empty-audit') {$result.items=@();$result.total=0}
            if ($global:testCase -eq 'audit-leak') {$result.items=@(@{reason=$global:clinicalCanary}) ;$result.total=1}
            if ($global:testCase -eq 'escaped-password-audit') {$result.items[0].nested=@{values=@('safe',$global:accountMap.patient.password)}}
        } elseif ($path -eq 'doctor/appointments') {
            $result=@(@{id=$global:apt;patientId=$global:accountMap.patient.id;doctorId=$global:accountMap.doctor.id;tenantId=$global:accountMap.doctor.tenantId;status=$global:status})
            if($global:testCase -in @('legacy-prerequisite','legacy-wrong-record')){$result+=@{id='44444444-4444-4444-8444-444444444444';patientId=$global:accountMap.patient.id;doctorId=$global:accountMap.doctor.id;tenantId=$global:accountMap.doctor.tenantId;status='BOOKED'}}
        } elseif ($path -eq 'patient/appointments') {
            $result=@(@{id=$global:apt;doctorId=$global:accountMap.doctor.id;tenantId=$global:accountMap.doctor.tenantId;status=$global:status})
            if($global:testCase -eq 'canceled-prerequisite'){$result+=@{id='55555555-5555-4555-8555-555555555555';doctorId=$global:accountMap.doctor.id;tenantId=$global:accountMap.doctor.tenantId;status='CANCELED'}}
        } elseif ($path -match '^doctor/' -and $role -ne 'doctor') {$status=403
        } elseif ($path -match '^patient/' -and $role -ne 'patient') {$status=403
        } elseif($path -match '44444444-4444-4444-8444-444444444444'){
            if($path -match '/clinical-workspace$'){$result=@{appointmentId='44444444-4444-4444-8444-444444444444';status='BOOKED';draft=$null;recordId=$null;patientComplaint=@{revision=0;description=$null;updatedAt=$null}}}
            elseif($path -match '/order$'){$status=403}
            elseif($path -match '/start$'){$result='44444444-4444-4444-8444-444444444444'}
            elseif($path -match '/complete$'){
                $result=@{id='66666666-6666-4666-8666-666666666666';appointmentId='44444444-4444-4444-8444-444444444444';doctorId=$global:accountMap.doctor.id;patientId=$global:accountMap.patient.id;complaint=$null;diagnosis=$inputBody.diagnosis;advice=$inputBody.advice;notes=$null}
                if($global:testCase -eq 'legacy-wrong-record'){$result.appointmentId=$global:apt}
            }
            else{throw 'Unexpected legacy boundary'}
        } elseif($path -match '55555555-5555-4555-8555-555555555555/complaint$'){
            if($Method -eq 'PUT'){$status=409}else{$result=@{appointmentId='55555555-5555-4555-8555-555555555555';description=$null;revision=0;updatedAt=$null;writable=$false}}
        } elseif ($path -match '/complaint$') {
            if ($Method -eq 'PUT') {
                if ($global:status -eq 'COMPLETED' -or $inputBody.expectedRevision -ne $global:complaint.revision) {$status=409}
                elseif ($inputBody.description -cne $global:complaint.description) {
                    $global:complaint.description=$inputBody.description; $global:clinicalCanary=($inputBody.description -split ':',2)[0]
                    $global:complaint.revision++;$global:complaint.updatedAt='2030-01-01T08:00:00+08:00'
                }
            }
            $result=$global:complaint
        } elseif ($path -match '/clinical-workspace$') {
            $result=@{appointmentId=$global:apt;status=$global:status;patientComplaint=$global:complaint;draft=$global:draft;recordId=$(if($global:original){$global:rid}else{$null});currentRevision=$(if($global:draft){$global:draft.revision}else{0})}
        } elseif ($path -match '/start$') {$global:status='IN_PROGRESS';$result=$global:apt
        } elseif ($path -match '/draft$') {
            if ($inputBody.expectedRevision -ne $(if($global:draft){$global:draft.revision}else{0})) {$status=409}
            else {$global:draft=@{content=@{complaint=$inputBody.complaint;diagnosis=$inputBody.diagnosis;advice=$inputBody.advice;notes=$inputBody.notes};revision=1;savedAt='2030-01-01T08:00:00+08:00'};$result=$global:draft}
        } elseif ($path -match '/complete$') {
            if ($global:draft -and $inputBody.expectedDraftRevision -ne 1) {$status=409}
            else {
                $global:original=@{complaint=$inputBody.complaint;diagnosis=$inputBody.diagnosis;advice=$inputBody.advice;notes=$inputBody.notes};$global:effective=$global:original
                $global:draft=$null;$global:status='COMPLETED';$global:complaint.writable=$false
                $global:order=@{id='33333333-3333-4333-8333-333333333333';appointmentId=$global:apt;amount=50;status='UNPAID';paidAt=$null;businessWritable=$true}
                $result=@{id=$global:rid;appointmentId=$global:apt;patientId=$global:accountMap.patient.id;doctorId=$global:accountMap.doctor.id;complaint=$inputBody.complaint;diagnosis=$inputBody.diagnosis;advice=$inputBody.advice;notes=$inputBody.notes;completedAt='2030-01-01T08:00:00+08:00'}
            }
        } elseif ($path -match '/order$') {if($global:order){$result=$global:order}else{$status=403}
        } elseif ($path -match '/corrections$') {
            if ($Method -eq 'POST') {
                $key=$inputBody.operationKey
                if($global:keys.ContainsKey($key)) {
                    $old=$global:keys[$key]
                    if ($Json -cne $old.json) {$status=409}
                    else {$result=$old.item;$global:retryRecovered=$true;$global:retryAfterAdvance=($global:corrections.Count -gt $result.sequence)}
                } elseif ($inputBody.expectedRevision -ne $global:corrections.Count) {$status=409
                } else {
                    $result=@{id=[guid]::NewGuid().ToString();sequence=$global:corrections.Count+1;before=$global:effective;after=$inputBody.after;reason=$inputBody.reason;doctorId=$global:accountMap.doctor.id;createdAt='2030-01-01T08:00:00+08:00'}
                    if($global:testCase -eq 'ignored-correction-after' -and $result.sequence -ge 2){$result.after=$global:effective}
                    if($global:testCase -eq 'ignored-correction-reason' -and $result.sequence -ge 2){$result.reason='Substituted reason'}
                    if($global:testCase -eq 'ignored-later-after' -and $result.sequence -eq 4){$result.after=$global:effective}
                    if($global:testCase -eq 'ignored-later-reason' -and $result.sequence -eq 4){$result.reason='Substituted reason'}
                    [void]$global:corrections.Add($result);$global:keys[$key]=@{json=$Json;item=$result};$global:effective=$inputBody.after
                    if($global:testCase -in @('ignored-correction-after','ignored-later-after')){$global:effective=$result.after}
                }
            } elseif ($query -match 'pageSize=6|page=invalid') {$status=400
            } else {
                $size=if($query -match 'pageSize=([1-5])') {[int]$Matches[1]}else{5};$page=if($query -match 'page=([0-9]+)') {[int]$Matches[1]}else{1}
                $items=@($global:corrections|Select-Object -Skip (($page-1)*$size) -First $size)
                if($global:corrections.Count -eq 6 -and $global:testCase -in @('middle-history-before','middle-history-after','middle-history-reason','middle-history-identity')){
                    $items=@($items|ForEach-Object{
                        $copy=($_|ConvertTo-Json -Depth 15)|ConvertFrom-Json
                        if($copy.sequence -eq 3){
                            switch($global:testCase){
                                'middle-history-before'{$copy.before.notes='Corrupted before'}
                                'middle-history-after'{$copy.after.notes='Corrupted after'}
                                'middle-history-reason'{$copy.reason='Corrupted reason'}
                                'middle-history-identity'{$copy.doctorId=$global:accountMap.otherDoctor.id;$copy.createdAt='2030-01-02T08:00:00+08:00';$copy.id='99999999-9999-4999-8999-999999999999'}
                            }
                        }
                        $copy
                    })
                }
                $result=@{items=$items;total=$global:corrections.Count;page=$page;pageSize=$size}
            }
        } elseif ($path -match '/record/details$|^doctor/records/[0-9a-f-]+$') {
            $result=@{recordId=$global:rid;appointmentId=$global:apt;original=$global:original;effective=$global:effective;currentRevision=$global:corrections.Count;completedAt='2030-01-01T08:00:00+08:00'}
        } elseif ($path -match '/record$') {
            $result=@{id=$global:rid;appointmentId=$global:apt;patientId=$global:accountMap.patient.id;doctorId=$global:accountMap.doctor.id;complaint=$global:original.complaint;diagnosis=$global:original.diagnosis;advice=$global:original.advice;notes=$global:original.notes;completedAt='2030-01-01T08:00:00+08:00'}
        } else {throw 'Unexpected HTTP boundary'}
        if ($status -ne 200) {
            $errorMessage='Access denied'
            if($global:testCase -eq 'escaped-password-error'){$errorMessage=$global:accountMap.patient.password}
            if($global:testCase -eq 'escaped-canary-error' -and $global:clinicalCanary){$errorMessage=$global:clinicalCanary}
            $raw=@{code='ACCESS_DENIED';message=$errorMessage;requestId=[guid]::NewGuid().ToString()}|ConvertTo-Json -Compress
            if($global:testCase -eq 'escaped-canary-error' -and $global:clinicalCanary){$raw=$raw.Replace($global:clinicalCanary,('\u0043'+$global:clinicalCanary.Substring(1)))}
        }
        else {$raw=@{code='OK';message='safe';data=$result}|ConvertTo-Json -Depth 20 -Compress}
        if ($global:testCase -eq 'revoke-evidence' -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $Root 'owner.id'),'revoked')}
        if ($global:testCase -eq 'revoke-evidence-privacy' -and $path -eq 'platform/audit-events') {Set-ClinicalTestPublicAcl $global:evidencePath}
        if ($global:testCase -eq 'log-leak' -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $Root 'paas-data\log\server.log'),$global:clinicalCanary)}
        if ($global:testCase -eq 'stdout-leak' -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $Root 'paas.out.log'),$global:clinicalCanary)}
        if ($global:testCase -eq 'gbk-password-log' -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $Root 'paas-data\log\server.log'),$global:accountMap.patient.password,$global:strictGbk)}
        if ($global:testCase -eq 'latin1-password-access-log' -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $Root 'portal-data\logs\localhost_access_log.2030-01-01.txt'),$global:accountMap.patient.password,$global:strictLatin1)}
        if ($global:testCase -in @('boot-only-canary','boot-no-policy-leak') -and $path -eq 'platform/audit-events') {[IO.File]::WriteAllText((Join-Path $global:bootLogRoot 'portal.stderr.log'),$global:clinicalCanary)}
        return @{Status=$status;Text=$raw}
    }
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Verify,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'Driver parse failure'}
    $boundary=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-ClinicalTransport'},$true))
    if ($boundary.Count -ne 1) {throw 'HTTP transport boundary missing'}
    $source=[IO.File]::ReadAllText($Verify)
    $replacement='function Invoke-ClinicalTransport { param($Uri,$Method,$Token,$Json) & $global:transport $Uri $Method $Token $Json }'
    $source=$source.Remove($boundary[0].Extent.StartOffset,$boundary[0].Extent.EndOffset-$boundary[0].Extent.StartOffset).Insert($boundary[0].Extent.StartOffset,$replacement)
    # Bind only the helper-source path for this anonymous script block. The
    # original helper's process/marker/listener guard stays real.
    $ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
    $dot=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.InvocationOperator -eq [Management.Automation.Language.TokenKind]::Dot},$true))
    if($dot.Count -ne 1){throw 'Ownership helper boundary missing'}
    $helper=[IO.Path]::GetFullPath((Join-Path (Split-Path $Verify -Parent) '..\tests\attachments-fixture-ownership.ps1'))
    $identityBoundary=@'
function Get-AttachmentsSqlIdentity($Fixture) {
    if($global:testCase -eq 'foreign-live-uuid'){return "ffffffff-ffff-4fff-8fff-ffffffffffff`t45678`t$($Fixture.mysql.dataDirectory)"}
    return "$($Fixture.mysql.serverUuid)`t$($Fixture.mysql.port)`t$($Fixture.mysql.dataDirectory)"
}
'@
    $source=$source.Remove($dot[0].Extent.StartOffset,$dot[0].Extent.EndOffset-$dot[0].Extent.StartOffset).Insert($dot[0].Extent.StartOffset,(". '"+$helper.Replace("'","''")+"'`n"+$identityBoundary))
    if($Case -eq 'missing-gate'){
        # Simulate an interrupted/missing producer, not an imported result.
        $ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
        $summaryFunction=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Write-ClinicalSummary'},$true)
        $source=$source.Insert($summaryFunction.Body.Extent.StartOffset+1,"`n    `$gates.Remove('migration-empty-schema')`n")
    }
    if($Case -in @('log-grows-over-limit','log-eof-appends-secret','log-eof-appends-over-limit')){
        $ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
        $open=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Open-ClinicalLogRead'},$true)
        if(-not $open){throw 'Log stream boundary missing'}
        $openDouble=@'
function Open-ClinicalLogRead([string]$Path) {
    if([IO.Path]::GetFileName($Path) -ceq 'server.log'){
        if($global:testCase -eq 'log-grows-over-limit'){return (New-Object ClinicalGrowingLogStream)}
        return (New-Object ClinicalEofAppendLogStream -ArgumentList $global:clinicalCanary,($global:testCase -eq 'log-eof-appends-over-limit'))
    }
    return [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
}
'@
        $source=$source.Remove($open.Extent.StartOffset,$open.Extent.EndOffset-$open.Extent.StartOffset).Insert($open.Extent.StartOffset,$openDouble)
    }
    try{& ([scriptblock]::Create($source)) -FixturePath $fixturePath -EvidenceRoot $global:evidencePath -AccountsPath $global:accountsPath}
    finally{if($global:liveLogWriter){$global:liveLogWriter.Dispose()}}
}
$failures=0;$assertions=0
function Check([bool]$Value,[string]$Name) {$script:assertions++;if(-not $Value){$script:failures++;Write-Output "FAIL: $Name"}}
$privacyCases=@('public-evidence-outside-boundary','public-evidence-ancestor','public-evidence-target','public-accounts-target','revoke-private-boundary','revoke-private-accounts','revoke-evidence-privacy')
$decodedCases=@('escaped-password-error','escaped-password-audit','escaped-canary-error')
$correctionCases=@('ignored-correction-after','ignored-correction-reason','ignored-later-after','ignored-later-reason','middle-history-before','middle-history-after','middle-history-reason','middle-history-identity')
$encodingCases=@('declared-encodings-clean','gbk-password-log','latin1-password-access-log','invalid-utf8-without-policy','invalid-declared-gbk','bad-policy-version','missing-policy-role','unknown-policy-role','invalid-policy-encoding','latin1-wrong-role','wrong-policy-shape','policy-no-boot')
$policyPreflightCases=@('bad-policy-version','missing-policy-role','unknown-policy-role','invalid-policy-encoding','latin1-wrong-role','wrong-policy-shape','policy-no-boot')
$bootCases=@('boot-no-policy-clean','boot-only-canary','boot-no-policy-leak','boot-missing-file','boot-no-policy-missing','boot-foreign','boot-reparse-directory','boot-nonprivate','boot-file-directory','boot-public-file','boot-empty-source','boot-source-changed')
$bootPreflightCases=@('boot-missing-file','boot-no-policy-missing','boot-foreign','boot-reparse-directory','boot-nonprivate','boot-file-directory','boot-public-file','boot-empty-source')
$eofCases=@('log-eof-appends-secret','log-eof-appends-over-limit')
$cases=if($Scenario -eq 'all'){@('success','live-writer-sharing','unreadable-log','log-grows-over-limit','nested-private-boundary','missing-gate','outside-descriptor','legacy-prerequisite','legacy-wrong-record','canceled-prerequisite','foreign-fixture','protected-port','reparse-descriptor','foreign-live-uuid','revoke-next-request','revoke-mysql-next-request','revoke-evidence','transport-secret-error','audit-leak','empty-audit','log-leak','stdout-leak','logs-unavailable','wrong-login-role','unauth-bypass')+$privacyCases+$decodedCases+$correctionCases+$encodingCases+$bootCases+$eofCases}elseif($Scenario -eq 'privacy'){$privacyCases}elseif($Scenario -eq 'decoded'){$decodedCases}elseif($Scenario -eq 'corrections'){$correctionCases}elseif($Scenario -eq 'encoding'){$encodingCases}elseif($Scenario -eq 'boot'){$bootCases}elseif($Scenario -eq 'eof'){$eofCases}else{@($Scenario)}
foreach($case in $cases){
    $root=Join-Path $env:TEMP ('attachments-'+[guid]::NewGuid().ToString('N'))
    if ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($root)) -ine 'E:\') {throw 'Tests require unique E: TEMP'}
    $testHost=New-Object ClinicalAcceptanceHost;$runspace=[runspacefactory]::CreateRunspace($testHost);$runspace.Open()
    $ps=[powershell]::Create();$ps.Runspace=$runspace
    try{
        [void]$ps.AddScript($harness.ToString()).AddArgument($verify).AddArgument($root).AddArgument($case)
        $output='';try{$output=$ps.Invoke()|Out-String}catch{$output=$_.Exception.Message};$output+=($ps.Streams.Error|Out-String)
        if($testHost.ExitCode -eq -1){Write-Output ($output -replace 'CLINICAL-CANARY-[0-9a-f]+','[redacted]')}
        $files=@(Get-ChildItem -LiteralPath $root -Filter 'clinical-supplements-summary.json' -Recurse -ErrorAction SilentlyContinue)
        $text=($files|ForEach-Object{[IO.File]::ReadAllText($_.FullName)}) -join ''
        $summary=if($files.Count -eq 1){$text|ConvertFrom-Json}else{$null}
        Check ($testHost.ExitCode -gt 0) "${case}: missingRequiredGateCannotPass"
        Check (-not [bool]$runspace.SessionStateProxy.GetVariable('requestUnguarded')) "${case}: every HTTP request freshly guarded"
        if($case -in (@('foreign-fixture','protected-port','reparse-descriptor','foreign-live-uuid','revoke-next-request','revoke-mysql-next-request','revoke-evidence','boot-source-changed')+$privacyCases)){
            Check ($files.Count -eq 0) "${case}: foreignFixtureBeforeEveryWrite prevents final evidence"
            if($case -in @('foreign-fixture','protected-port','reparse-descriptor','foreign-live-uuid')){Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 0) 'foreign/protected/reparse fixture refuses first HTTP'}
            if($case -in @('revoke-next-request','revoke-mysql-next-request')){Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 1) 'revocation aborts next request'}
            if($case -in @('public-evidence-outside-boundary','public-evidence-ancestor','public-evidence-target','public-accounts-target')){Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 0) 'nonprivate ancestry/target refuses first HTTP'}
            if($case -in @('revoke-private-boundary','revoke-private-accounts')){Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 1) 'midrun privacy loss aborts next request'}
            if($case -eq 'boot-source-changed'){Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 1) 'source metadata change aborts next request'}
        }elseif($case -in ($policyPreflightCases+$bootPreflightCases)){
            Check ([int]$runspace.SessionStateProxy.GetVariable('requests') -eq 0) "${case}: invalid source contract refused before HTTP"
            Check (-not $summary -or (@($summary.failed) -contains 'fixture-and-private-accounts')) "${case}: policy never marked clean"
        }else{
            Check ($null -ne $summary) "${case}: final scalar summary exists"
            if($case -in @('success','live-writer-sharing','declared-encodings-clean','boot-no-policy-clean','nested-private-boundary','missing-gate','outside-descriptor','legacy-prerequisite','canceled-prerequisite','logs-unavailable')){
                $expectedPassed=if($case -in @('legacy-prerequisite','canceled-prerequisite')){22}elseif($case -eq 'logs-unavailable'){20}else{21}
                Check ($summary.completeDriverRun -eq $true -and $summary.wholeTask6Accepted -eq $false) 'HTTP green distinguishes driver completion from Task6 acceptance'
                Check (@($summary.required).Count -eq 53 -and @($summary.failed).Count -eq 0 -and @($summary.passed).Count -eq $expectedPassed) 'HTTP chain passed with complete required gate registry'
                Check (@($summary.unverified) -contains 'browser-js-disabled') 'native/browser missing not passed'
                Check (@($summary.unverified) -contains 'mysql-concurrency-draft') 'live database missing not passed'
                Check (@($summary.unverified) -contains 'migration-empty-schema') 'missingRequiredGateCannotPass: absent gate restored unverified'
                if($case -eq 'logs-unavailable'){Check (@($summary.unverified) -contains 'container-log-redaction') 'unavailable logs remain unverified'}
                if($case -eq 'legacy-prerequisite'){Check (@($summary.passed) -contains 'legacy-complete-no-draft') 'separate fresh legacy prerequisite executed'}else{Check (@($summary.unverified) -contains 'legacy-complete-no-draft') 'missing legacy prerequisite not passed'}
                if($case -eq 'canceled-prerequisite'){Check (@($summary.passed) -contains 'canceled-complaint-readonly') 'separate canceled prerequisite executed'}else{Check (@($summary.unverified) -contains 'canceled-complaint-readonly') 'missing canceled prerequisite not passed'}
                Check ([bool]$runspace.SessionStateProxy.GetVariable('retryRecovered') -and [bool]$runspace.SessionStateProxy.GetVariable('retryAfterAdvance')) 'operationKeyRetryAfterLostResponse recovers old item after intervening correction'
                Check (@($summary.required).Count -eq (@($summary.passed).Count+@($summary.failed).Count+@($summary.skipped).Count+@($summary.unverified).Count)) 'every required gate accounted exactly once'
            }else{
                Check (@($summary.failed).Count -gt 0) "${case}: observed fault produces failed gate"
                if($case -eq 'unreadable-log'){Check (@($summary.failed) -contains 'container-log-redaction') 'unreadable source is failed, never clean'}
                if($case -in $eofCases){
                    Check (@($summary.failed) -contains 'container-log-redaction' -and @($summary.passed) -notcontains 'container-log-redaction') "${case}: incomplete EOF snapshot cannot pass log gate"
                    Check ($summary.completeDriverRun -eq $false -and $summary.wholeTask6Accepted -eq $false) "${case}: incomplete EOF snapshot cannot claim completion"
                }
                if($case -in $correctionCases){Check ($summary.facts.historyChainValid -eq $false) "${case}: corrupted chain cannot be claimed valid"}
            }
        }
        $canary=[string]$runspace.SessionStateProxy.GetVariable('clinicalCanary');$secret=[string]$runspace.SessionStateProxy.GetVariable('canary')
        Check (-not $secret -or -not ($output+$text).Contains($secret)) "${case}: boundary secrets never console/evidence"
        Check (-not $canary -or -not ($output+$text).Contains($canary)) "${case}: clinicalTextNotInEvidenceOrAudit"
    }finally{$ps.Dispose();$runspace.Dispose()}
}
Write-Output "clinical acceptance behavior: scenarios=$($cases.Count) assertions=$assertions failed=$failures"
if($failures){exit 1};exit 0
