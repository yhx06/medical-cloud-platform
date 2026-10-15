param([string]$Scenario = 'all')
$ErrorActionPreference = 'Stop'
$verify = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\scripts\verify-attachments.ps1'))
# Execute the unchanged driver, including exit, in a host that records its exit code.
# Only OS/HTTP/client boundaries are fake; ownership and summary logic stay real.
if (-not ('AttachmentAcceptanceHost' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Globalization;
using System.Management.Automation.Host;
public sealed class AttachmentAcceptanceHost : PSHost {
    public int ExitCode = -1;
    public override string Name { get { return "AttachmentAcceptanceTests"; } }
    public override Version Version { get { return new Version(1,0); } }
    private readonly Guid id = Guid.NewGuid();
    public override Guid InstanceId { get { return id; } }
    public override PSHostUserInterface UI { get { return null; } }
    public override CultureInfo CurrentCulture { get { return CultureInfo.InvariantCulture; } }
    public override CultureInfo CurrentUICulture { get { return CultureInfo.InvariantCulture; } }
    public override void SetShouldExit(int exitCode) { ExitCode = exitCode; }
    public override void EnterNestedPrompt() { throw new NotSupportedException(); }
    public override void ExitNestedPrompt() { }
    public override void NotifyBeginApplication() { }
    public override void NotifyEndApplication() { }
}
'@
}
$harness = {
    param($Verify, $Root, $Case)
    $ErrorActionPreference = 'Stop'
    $global:testCase = $Case
    $global:requests = 0
    $global:mutations = 0
    $global:uploadCount = 0
    $global:guards = @()
    $global:leak = 'PRIVATE-SECRET X-Amz-Signature=abcdef X-Amz-Credential=credential X-Amz-Security-Token=token Bearer JWT password=secret'
    $owner = '0123456789abcdef0123456789abcdef'
    [void][IO.Directory]::CreateDirectory($Root)
    [IO.File]::WriteAllText((Join-Path $Root 'owner.id'), $owner)
    $fixture = [ordered]@{ ownerId=$owner; runtimeRoot=$Root }
    $created = [datetime]'2030-01-01T00:00:00Z'
    $global:processes = @{}
    $global:listeners = @{}
    $ports = @{mysql=45678;storage=45679;portal=45680;paas=18081}
    $ids = @{mysql=23456;storage=23457;portal=23458;paas=23459}
    foreach ($component in @('mysql','storage','portal','paas')) {
        $data = Join-Path $Root ($component + '-data')
        [void][IO.Directory]::CreateDirectory($data)
        $exe = Join-Path $Root ($component + '.exe')
        [IO.File]::WriteAllText($exe, 'fixture only')
        $port = $ports[$component]
        $part = [ordered]@{pid=$ids[$component];created=$created;executable=$exe;dataDirectory=$data;port=$port;url="http://127.0.0.1:$port"}
        $command = "`"$exe`" `"$data`""
        switch ($component) {
            mysql {
                $part.host='127.0.0.1'; $part.serverUuid='12345678-1234-4234-8234-123456789abc'
                $part.database='medical_cloud_course'
                [IO.File]::WriteAllText((Join-Path $data 'auto.cnf'), "[auto]`nserver-uuid=$($part.serverUuid)`n")
                $part.credentialFile=Join-Path $Root 'client.cnf'
                [IO.File]::WriteAllText($part.credentialFile, '[client]')
                $part.clientExecutable=Join-Path $Root 'identity.ps1'
                [IO.File]::WriteAllText($part.clientExecutable, ('"' + $part.serverUuid + '`t' + $port + '`t' + $data + '"; $global:LASTEXITCODE=0'))
                $command += " --port=$port"
            }
            storage {
                $part.consolePort=45682; $global:listeners[45682]=$ids[$component]
                $part.internalEndpoint=$part.url; $part.publicEndpoint=$part.url; $part.bucket='test-private'; $part.prefix='course-attachments/'
                $command += " --address 127.0.0.1:$port --console-address 127.0.0.1:45682"
            }
            portal {
                $part.shutdownPort=45681; $global:listeners[45681]=$ids[$component]
                [void][IO.Directory]::CreateDirectory((Join-Path $data 'conf'))
                [IO.File]::WriteAllText((Join-Path $data 'conf\server.xml'), '<Server port="45681"><Service><Connector port="45680" address="127.0.0.1" /></Service></Server>')
            }
            paas {
                $part.managementPort=19991; $global:listeners[19991]=$ids[$component]
                $command += ' -Djboss.socket.binding.port-offset=10001'
            }
        }
        $global:processes[$ids[$component]]=[pscustomobject]@{ProcessId=$ids[$component];CreationDate=$created;ExecutablePath=$exe;CommandLine=$command;Component=$component}
        $global:listeners[[int]$port]=$ids[$component]
        $fixture[$component]=$part
    }
    if ($Case -eq 'foreign-marker') { $fixture.ownerId='ffffffffffffffffffffffffffffffff' }
    $fixturePath=Join-Path $Root 'fixture.json'
    [IO.File]::WriteAllText($fixturePath, ($fixture | ConvertTo-Json -Depth 8))
    function global:Get-CimInstance {
        param($ClassName,$Filter,$ErrorAction)
        $id=[int]($Filter -replace '^ProcessId=','')
        $process=$global:processes[$id]
        $global:guards += $process.Component
        if ($global:testCase -eq 'malicious-guard-error' -and $global:requests -ge 1 -and $process.Component -eq 'paas') { throw $global:leak }
        if ($global:testCase -eq 'lost-login-owner' -and $global:requests -ge 1 -and $process.Component -eq 'paas') { return $null }
        if ($global:testCase -eq 'lost-content-owner' -and $global:uploadCount -ge 1 -and $process.Component -eq 'storage') { return $null }
        return $process
    }
    function global:Get-NetTCPConnection {
        param($LocalAddress,$LocalPort,$State,$ErrorAction)
        return [pscustomobject]@{OwningProcess=$global:listeners[[int]$LocalPort];LocalPort=$LocalPort}
    }
    function global:cmd { return 'java fixture version (not a runtime observation)' }
    function global:Invoke-WebRequest {
        param($Uri,$Method,$Headers,$TimeoutSec,$ErrorAction,$Body,$ContentType,[switch]$UseBasicParsing)
        $global:requests++
        if ($Method -notin @('GET','HEAD')) {
            $global:mutations++
            if ($global:testCase -eq 'fresh-mutation-guards' -and @('mysql','paas','storage' | Where-Object { $_ -notin $global:guards }).Count -gt 0) {
                throw $global:leak
            }
        }
        $global:guards=@()
        if ($global:testCase -eq 'failure' -and $Uri -match '/auth/login$') { throw $global:leak }
        $status=200; $data=@{}
        switch -Regex ($Uri) {
            '/auth/login$' { $data=@{accessToken='fixture-token'}; break }
            '/patient/appointments$' {
                if ($Method -eq 'GET') { $status=401 } else { $data=@{id='appointment-1'} }; break
            }
            '/files/uploads$' {
                $descriptor=$Body | ConvertFrom-Json
                if ($descriptor.sizeBytes -gt 10485760) { $status=413 }
                elseif ($descriptor.contentType -eq 'text/plain') { $status=400 }
                elseif ($descriptor.fileName -eq 'pending3.png') { $status=429 }
                elseif ($global:testCase -eq 'malicious-body') { $status=500; $data=@{message=$global:leak} }
                else { $global:uploadCount++; $data=@{uploadId=('upload-' + $global:uploadCount)} }
                break
            }
            '/content$' { break }
            '/files\?page=bad' { $status=if ($Headers.Authorization) {403} else {401}; break }
            '/files\?page=' { $data=@{items=@(@{fileId='upload-1'});total=2}; break }
            '/files/uploads/upload-1$' { $data=@{uploadId='upload-1'}; break }
            '/files/uploads/[0-9a-f-]{36}$' { $status=404; break }
            '/files/[0-9a-f-]{36}/(delete|download-url)$' { $status=404; break }
            '/audit-events\?' { $data=@{items=@();total=0}; break }
            '/files/upload-1/download-url$' { $data=@{url='http://127.0.0.1:45679/test-private/object?X-Amz-Signature=abcdef';expiresAt='2030-01-01T00:05:00Z'}; break }
            '/test-private$' { $status=403; break }
            'X-Amz-Signature=0' { $status=403; break }
            'X-Amz-Signature=' { break }
            '/portal/login$' { break }
            default { throw 'Test boundary received an unexpected request' }
        }
        return [pscustomobject]@{StatusCode=$status;Content=(@{data=$data} | ConvertTo-Json -Depth 8)}
    }
    $arguments=@{FixturePath=$fixturePath;EvidenceRoot=(Join-Path $Root 'evidence')}
    if ($Case -eq 'skip') { $arguments.SkipGate=@('app-health') }
    if ($Case -eq 'unknown-skip') { $arguments.SkipGate=@('PRIVATE-SECRET') }
    & $Verify @arguments
    exit $LASTEXITCODE
}
$script:assertions=0; $script:failures=0; $script:scenarios=0
function Check([bool]$Value,[string]$Name) {
    $script:assertions++
    if (-not $Value) { $script:failures++; Write-Output "FAIL: $Name" }
}
$cases=if ($Scenario -eq 'all') { @('success','failure','malicious-body','malicious-guard-error','unknown-skip','skip','foreign-marker','fresh-mutation-guards','lost-login-owner','lost-content-owner') } else { @($Scenario) }
foreach ($case in $cases) {
    $script:scenarios++
    $root=Join-Path ([IO.Path]::GetTempPath()) ('attachments-' + [guid]::NewGuid().ToString('N'))
    $testHost=New-Object AttachmentAcceptanceHost
    $runspace=[runspacefactory]::CreateRunspace($testHost); $runspace.Open()
    $ps=[powershell]::Create(); $ps.Runspace=$runspace
    try {
        [void]$ps.AddScript($harness.ToString()).AddArgument($verify).AddArgument($root).AddArgument($case)
        $output=''
        try { $output=$ps.Invoke() | Out-String } catch { $output=$_.Exception.Message }
        $output += ($ps.Streams.Error | Out-String)
        $requests=[int]$runspace.SessionStateProxy.GetVariable('requests')
        $mutations=[int]$runspace.SessionStateProxy.GetVariable('mutations')
        $path=Join-Path $root 'evidence\attachments-verification-summary.json'
        $summary=if (Test-Path -LiteralPath $path) { [IO.File]::ReadAllText($path) | ConvertFrom-Json } else { $null }
        if ($case -eq 'foreign-marker') {
            Check ($testHost.ExitCode -ne 0 -and -not $summary -and $requests -eq 0) 'foreign marker refused before HTTP/evidence'
            continue
        }
        if ($case -eq 'unknown-skip') {
            Check ($testHost.ExitCode -eq 3 -and $requests -eq 0) 'unknown skip rejected before HTTP'
            Check ($output -notmatch 'PRIVATE-SECRET') 'unknown skip value never reflected'
            continue
        }
        Check ($null -ne $summary) "$case produces partial summary"
        Check ($testHost.ExitCode -gt 0) "$case cannot report full verification success"
        if ($case -eq 'success') {
            Check ($testHost.ExitCode -eq 4) 'incomplete coverage has its own exit status'
            Check (@($summary.passed).Count -eq 7 -and @($summary.failed).Count -eq 0) 'seven successful HTTP subchecks preserved'
            Check (@($summary.unverified).Count -ge 9) 'missing required coverage explicitly unverified'
            Check (@($summary.required).Count -gt 7) 'required coverage exceeds seven HTTP subchecks'
            Check (@($summary.designCoverage).Count -eq 9 -and @($summary.designCoverage | Where-Object status -eq 'passed').Count -eq 0) 'nine design categories cannot pass from partial HTTP checks'
            Check ($summary.limitations.Count -gt 0) 'summary discloses limits of this invocation'
        }
        if ($case -in @('failure','malicious-body','malicious-guard-error')) {
            $evidence=($output + ((Get-ChildItem -LiteralPath (Join-Path $root 'evidence') -Filter '*.json' | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join ''))
            Check (@($summary.failed).Count -gt 0) "$case recorded as failed"
            Check ($testHost.ExitCode -eq 1 -and @($summary.skipped).Count -eq 0) "$case distinguished from skipped/unverified coverage"
            Check ($evidence -notmatch 'PRIVATE-SECRET|X-Amz-Signature|X-Amz-Credential|X-Amz-Security-Token|Bearer JWT|password=secret') "$case never discloses unknown body/exception secrets"
        }
        if ($case -eq 'skip') {
            Check (@($summary.skipped) -contains 'app-health' -and @($summary.failed) -notcontains 'app-health' -and @($summary.unverified) -notcontains 'app-health') 'explicit skip remains skipped, not failed/unverified'
            Check ($testHost.ExitCode -eq 2) 'required skip has its own exit status'
        }
        if ($case -eq 'fresh-mutation-guards') { Check (@($summary.passed).Count -eq 7) 'every mutation including login freshly guards owned dependencies' }
        if ($case -eq 'lost-login-owner') { Check ($mutations -eq 0) 'lost PaaS ownership prevents login mutation' }
        if ($case -eq 'lost-content-owner') { Check ($mutations -eq 7) 'lost storage ownership after prepare prevents content and later mutations' }
    } finally { $ps.Dispose(); $runspace.Dispose() }
}
Write-Output "attachment acceptance summary behavior: scenarios=$scenarios assertions=$assertions failed=$failures"
if ($failures -gt 0) { exit 1 }
exit 0
