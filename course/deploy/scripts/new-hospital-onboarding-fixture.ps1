param(
    [string]$RuntimeRoot,[string]$MySqlBin,[string]$JavaHome,[string]$TomcatHome,
    [string]$WildFlyHome,[string]$MinioExecutable,[string]$MinioClientExecutable,
    [string]$MavenExecutable,[string]$ConnectorJar,
    [string]$SourceCommit,[string]$NewRunId,[switch]$Execute,[switch]$Stop,[switch]$Start
)

# Intentionally ASCII for native Windows PowerShell 5.1 without a BOM.
function Assert-OnboardingPathChain([string]$OnboardingPath) {
    $OnboardingCursor=[IO.Path]::GetFullPath($OnboardingPath)
    while ($OnboardingCursor) {
        if (Test-Path -LiteralPath $OnboardingCursor) {
            if ((Get-Item -LiteralPath $OnboardingCursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unsafe reparse path' }
        }
        $OnboardingCursor=Split-Path $OnboardingCursor -Parent
    }
}
function Assert-OnboardingPrivateParent([string]$OnboardingPath) {
    $OnboardingMe=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach ($OnboardingRule in (Get-Acl -LiteralPath $OnboardingPath).Access) {
        $OnboardingSid=$OnboardingRule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($OnboardingRule.AccessControlType -eq 'Allow' -and
            ($OnboardingRule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -ne 0 -and
            $OnboardingSid -notin @($OnboardingMe,'S-1-5-18','S-1-5-32-544','S-1-3-0')) { throw 'Unsafe writable parent ACL' }
    }
}
function Assert-OnboardingNewRuntime([hashtable]$OnboardingOptions) {
    if ($OnboardingOptions.NewRunId -cnotmatch '\A[0-9a-f]{32}\z' -or $OnboardingOptions.NewRunId -ceq ('0'*32)) { throw 'Invalid run ID' }
    if ($OnboardingOptions.SourceCommit -cnotmatch '\A[0-9a-f]{40}\z') { throw 'Source commit must be fixed lowercase 40hex' }
    if (-not [IO.Path]::IsPathRooted($OnboardingOptions.RuntimeRoot)) { throw 'Runtime must be absolute' }
    $OnboardingRoot=[IO.Path]::GetFullPath($OnboardingOptions.RuntimeRoot).TrimEnd('\','/')
    $OnboardingExpected=[IO.Path]::GetFullPath((Join-Path $OnboardingOptions.ProjectRoot ('.local/runtime/attachments-'+$OnboardingOptions.NewRunId)))
    if ($OnboardingRoot -cne $OnboardingExpected) { throw 'Runtime owner/path mismatch' }
    Assert-OnboardingPathChain $OnboardingRoot
    if (Test-Path -LiteralPath $OnboardingRoot) { throw 'Runtime already exists' }
    $OnboardingParent=Split-Path $OnboardingRoot -Parent
    if (-not (Test-Path -LiteralPath $OnboardingParent -PathType Container)) { throw 'Runtime parent missing' }
    Assert-OnboardingPrivateParent $OnboardingParent
    return $OnboardingRoot
}
function Set-OnboardingPrivateAcl([string]$OnboardingPath) {
    $OnboardingIsDirectory=Test-Path -LiteralPath $OnboardingPath -PathType Container
    $OnboardingAcl=if($OnboardingIsDirectory){New-Object Security.AccessControl.DirectorySecurity}else{New-Object Security.AccessControl.FileSecurity}
    $OnboardingAcl.SetAccessRuleProtection($true,$false)
    foreach ($OnboardingRule in @($OnboardingAcl.Access)) { [void]$OnboardingAcl.RemoveAccessRuleAll($OnboardingRule) }
    $OnboardingMe=[Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($OnboardingIsDirectory) {
        $OnboardingAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($OnboardingMe,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    } else { $OnboardingAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($OnboardingMe,'FullControl','Allow'))) }
    if ($OnboardingIsDirectory) { [IO.Directory]::SetAccessControl($OnboardingPath,$OnboardingAcl) } else { [IO.File]::SetAccessControl($OnboardingPath,$OnboardingAcl) }
}
function Write-OnboardingPrivate([string]$OnboardingPath,[string]$OnboardingText) {
    Assert-OnboardingPathChain $OnboardingPath
    [IO.File]::WriteAllText($OnboardingPath,$OnboardingText,(New-Object Text.UTF8Encoding($false)))
    Set-OnboardingPrivateAcl $OnboardingPath
}
function Expand-OnboardingSource([string]$OnboardingArchive,[string]$OnboardingDestination) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $OnboardingDestination) { throw 'Snapshot destination already exists' }
    Assert-OnboardingPathChain $OnboardingDestination
    $OnboardingZip=[IO.Compression.ZipFile]::OpenRead($OnboardingArchive)
    try {
        # .NET Framework may silently stop reading the central directory when a
        # name contains invalid Windows characters. Verify declared entry count.
        $OnboardingStream=[IO.File]::OpenRead($OnboardingArchive)
        try {
            $OnboardingTail=New-Object byte[] ([int][Math]::Min(65557,$OnboardingStream.Length))
            [void]$OnboardingStream.Seek(-$OnboardingTail.Length,[IO.SeekOrigin]::End)
            [void]$OnboardingStream.Read($OnboardingTail,0,$OnboardingTail.Length)
            $OnboardingDeclared=-1
            for($OnboardingIndex=$OnboardingTail.Length-22;$OnboardingIndex -ge 0;$OnboardingIndex--) {
                if ([BitConverter]::ToUInt32($OnboardingTail,$OnboardingIndex) -eq 0x06054b50 -and $OnboardingIndex+22+[BitConverter]::ToUInt16($OnboardingTail,$OnboardingIndex+20) -eq $OnboardingTail.Length) { $OnboardingDeclared=[BitConverter]::ToUInt16($OnboardingTail,$OnboardingIndex+10); break }
            }
            if ($OnboardingDeclared -le 0 -or $OnboardingDeclared -eq 65535 -or $OnboardingZip.Entries.Count -ne $OnboardingDeclared) { throw 'ZIP entry enumeration incomplete or unsafe' }
        } finally { $OnboardingStream.Dispose() }
        $OnboardingSeen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($OnboardingEntry in $OnboardingZip.Entries) {
            $OnboardingName=$OnboardingEntry.FullName
            if ($OnboardingName -match '[\x00-\x1f\\:]' -or $OnboardingName.StartsWith('/') -or $OnboardingName -match '(^|/)\.\.?(/|$)' -or
                $OnboardingName -match '(?i)(^|/)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|/|$)' -or
                $OnboardingName -match '[ .](/|$)' -or -not $OnboardingSeen.Add($OnboardingName.TrimEnd('/'))) { throw 'Unsafe ZIP entry' }
            $OnboardingTarget=[IO.Path]::GetFullPath((Join-Path $OnboardingDestination $OnboardingName))
            if (-not $OnboardingTarget.StartsWith([IO.Path]::GetFullPath($OnboardingDestination)+'\',[StringComparison]::OrdinalIgnoreCase) -or $OnboardingTarget.Length -gt 240) { throw 'Unsafe ZIP path length or escape' }
            if (($OnboardingEntry.ExternalAttributes -shr 16 -band 0xF000) -eq 0xA000) { throw 'ZIP symlink refused' }
        }
        [IO.Directory]::CreateDirectory($OnboardingDestination)|Out-Null
        foreach ($OnboardingEntry in $OnboardingZip.Entries) {
            $OnboardingTarget=Join-Path $OnboardingDestination $OnboardingEntry.FullName
            if ($OnboardingEntry.FullName.EndsWith('/')) { [IO.Directory]::CreateDirectory($OnboardingTarget)|Out-Null; continue }
            [IO.Directory]::CreateDirectory((Split-Path $OnboardingTarget -Parent))|Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($OnboardingEntry,$OnboardingTarget,$false)
        }
    } finally { $OnboardingZip.Dispose() }
}
function ConvertTo-OnboardingArgument([string]$OnboardingValue) {
    if ($OnboardingValue -match '[\x00\r\n]') { throw 'Unsafe native argument' }
    if ($OnboardingValue -notmatch '[\s"]' -and $OnboardingValue.Length) { return $OnboardingValue }
    return '"'+[regex]::Replace([regex]::Replace($OnboardingValue,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'
}
function Invoke-OnboardingNative {
    param($OnboardingExecutable,$OnboardingArguments,$OnboardingWorkingDirectory,$OnboardingInput,$OnboardingLabel,[switch]$OnboardingStart)
    if ($OnboardingStart) {
        $OnboardingQuoted=@($OnboardingArguments|ForEach-Object{ConvertTo-OnboardingArgument ([string]$_)})
        $OnboardingBackground=Start-Process -FilePath $OnboardingExecutable -ArgumentList $OnboardingQuoted -WorkingDirectory $OnboardingWorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OnboardingWorkingDirectory ($OnboardingLabel+'.out.log')) -RedirectStandardError (Join-Path $OnboardingWorkingDirectory ($OnboardingLabel+'.err.log'))
        return [pscustomobject]@{ExitCode=0;ProcessId=$OnboardingBackground.Id;Created=$OnboardingBackground.StartTime.ToUniversalTime().ToString('o');Output=''}
    }
    if ([IO.Path]::GetExtension($OnboardingExecutable) -imatch '^\.(cmd|bat)$') {
        # Windows batch files need an interpreter. A literal PS relay preserves
        # array boundaries and makes the child native exit explicit (no cmd /c).
        $OnboardingLiterals=@($OnboardingArguments|ForEach-Object{"'"+([string]$_).Replace("'","''")+"'"})
        $OnboardingRelay="[Console]::OutputEncoding=New-Object Text.UTF8Encoding(`$false); & '"+$OnboardingExecutable.Replace("'","''")+"' "+($OnboardingLiterals -join ' ')+"; exit `$LASTEXITCODE"
        $OnboardingExecutable='powershell.exe'
        $OnboardingArguments=@('-NoProfile','-NonInteractive','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($OnboardingRelay)))
    }
    $OnboardingInfo=New-Object Diagnostics.ProcessStartInfo
    $OnboardingInfo.FileName=$OnboardingExecutable
    $OnboardingInfo.Arguments=(@($OnboardingArguments|ForEach-Object{ConvertTo-OnboardingArgument ([string]$_)}) -join ' ')
    $OnboardingInfo.WorkingDirectory=$OnboardingWorkingDirectory
    $OnboardingInfo.UseShellExecute=$false; $OnboardingInfo.CreateNoWindow=$true
    $OnboardingInfo.RedirectStandardInput=$true
    $OnboardingInfo.RedirectStandardOutput=$true; $OnboardingInfo.RedirectStandardError=$true
    $OnboardingInfo.StandardOutputEncoding=New-Object Text.UTF8Encoding($false)
    $OnboardingInfo.StandardErrorEncoding=New-Object Text.UTF8Encoding($false)
    $OnboardingProcess=New-Object Diagnostics.Process
    $OnboardingProcess.StartInfo=$OnboardingInfo
    try {
        if (-not $OnboardingProcess.Start()) { throw 'Native start failed' }
        $OnboardingOutput=$OnboardingProcess.StandardOutput.ReadToEndAsync()
        $OnboardingError=$OnboardingProcess.StandardError.ReadToEndAsync()
        if ($null -ne $OnboardingInput) {
            $OnboardingBytes=(New-Object Text.UTF8Encoding($false)).GetBytes([string]$OnboardingInput)
            $OnboardingProcess.StandardInput.BaseStream.Write($OnboardingBytes,0,$OnboardingBytes.Length)
        }
        $OnboardingProcess.StandardInput.Close()
        if (-not $OnboardingProcess.WaitForExit(1800000)) { throw ('Native step timed out: '+$OnboardingLabel) }
        return [pscustomobject]@{ExitCode=$OnboardingProcess.ExitCode;Output=$OnboardingOutput.Result;Error=$OnboardingError.Result}
    } finally { $OnboardingProcess.Dispose() }
}
function Invoke-OnboardingChecked($OnboardingExecutable,$OnboardingArguments,$OnboardingDirectory,$OnboardingInput,$OnboardingLabel) {
    $OnboardingResult=Invoke-OnboardingNative $OnboardingExecutable $OnboardingArguments $OnboardingDirectory $OnboardingInput $OnboardingLabel
    if ($OnboardingResult.ExitCode -ne 0) { throw ('Native step stopped: '+$OnboardingLabel+' (exit '+$OnboardingResult.ExitCode+')') }
    return $OnboardingResult.Output
}
function Assert-OnboardingToolchain([hashtable]$OnboardingOptions) {
    foreach ($OnboardingPath in @((Join-Path $OnboardingOptions.MySqlBin 'mysqld.exe'),(Join-Path $OnboardingOptions.MySqlBin 'mysql.exe'),(Join-Path $OnboardingOptions.MySqlBin 'mysqladmin.exe'),(Join-Path $OnboardingOptions.MySqlBin 'mysqldump.exe'),(Join-Path $OnboardingOptions.JavaHome 'bin/javac.exe'),(Join-Path $OnboardingOptions.JavaHome 'bin/java.exe'),(Join-Path $OnboardingOptions.TomcatHome 'bin/bootstrap.jar'),(Join-Path $OnboardingOptions.TomcatHome 'bin/tomcat-juli.jar'),(Join-Path $OnboardingOptions.TomcatHome 'conf/server.xml'),(Join-Path $OnboardingOptions.WildFlyHome 'jboss-modules.jar'),(Join-Path $OnboardingOptions.WildFlyHome 'bin/jboss-cli.bat'),(Join-Path $OnboardingOptions.WildFlyHome 'standalone/configuration/standalone.xml'),$OnboardingOptions.MinioExecutable,$OnboardingOptions.MinioClientExecutable,$OnboardingOptions.MavenExecutable,$OnboardingOptions.ConnectorJar)) {
        if (-not (Test-Path -LiteralPath $OnboardingPath -PathType Leaf)) { throw 'Required tool/configuration missing' }
        Assert-OnboardingPathChain $OnboardingPath
    }
    if (-not (Test-Path -LiteralPath (Join-Path $OnboardingOptions.WildFlyHome 'modules') -PathType Container)) { throw 'WildFly modules directory missing' }
    $OnboardingMySql=Invoke-OnboardingNative (Join-Path $OnboardingOptions.MySqlBin 'mysqld.exe') @('--no-defaults','--version') $OnboardingOptions.ProjectRoot $null 'mysql-version'
    if ($OnboardingMySql.ExitCode -ne 0 -or ($OnboardingMySql.Output+$OnboardingMySql.Error) -notmatch 'Ver 8\.0\.') { throw 'MySQL 8.0 is required for this fixture' }
    $OnboardingJava=Invoke-OnboardingNative (Join-Path $OnboardingOptions.JavaHome 'bin/javac.exe') @('-version') $OnboardingOptions.ProjectRoot $null 'java-version'
    if ($OnboardingJava.ExitCode -ne 0 -or ($OnboardingJava.Output+$OnboardingJava.Error) -notmatch 'javac 1\.8\.') { throw 'Java 8 preflight stopped' }
    if ((Get-OnboardingToolHash $OnboardingOptions.ConnectorJar) -cne 'e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9') { throw 'Connector/J identity mismatch' }
    if ((Get-OnboardingToolHash $OnboardingOptions.MinioExecutable) -cne '1e534498e2468a4b82c2e98300a883492af3107e666af35aba9875ef7853162f' -or
        (Get-OnboardingToolHash $OnboardingOptions.MinioClientExecutable) -cne 'c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157') { throw 'Pinned object-store tool identity mismatch' }
}
function Save-OnboardingJournal($OnboardingContext) {
    $OnboardingMarker=Join-Path $OnboardingContext.Root 'owner.id'
    if (-not (Test-Path -LiteralPath $OnboardingMarker) -or [IO.File]::ReadAllText($OnboardingMarker) -cne $OnboardingContext.Options.NewRunId) { throw 'Journal owner lost; last journal preserved' }
    Assert-OnboardingPathChain $OnboardingContext.Root
    foreach($OnboardingName in @($OnboardingContext.Journal.components.Keys)) {
        $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
        if ($OnboardingPart.status -eq 'running') { Assert-OnboardingIdentity $OnboardingContext $OnboardingName }
        elseif ($OnboardingPart.status -eq 'starting') { Assert-OnboardingIdentity $OnboardingContext $OnboardingName -OnboardingStarting }
    }
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'journal.json') ($OnboardingContext.Journal|ConvertTo-Json -Depth 16)
}
function Get-OnboardingProcess([int]$OnboardingProcessId) { Get-CimInstance Win32_Process -Filter "ProcessId=$OnboardingProcessId" -ErrorAction Stop }
function Get-OnboardingListeners([int]$OnboardingPort) { @(Get-NetTCPConnection -LocalPort $OnboardingPort -State Listen -ErrorAction SilentlyContinue) }
function Assert-OnboardingIdentity($OnboardingContext,[string]$OnboardingComponent,[switch]$OnboardingStarting) {
    Assert-OnboardingPathChain $OnboardingContext.Root
    if ([IO.File]::ReadAllText((Join-Path $OnboardingContext.Root 'owner.id')) -cne $OnboardingContext.Options.NewRunId) { throw 'Owner identity lost' }
    $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingComponent]
    if (-not $OnboardingPart -or $OnboardingPart.pid -lt 1 -or $OnboardingPart.port -in @(3318,8180,8188,10090) -or $OnboardingPart.port -lt 1 -or $OnboardingPart.port -gt 65535) { throw 'Component identity incomplete' }
    $OnboardingProcess=@(Get-OnboardingProcess $OnboardingPart.pid)
    if ($OnboardingProcess.Count -ne 1 -or $OnboardingProcess[0].ProcessId -ne $OnboardingPart.pid -or
        [IO.Path]::GetFullPath($OnboardingProcess[0].ExecutablePath) -ine [IO.Path]::GetFullPath($OnboardingPart.executable) -or
        [math]::Floor(([datetime]$OnboardingProcess[0].CreationDate).ToUniversalTime().Ticks/10) -ne [math]::Floor(([datetime]$OnboardingPart.created).ToUniversalTime().Ticks/10)) { throw 'Process identity lost' }
    $OnboardingData=[IO.Path]::GetFullPath($OnboardingPart.dataDirectory)
    if (-not $OnboardingData.StartsWith($OnboardingContext.Root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Component path escaped' }
    Assert-OnboardingPathChain $OnboardingData
    if ($OnboardingProcess[0].CommandLine -notmatch [regex]::Escape($OnboardingData)) { throw 'Component command identity lost' }
    foreach ($OnboardingArgument in $OnboardingPart.arguments) {
        if ($OnboardingProcess[0].CommandLine -notmatch [regex]::Escape($OnboardingArgument)) { throw 'Component arguments changed' }
    }
    if (-not $OnboardingStarting) {
        foreach ($OnboardingPort in @($OnboardingPart.port)+@($OnboardingPart.extraPorts)) {
            if (-not $OnboardingPort) { continue }
            $OnboardingListeners=@(Get-OnboardingListeners $OnboardingPort)
            if ($OnboardingListeners.Count -ne 1 -or $OnboardingListeners[0].OwningProcess -ne $OnboardingPart.pid -or $OnboardingListeners[0].LocalAddress -ne '127.0.0.1') { throw 'Listener identity lost' }
        }
    }
    if ($OnboardingComponent -like 'mysql*') {
        $OnboardingUuidLines=@([IO.File]::ReadAllLines((Join-Path $OnboardingData 'auto.cnf'))|Where-Object{$_ -match '^server-uuid='})
        if ($OnboardingUuidLines.Count -ne 1 -or ($OnboardingUuidLines[0] -split '=',2)[1] -cne $OnboardingPart.serverUuid -or $OnboardingPart.serverUuid -cnotmatch '\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z' -or $OnboardingPart.serverUuid -eq [guid]::Empty.ToString()) { throw 'Offline MySQL UUID identity lost' }
    }
}
function Assert-OnboardingAllProcesses($OnboardingContext) {
    foreach ($OnboardingComponent in @($OnboardingContext.Journal.components.Keys)) {
        if ($OnboardingContext.Journal.components[$OnboardingComponent].status -eq 'running') { Assert-OnboardingIdentity $OnboardingContext $OnboardingComponent }
    }
}
function Get-OnboardingProjectRoot {
    $OnboardingSource=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    return (Split-Path (Split-Path $OnboardingSource -Parent) -Parent)
}
function ConvertTo-OnboardingMap($OnboardingValue) {
    if ($null -eq $OnboardingValue -or $OnboardingValue -is [string] -or $OnboardingValue -is [ValueType]) { return $OnboardingValue }
    if ($OnboardingValue -is [array]) { return ,@($OnboardingValue|ForEach-Object{ConvertTo-OnboardingMap $_}) }
    $OnboardingMap=[ordered]@{}
    foreach($OnboardingProperty in $OnboardingValue.PSObject.Properties) { $OnboardingMap[$OnboardingProperty.Name]=ConvertTo-OnboardingMap $OnboardingProperty.Value }
    return $OnboardingMap
}
function Read-OnboardingFixtureContext([string]$OnboardingFixturePath,[string]$OnboardingExpectedOwner,[switch]$AllowStopped) {
    $ErrorActionPreference='Stop'
    if ($OnboardingExpectedOwner -cnotmatch '\A[0-9a-f]{32}\z' -or $OnboardingExpectedOwner -ceq ('0'*32)) { throw 'Explicit owner required' }
    $OnboardingProject=Get-OnboardingProjectRoot
    $OnboardingRoot=[IO.Path]::GetFullPath((Join-Path $OnboardingProject ('.local/runtime/attachments-'+$OnboardingExpectedOwner)))
    if (-not [IO.Path]::IsPathRooted($OnboardingFixturePath) -or [IO.Path]::GetFullPath($OnboardingFixturePath) -cne (Join-Path $OnboardingRoot 'fixture.json')) { throw 'Fixture path/owner mismatch' }
    Assert-OnboardingPathChain $OnboardingFixturePath
    Assert-OnboardingPrivateParent $OnboardingRoot
    Assert-OnboardingPrivateParent $OnboardingFixturePath
    if ([IO.File]::ReadAllText((Join-Path $OnboardingRoot 'owner.id')) -cne $OnboardingExpectedOwner) { throw 'Fixture owner marker mismatch' }
    $OnboardingManifest=ConvertTo-OnboardingMap ([IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json -ErrorAction Stop)
    if ($OnboardingManifest.ownerId -cne $OnboardingExpectedOwner -or $OnboardingManifest.runtimeRoot -cne $OnboardingRoot -or $OnboardingManifest.sourceCommit -cnotmatch '\A[0-9a-f]{40}\z' -or $OnboardingManifest.schemaVersion -cne '005' -or $OnboardingManifest.state -notin @('running','stopped','failed')) { throw 'Fixture deployment identity incomplete' }
    if (-not $AllowStopped -and $OnboardingManifest.state -ne 'running') { throw 'Fixture is not running' }
    $OnboardingJournalPath=Join-Path $OnboardingRoot 'journal.json'
    Assert-OnboardingPathChain $OnboardingJournalPath; Assert-OnboardingPrivateParent $OnboardingJournalPath
    $OnboardingJournal=ConvertTo-OnboardingMap ([IO.File]::ReadAllText($OnboardingJournalPath)|ConvertFrom-Json -ErrorAction Stop)
    if ($OnboardingJournal.ownerId -cne $OnboardingExpectedOwner -or $OnboardingJournal.sourceCommit -cne $OnboardingManifest.sourceCommit -or $OnboardingJournal.schemaVersion -cne '005' -or $OnboardingJournal.state -ne $OnboardingManifest.state) { throw 'Journal deployment identity differs' }
    $OnboardingOptions=@{NewRunId=$OnboardingExpectedOwner;MySqlBin=(Split-Path $OnboardingManifest.mysql.clientExecutable -Parent)}
    foreach($OnboardingTool in @('JavaHome','TomcatHome','WildFlyHome','MinioExecutable','MinioClientExecutable','MavenExecutable','ConnectorJar')) {
        if ($OnboardingManifest.tools.host.$OnboardingTool) { $OnboardingOptions[$OnboardingTool]=$OnboardingManifest.tools.host.$OnboardingTool }
    }
    $OnboardingContext=@{Root=$OnboardingRoot;Options=$OnboardingOptions;Journal=$OnboardingJournal;Manifest=$OnboardingManifest;Ports=@{}}
    foreach($OnboardingName in @('mysql','mysqlMigration','storage','paas','portal')) {
        $OnboardingContext.Ports[$OnboardingName]=[int]$OnboardingJournal.components[$OnboardingName].port
        $OnboardingExtra=@($OnboardingJournal.components[$OnboardingName].extraPorts)
        if ($OnboardingExtra.Count) { $OnboardingContext.Ports[(@{'storage'='console';'paas'='management';'portal'='shutdown'}[$OnboardingName])]=[int]$OnboardingExtra[0] }
    }
    $OnboardingUsed=New-Object 'Collections.Generic.HashSet[int]'
    foreach($OnboardingName in @('mysql','mysqlMigration','storage','paas','portal')) {
        $OnboardingPart=$OnboardingManifest[$OnboardingName]
        $OnboardingJournalPart=$OnboardingJournal.components[$OnboardingName]
        foreach($OnboardingField in @('pid','created','executable','dataDirectory','port','status','serverUuid')) { if ([string]$OnboardingPart[$OnboardingField] -cne [string]$OnboardingJournalPart[$OnboardingField]) { throw 'Manifest/journal component identity differs' } }
        if ($OnboardingPart.status -eq 'running') { }
        elseif ($OnboardingPart.status -eq 'stopped' -and $AllowStopped) { }
        elseif ($OnboardingPart.status -in @('starting','intent') -and $AllowStopped -and $OnboardingManifest.state -eq 'failed') { }
        else { throw 'Fixture component is not running' }
        foreach($OnboardingPort in @($OnboardingPart.port)+@($OnboardingPart.extraPorts)) {
            if (-not $OnboardingPort -or $OnboardingPort -in @(3318,8180,8188,10090) -or -not $OnboardingUsed.Add([int]$OnboardingPort)) { throw 'Fixture ports overlap or are protected' }
        }
        $OnboardingDataLeaf=if($OnboardingName -eq 'paas'){'paas-base'}elseif($OnboardingName -eq 'portal'){'portal-base'}elseif($OnboardingName -eq 'storage'){'storage-data'}else{$OnboardingName+'-data'}
        if ($OnboardingPart.dataDirectory -cne (Join-Path $OnboardingRoot $OnboardingDataLeaf)) { throw 'Component directory mismatch' }
        if ($OnboardingPart.url -cne ('http://127.0.0.1:'+$OnboardingPart.port)) { throw 'Component endpoint mismatch' }
        if ($OnboardingName -like 'mysql*') {
            $OnboardingCredential=Join-Path $OnboardingRoot ($OnboardingName+'-client.cnf')
            if ($OnboardingPart.credentialFile -cne $OnboardingCredential -or $OnboardingPart.schemaVersion -cne '005' -or $OnboardingPart.authenticated -ne $true -or $OnboardingPart.database -cne 'medical_cloud_course' -or $OnboardingPart.host -cne '127.0.0.1' -or $OnboardingPart.clientExecutable -cne (Join-Path $OnboardingContext.Options.MySqlBin 'mysql.exe')) { throw 'MySQL metadata mismatch' }
            Assert-OnboardingPathChain $OnboardingCredential; Assert-OnboardingPrivateParent $OnboardingCredential
        }
        if ($OnboardingPart.status -eq 'running') { Assert-OnboardingIdentity $OnboardingContext $OnboardingName }
    }
    # No SQL, login, native command, journal write or service mutation here.
    # Call Assert-OnboardingMysqlLive explicitly immediately before SQL work.
    return $OnboardingContext
}
function New-OnboardingContainerConfiguration($OnboardingContext) {
    $OnboardingPortal=Join-Path $OnboardingContext.Root 'portal-base'
    $OnboardingPaas=Join-Path $OnboardingContext.Root 'paas-base'
    foreach ($OnboardingDirectory in @('portal-base','portal-base/logs','portal-base/webapps','portal-base/temp','portal-base/work','paas-base','paas-base/data','paas-base/deployments','paas-base/log','paas-base/tmp')) { [IO.Directory]::CreateDirectory((Join-Path $OnboardingContext.Root $OnboardingDirectory))|Out-Null }
    Copy-Item -LiteralPath (Join-Path $OnboardingContext.Options.TomcatHome 'conf') -Destination (Join-Path $OnboardingPortal 'conf') -Recurse
    Copy-Item -LiteralPath (Join-Path $OnboardingContext.Options.WildFlyHome 'standalone/configuration') -Destination (Join-Path $OnboardingPaas 'configuration') -Recurse
    [xml]$OnboardingXml=[IO.File]::ReadAllText((Join-Path $OnboardingPortal 'conf/server.xml'))
    $OnboardingXml.Server.SetAttribute('port',[string]$OnboardingContext.Ports.shutdown)
    $OnboardingXml.Server.SetAttribute('address','127.0.0.1')
    $OnboardingXml.Server.SetAttribute('shutdown',$OnboardingContext.Secrets.tomcatShutdown)
    $OnboardingHttpCount=0
    foreach ($OnboardingConnector in @($OnboardingXml.Server.Service.Connector)) {
        if ($OnboardingConnector.protocol -match 'HTTP/1.1|Http11') { $OnboardingHttpCount++; $OnboardingConnector.SetAttribute('port',[string]$OnboardingContext.Ports.portal); $OnboardingConnector.SetAttribute('address','127.0.0.1'); $OnboardingConnector.RemoveAttribute('redirectPort') }
        else { [void]$OnboardingConnector.ParentNode.RemoveChild($OnboardingConnector) }
    }
    if ($OnboardingHttpCount -ne 1) { throw 'Expected one Tomcat HTTP connector' }
    Write-OnboardingPrivate (Join-Path $OnboardingPortal 'conf/server.xml') $OnboardingXml.OuterXml
    [xml]$OnboardingXml=[IO.File]::ReadAllText((Join-Path $OnboardingPaas 'configuration/standalone.xml'))
    foreach ($OnboardingInterface in @($OnboardingXml.SelectNodes("//*[local-name()='interface']"))) {
        $OnboardingInterface.InnerXml='<inet-address xmlns="'+$OnboardingInterface.NamespaceURI+'" value="127.0.0.1"/>'
    }
    $OnboardingBindings=$OnboardingXml.SelectSingleNode("//*[local-name()='socket-binding-group']")
    $OnboardingBindings.SetAttribute('port-offset','0')
    foreach ($OnboardingBinding in @($OnboardingBindings.SelectNodes("*[local-name()='socket-binding']"))) {
        if ($OnboardingBinding.GetAttribute('name') -eq 'http') { $OnboardingBinding.SetAttribute('port',[string]$OnboardingContext.Ports.paas) }
        elseif ($OnboardingBinding.GetAttribute('name') -eq 'management-http') { $OnboardingBinding.SetAttribute('port',[string]$OnboardingContext.Ports.management) }
        else { $OnboardingBinding.SetAttribute('port','0') }
    }
    $OnboardingDatasources=$OnboardingXml.SelectSingleNode("//*[local-name()='datasources' and *[local-name()='drivers']]")
    if (-not $OnboardingDatasources) { throw 'WildFly datasource configuration missing' }
    foreach ($OnboardingExisting in @($OnboardingDatasources.SelectNodes("*[local-name()='datasource' or local-name()='xa-datasource']"))) { [void]$OnboardingDatasources.RemoveChild($OnboardingExisting) }
    $OnboardingDriverList=$OnboardingDatasources.SelectSingleNode("*[local-name()='drivers']")
    if (-not $OnboardingDriverList) { throw 'WildFly driver list missing' }
    foreach ($OnboardingExisting in @($OnboardingDriverList.SelectNodes("*[local-name()='driver' and @name='mysql']"))) { [void]$OnboardingDriverList.RemoveChild($OnboardingExisting) }
    $OnboardingDriverNode=$OnboardingXml.CreateElement('driver',$OnboardingDatasources.NamespaceURI)
    $OnboardingDriverNode.SetAttribute('name','mysql'); $OnboardingDriverNode.SetAttribute('module','com.mysql')
    [void]$OnboardingDriverList.AppendChild($OnboardingDriverNode)
    $OnboardingDatasource=$OnboardingXml.CreateElement('datasource',$OnboardingDatasources.NamespaceURI)
    $OnboardingDatasource.SetAttribute('jndi-name','java:/jdbc/MedicalCloudCourseDS'); $OnboardingDatasource.SetAttribute('pool-name','MedicalCloudCourseDS'); $OnboardingDatasource.SetAttribute('enabled','true'); $OnboardingDatasource.SetAttribute('use-java-context','true')
    # allowPublicKeyRetrieval is required for the MySQL 8 caching_sha2 account over loopback without TLS.
    $OnboardingDatasource.InnerXml='<connection-url xmlns="'+$OnboardingDatasources.NamespaceURI+'">jdbc:mysql://127.0.0.1:'+$OnboardingContext.Ports.mysql+'/medical_cloud_course?useSSL=false&amp;allowPublicKeyRetrieval=true&amp;serverTimezone=UTC&amp;useUnicode=true&amp;characterEncoding=UTF-8</connection-url><driver xmlns="'+$OnboardingDatasources.NamespaceURI+'">'+$OnboardingContext.Driver+'</driver><security xmlns="'+$OnboardingDatasources.NamespaceURI+'"><user-name>'+$OnboardingContext.Secrets.dbUser+'</user-name><password>'+$OnboardingContext.Secrets.dbPassword+'</password></security>'
    [void]$OnboardingDatasources.InsertBefore($OnboardingDatasource,$OnboardingDatasources.FirstChild)
    Write-OnboardingPrivate (Join-Path $OnboardingPaas 'configuration/standalone.xml') $OnboardingXml.OuterXml
}
function Set-OnboardingRuntimeEnvironment($OnboardingContext) {
    $OnboardingStorage=$OnboardingContext.Journal.components.storage
    if (-not $OnboardingStorage -or -not $OnboardingStorage.internalEndpoint) { throw 'Storage identity missing' }
    $OnboardingCredentials=$OnboardingContext.Secrets
    if (-not $OnboardingCredentials) { $OnboardingCredentials=ConvertTo-OnboardingMap ([IO.File]::ReadAllText((Join-Path $OnboardingContext.Root 'credentials.json'))|ConvertFrom-Json -ErrorAction Stop) }
    if (-not $OnboardingContext.Ports.portal -or -not $OnboardingContext.Ports.paas) { throw 'Portal/PaaS ports missing' }
    $env:COURSE_ATTACHMENTS_ENABLED='true'
    $env:COURSE_ATTACHMENTS_TEMP_DIR=Join-Path $OnboardingContext.Root 'attachments-temp'
    $env:COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS='http://127.0.0.1:'+$OnboardingContext.Ports.portal
    $env:COURSE_S3_INTERNAL_ENDPOINT=$OnboardingStorage.internalEndpoint
    $env:COURSE_S3_PUBLIC_ENDPOINT=$OnboardingStorage.publicEndpoint
    $env:COURSE_S3_REGION='us-east-1'
    $env:COURSE_S3_BUCKET=$OnboardingStorage.bucket
    $env:COURSE_S3_PREFIX=$OnboardingStorage.prefix
    $env:COURSE_S3_ACCESS_KEY=$OnboardingCredentials.storageAppKey
    $env:COURSE_S3_SECRET_KEY=$OnboardingCredentials.storageAppSecret
    $env:MEDICAL_COURSE_PAAS_URL='http://127.0.0.1:'+$OnboardingContext.Ports.paas+'/paas/api'
}
function New-OnboardingRandomToken {
    $OnboardingBytes=New-Object byte[] 32
    $OnboardingRng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $OnboardingRng.GetBytes($OnboardingBytes) } finally { $OnboardingRng.Dispose() }
    return [BitConverter]::ToString($OnboardingBytes).Replace('-','').ToLowerInvariant()
}
function Get-OnboardingToolHash([string]$OnboardingPath) { (Get-FileHash -LiteralPath $OnboardingPath -Algorithm SHA256).Hash.ToLowerInvariant() }
function New-OnboardingIamArchive($OnboardingContext) {
    # Verified minio@9e49d5e cmd/admin-handlers-users.go / iam-store.go and
    # madmin-go/v3@v3.0.109 user-commands.go. No secret-bearing argv fallback.
    $OnboardingPolicyName='onboarding-'+$OnboardingContext.Options.NewRunId.Substring(0,12)
    $OnboardingPolicy=[ordered]@{Version='2012-10-17';Statement=@(
        @{Effect='Allow';Action=@('s3:ListBucket');Resource=@('arn:aws:s3:::'+$OnboardingContext.Bucket);Condition=@{StringLike=@{'s3:prefix'=@($OnboardingContext.Prefix+'*')}}},
        @{Effect='Allow';Action=@('s3:GetBucketVersioning','s3:GetBucketObjectLockConfiguration','s3:GetBucketPolicy');Resource=@('arn:aws:s3:::'+$OnboardingContext.Bucket)},
        @{Effect='Allow';Action=@('s3:GetObject','s3:PutObject','s3:DeleteObject');Resource=@('arn:aws:s3:::'+$OnboardingContext.Bucket+'/'+$OnboardingContext.Prefix+'*')}
    )}
    $OnboardingEntries=@{
        'iam-assets/policies.json'=@{$OnboardingPolicyName=$OnboardingPolicy};
        'iam-assets/users.json'=@{$OnboardingContext.Secrets.storageAppKey=@{secretKey=$OnboardingContext.Secrets.storageAppSecret;status='enabled'}};
        'iam-assets/user_mappings.json'=@{$OnboardingContext.Secrets.storageAppKey=@{version=1;policy=$OnboardingPolicyName;updatedAt=[datetime]::UtcNow.ToString('o')}}
    }
    $OnboardingArchive=Join-Path $OnboardingContext.Root 'iam-import.zip'
    $OnboardingZip=[IO.Compression.ZipFile]::Open($OnboardingArchive,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($OnboardingName in $OnboardingEntries.Keys) {
            $OnboardingStream=$OnboardingZip.CreateEntry($OnboardingName).Open()
            try { $OnboardingBytes=[Text.Encoding]::UTF8.GetBytes(($OnboardingEntries[$OnboardingName]|ConvertTo-Json -Depth 12)); $OnboardingStream.Write($OnboardingBytes,0,$OnboardingBytes.Length) } finally { $OnboardingStream.Dispose() }
        }
    } finally { $OnboardingZip.Dispose() }
    Set-OnboardingPrivateAcl $OnboardingArchive
    return $OnboardingArchive
}
function Test-OnboardingNonempty($OnboardingValue) {
    if ($null -eq $OnboardingValue) { return $false }
    if ($OnboardingValue -is [string]) { return $OnboardingValue.Length -gt 0 }
    if ($OnboardingValue -is [ValueType]) { return [bool]$OnboardingValue }
    if ($OnboardingValue -is [array]) { return $OnboardingValue.Count -gt 0 }
    foreach($OnboardingProperty in $OnboardingValue.PSObject.Properties) { if(Test-OnboardingNonempty $OnboardingProperty.Value){return $true} }
    return $false
}
function Assert-OnboardingIamResult([string]$OnboardingOutput,[string]$OnboardingExpectedUser,[string]$OnboardingExpectedPolicy) {
    # Pinned mc prints ONE pretty-printed ImportIAMResult, without a status
    # envelope. Legacy fallback prints nothing in JSON mode and is not proof.
    if ([string]::IsNullOrWhiteSpace($OnboardingOutput)) { throw 'IAM import result missing' }
    $OnboardingResult=$OnboardingOutput|ConvertFrom-Json -ErrorAction Stop
    if ((Test-OnboardingNonempty $OnboardingResult.failed) -or (Test-OnboardingNonempty $OnboardingResult.skipped) -or (Test-OnboardingNonempty $OnboardingResult.removed)) { throw 'IAM import failed, skipped or removed entities' }
    if (@($OnboardingResult.added.users).Count -ne 1 -or $OnboardingResult.added.users[0] -cne $OnboardingExpectedUser -or
        @($OnboardingResult.added.policies).Count -ne 1 -or $OnboardingResult.added.policies[0] -cne $OnboardingExpectedPolicy -or
        @($OnboardingResult.added.userPolicies).Count -ne 1) { throw 'IAM import did not add exact expected entities' }
    $OnboardingBindings=@($OnboardingResult.added.userPolicies[0].PSObject.Properties)
    if ($OnboardingBindings.Count -ne 1 -or $OnboardingBindings[0].Name -cne $OnboardingExpectedUser -or @($OnboardingBindings[0].Value).Count -ne 1 -or $OnboardingBindings[0].Value[0] -cne $OnboardingExpectedPolicy) { throw 'IAM import policy binding mismatch' }
    foreach($OnboardingName in @('groups','serviceAccounts','groupPolicies','stsPolicies')) { if (Test-OnboardingNonempty $OnboardingResult.added.$OnboardingName) { throw 'Unexpected imported IAM entities' } }
}
function Invoke-OnboardingStep($OnboardingContext,$OnboardingExecutable,$OnboardingArguments,$OnboardingInput,[string]$OnboardingLabel) {
    Assert-OnboardingAllProcesses $OnboardingContext
    $OnboardingContext.Journal.stage=$OnboardingLabel
    Save-OnboardingJournal $OnboardingContext
    $OnboardingResult=Invoke-OnboardingNative $OnboardingExecutable $OnboardingArguments $OnboardingContext.Root $OnboardingInput $OnboardingLabel
    $OnboardingContext.Journal.nativeExits+=@{step=$OnboardingLabel;exitCode=$OnboardingResult.ExitCode}
    Assert-OnboardingAllProcesses $OnboardingContext
    Save-OnboardingJournal $OnboardingContext
    if ($OnboardingResult.ExitCode -ne 0) { throw ('Native step stopped: '+$OnboardingLabel) }
    return $OnboardingResult.Output
}
function Get-OnboardingPort($OnboardingUsed) {
    do {
        $OnboardingListener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0)
        $OnboardingListener.Start()
        try { $OnboardingCandidate=([Net.IPEndPoint]$OnboardingListener.LocalEndpoint).Port } finally { $OnboardingListener.Stop() }
    } while ($OnboardingCandidate -in @(3318,8180,8188,10090) -or -not $OnboardingUsed.Add($OnboardingCandidate))
    return $OnboardingCandidate
}
function Start-OnboardingComponent($OnboardingContext,[string]$OnboardingName,[string]$OnboardingExe,[string[]]$OnboardingArguments,[string]$OnboardingData,[int]$OnboardingPort,[int[]]$OnboardingExtraPorts=@(),[string]$OnboardingUuid='') {
    Assert-OnboardingAllProcesses $OnboardingContext
    foreach($OnboardingCandidate in @($OnboardingPort)+$OnboardingExtraPorts) { if (@(Get-OnboardingListeners $OnboardingCandidate).Count) { throw 'Allocated port is already listening' } }
    $OnboardingContext.Journal.stage=$OnboardingName+'-start'
    $OnboardingEntry=[ordered]@{status='intent';executable=$OnboardingExe;arguments=$OnboardingArguments;dataDirectory=$OnboardingData;port=$OnboardingPort;extraPorts=$OnboardingExtraPorts;serverUuid=$OnboardingUuid;pid=0;created=$null;url=('http://127.0.0.1:'+$OnboardingPort)}
    $OnboardingExisting=$OnboardingContext.Journal.components[$OnboardingName]
    if ($OnboardingExisting) {
        foreach ($OnboardingKey in @($OnboardingExisting.Keys)) { if (-not $OnboardingEntry.Contains($OnboardingKey)) { $OnboardingEntry[$OnboardingKey]=$OnboardingExisting[$OnboardingKey] } }
        if (-not $OnboardingUuid) { $OnboardingEntry.serverUuid=$OnboardingExisting.serverUuid }
    }
    $OnboardingContext.Journal.components[$OnboardingName]=$OnboardingEntry
    Save-OnboardingJournal $OnboardingContext
    $OnboardingStarted=Invoke-OnboardingNative $OnboardingExe $OnboardingArguments $OnboardingContext.Root $null ($OnboardingName+'-start') -OnboardingStart
    if ($OnboardingStarted.ExitCode -ne 0 -or $OnboardingStarted.ProcessId -lt 1) { throw 'Component failed to start' }
    $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
    $OnboardingPart.pid=$OnboardingStarted.ProcessId; $OnboardingPart.created=$OnboardingStarted.Created; $OnboardingPart.status='starting'
    Save-OnboardingJournal $OnboardingContext
    for($OnboardingAttempt=0;$OnboardingAttempt -lt 240;$OnboardingAttempt++) {
        Assert-OnboardingIdentity $OnboardingContext $OnboardingName -OnboardingStarting
        $OnboardingReady=$true
        foreach($OnboardingCandidate in @($OnboardingPort)+$OnboardingExtraPorts) {
            $OnboardingListeners=@(Get-OnboardingListeners $OnboardingCandidate)
            if ($OnboardingListeners.Count -eq 0) { $OnboardingReady=$false; continue }
            if ($OnboardingListeners.Count -ne 1 -or $OnboardingListeners[0].OwningProcess -ne $OnboardingPart.pid -or $OnboardingListeners[0].LocalAddress -ne '127.0.0.1') { throw 'New listener ownership mismatch' }
        }
        if ($OnboardingReady) { $OnboardingPart.status='running'; Save-OnboardingJournal $OnboardingContext; return }
        Start-Sleep -Milliseconds 250
    }
    throw 'Component readiness timed out'
}
function Assert-OnboardingMysqlLive($OnboardingContext,[string]$OnboardingName) {
    Assert-OnboardingIdentity $OnboardingContext $OnboardingName
    $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
    $OnboardingOption=if($OnboardingPart.authenticated){'--defaults-extra-file=./'+$OnboardingName+'-client.cnf'}else{'--no-defaults'}
    $OnboardingIdentity=Invoke-OnboardingStep $OnboardingContext (Join-Path $OnboardingContext.Options.MySqlBin 'mysql.exe') @($OnboardingOption,'--protocol=TCP','--host=127.0.0.1',('--port='+$OnboardingPart.port),'--user=root','--connect-timeout=3','--batch','--skip-column-names','--default-character-set=utf8mb4') 'SELECT @@server_uuid,@@port,HEX(@@datadir),@@version_compile_os;' ($OnboardingName+'-identity')
    $OnboardingFields=$OnboardingIdentity.Trim() -split "`t"
    if ($OnboardingFields.Count -ne 4 -or $OnboardingFields[0] -cne $OnboardingPart.serverUuid -or $OnboardingFields[1] -cne [string]$OnboardingPart.port -or $OnboardingFields[2] -cnotmatch '\A(?:[0-9A-F]{2})+\z' -or $OnboardingFields[3] -notin @('Win32','Win64')) { throw 'Live MySQL UUID/port identity mismatch' }
    $OnboardingBytes=New-Object byte[] ($OnboardingFields[2].Length/2)
    for($OnboardingIndex=0;$OnboardingIndex -lt $OnboardingBytes.Length;$OnboardingIndex++) { $OnboardingBytes[$OnboardingIndex]=[Convert]::ToByte($OnboardingFields[2].Substring($OnboardingIndex*2,2),16) }
    $OnboardingMatch=$false
    foreach($OnboardingCodePage in @(65001,[Text.Encoding]::Default.CodePage)) {
        try {
            $OnboardingEncoding=[Text.Encoding]::GetEncoding($OnboardingCodePage,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)
            $OnboardingDecoded=$OnboardingEncoding.GetString($OnboardingBytes)
            if ([IO.Path]::IsPathRooted($OnboardingDecoded) -and [IO.Path]::GetFullPath($OnboardingDecoded).TrimEnd('\','/') -ieq $OnboardingPart.dataDirectory) { $OnboardingMatch=$true }
        } catch { }
    }
    if (-not $OnboardingMatch) { throw 'Live MySQL data directory mismatch' }
}
function Invoke-OnboardingSql($OnboardingContext,[string]$OnboardingName,[string]$OnboardingSql,[string]$OnboardingLabel) {
    Assert-OnboardingMysqlLive $OnboardingContext $OnboardingName
    $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
    $OnboardingOption=if($OnboardingPart.authenticated){'--defaults-extra-file=./'+$OnboardingName+'-client.cnf'}else{'--no-defaults'}
    Invoke-OnboardingStep $OnboardingContext (Join-Path $OnboardingContext.Options.MySqlBin 'mysql.exe') @($OnboardingOption,'--protocol=TCP','--host=127.0.0.1',('--port='+$OnboardingPart.port),'--user=root','--connect-timeout=3','--batch','--skip-column-names','--default-character-set=utf8mb4') $OnboardingSql $OnboardingLabel
}
function Invoke-OnboardingHttp([string]$OnboardingUri,[string]$OnboardingBody='') {
    $OnboardingAddress=[uri]$OnboardingUri
    if ($OnboardingAddress.Host -ne '127.0.0.1' -or $OnboardingAddress.Scheme -ne 'http') { throw 'Non-loopback HTTP refused' }
    $OnboardingRequest=[Net.HttpWebRequest]::Create($OnboardingAddress)
    $OnboardingRequest.AllowAutoRedirect=$false; $OnboardingRequest.Proxy=$null; $OnboardingRequest.Timeout=5000
    if ($OnboardingBody) {
        $OnboardingRequest.Method='POST'; $OnboardingRequest.ContentType='application/json; charset=utf-8'
        $OnboardingBytes=[Text.Encoding]::UTF8.GetBytes($OnboardingBody); $OnboardingRequest.ContentLength=$OnboardingBytes.Length
        $OnboardingStream=$OnboardingRequest.GetRequestStream(); try{$OnboardingStream.Write($OnboardingBytes,0,$OnboardingBytes.Length)}finally{$OnboardingStream.Dispose()}
    }
    $OnboardingResponse=$OnboardingRequest.GetResponse()
    try { return [pscustomobject]@{StatusCode=[int]$OnboardingResponse.StatusCode} } finally { $OnboardingResponse.Dispose() }
}
function Invoke-OnboardingBootstrap($OnboardingContext) {
    $OnboardingOptions=$OnboardingContext.Options
    if ((Get-OnboardingToolHash $OnboardingOptions.MinioExecutable) -cne '1e534498e2468a4b82c2e98300a883492af3107e666af35aba9875ef7853162f' -or
        (Get-OnboardingToolHash $OnboardingOptions.MinioClientExecutable) -cne 'c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157') { throw 'Pinned object-store tool identity mismatch' }
    $OnboardingContext.Journal.tools=@{minioSource='9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a';mcSource='7394ce0dd2a80935aded936b09fa12cbb3cb8096';notice='AGPLv3; archived server; no security updates; local fictional-data acceptance only, not production';host=@{JavaHome=$OnboardingOptions.JavaHome;TomcatHome=$OnboardingOptions.TomcatHome;WildFlyHome=$OnboardingOptions.WildFlyHome;MavenExecutable=$OnboardingOptions.MavenExecutable;ConnectorJar=$OnboardingOptions.ConnectorJar;MinioExecutable=$OnboardingOptions.MinioExecutable;MinioClientExecutable=$OnboardingOptions.MinioClientExecutable}}
    $OnboardingContext.Secrets=@{mysqlRootPassword=(New-OnboardingRandomToken);migrationRootPassword=(New-OnboardingRandomToken);dbUser=('hob_'+$OnboardingOptions.NewRunId.Substring(0,12));dbPassword=(New-OnboardingRandomToken);storageRootKey=('root'+$OnboardingOptions.NewRunId.Substring(0,20));storageRootSecret=(New-OnboardingRandomToken);storageAppKey=('app'+$OnboardingOptions.NewRunId.Substring(0,20));storageAppSecret=(New-OnboardingRandomToken);tomcatShutdown=(New-OnboardingRandomToken)}
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'credentials.json') ($OnboardingContext.Secrets|ConvertTo-Json)
    $OnboardingUsed=New-Object 'Collections.Generic.HashSet[int]'
    $OnboardingContext.Ports=@{}
    foreach($OnboardingName in @('mysql','mysqlMigration','storage','console','portal','shutdown','paas','management')) { $OnboardingContext.Ports[$OnboardingName]=Get-OnboardingPort $OnboardingUsed }
    foreach($OnboardingName in @('mysql-data','mysqlMigration-data','storage-data','mc-config','attachments-temp','hash-tool')) { [IO.Directory]::CreateDirectory((Join-Path $OnboardingContext.Root $OnboardingName))|Out-Null }
    # Verify and stage both WARs and runtime dependencies before initializing SQL.
    # The Maven build also emits attachment probe WARs into paas/target; select the
    # deployable artifact by exact name instead of counting every *.war file.
    foreach($OnboardingModule in @('paas','portal')) {
        $OnboardingWarPath=Join-Path $OnboardingContext.Snapshot ('course/'+$OnboardingModule+'/target/'+$OnboardingModule+'.war')
        if (-not (Test-Path -LiteralPath $OnboardingWarPath -PathType Leaf)) { throw ('Built '+$OnboardingModule+' WAR missing') }
        $OnboardingContext[$OnboardingModule+'War']=$OnboardingWarPath
        Expand-OnboardingSource $OnboardingWarPath (Join-Path $OnboardingContext.Root ($OnboardingModule+'-unpacked'))
        # PaaS is a Servlet 3 / JAX-RS WAR without web.xml; Portal is a classic web.xml WAR.
        if ($OnboardingModule -eq 'paas') {
            foreach ($OnboardingMarker in @('WEB-INF/classes/META-INF/persistence.xml','WEB-INF/classes/com/medcloud/course/paas/api/CourseApplication.class','WEB-INF/lib')) {
                if (-not (Test-Path -LiteralPath (Join-Path $OnboardingContext.Root ('paas-unpacked/'+$OnboardingMarker)))) { throw 'Built paas WAR incomplete' }
            }
        } elseif (-not (Test-Path -LiteralPath (Join-Path $OnboardingContext.Root 'portal-unpacked/WEB-INF/web.xml') -PathType Leaf)) { throw 'Built portal WAR incomplete' }
    }
    # The JDBC driver is installed as a private WildFly module (same proven shape as
    # configure-wildfly.ps1); the teacher installation stays read-only and untouched.
    $OnboardingDriverPath=[IO.Path]::GetFullPath($OnboardingOptions.ConnectorJar)
    Assert-OnboardingPathChain $OnboardingDriverPath
    if (-not (Test-Path -LiteralPath $OnboardingDriverPath -PathType Leaf)) { throw 'Connector/J jar missing' }
    if ((Get-OnboardingToolHash $OnboardingDriverPath) -cne 'e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9') { throw 'Connector/J identity mismatch' }
    $OnboardingBcrypt=@(Get-ChildItem -LiteralPath (Join-Path $OnboardingContext.Root 'paas-unpacked/WEB-INF/lib') -Filter 'jbcrypt-*.jar' -File)
    if ($OnboardingBcrypt.Count -ne 1) { throw 'Built WAR dependencies missing or ambiguous' }
    [xml]$OnboardingPersistence=[IO.File]::ReadAllText((Join-Path $OnboardingContext.Root 'paas-unpacked/WEB-INF/classes/META-INF/persistence.xml'))
    if ($OnboardingPersistence.SelectSingleNode("//*[local-name()='property' and @name='hibernate.hbm2ddl.auto' and @value='validate']") -eq $null) { throw 'JPA validate configuration required' }
    $OnboardingModulePath=Join-Path $OnboardingContext.Root 'paas-base/modules/com/mysql/main'
    [IO.Directory]::CreateDirectory($OnboardingModulePath)|Out-Null
    Copy-Item -LiteralPath $OnboardingDriverPath -Destination (Join-Path $OnboardingModulePath 'mysql-connector-j.jar')
    Copy-Item -LiteralPath (Join-Path $OnboardingContext.Snapshot 'course/deploy/mysql-module.xml') -Destination (Join-Path $OnboardingModulePath 'module.xml')
    $OnboardingContext.Driver='mysql'
    New-OnboardingContainerConfiguration $OnboardingContext
    Copy-Item -LiteralPath $OnboardingContext.portalWar -Destination (Join-Path $OnboardingContext.Root 'portal-base/webapps/portal.war')
    Copy-Item -LiteralPath $OnboardingContext.paasWar -Destination (Join-Path $OnboardingContext.Root 'paas-base/deployments/paas.war')
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'paas-base/deployments/paas.war.dodeploy') ''
    $OnboardingHashSource='import java.io.*; import org.mindrot.jbcrypt.BCrypt; public class OnboardingHash { public static void main(String[] a) throws Exception { BufferedReader r=new BufferedReader(new InputStreamReader(System.in,"UTF-8")); String p=r.readLine(); if(p==null)throw new IllegalArgumentException(); System.out.print(BCrypt.hashpw(p,BCrypt.gensalt(10))); }}'
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'hash-tool/OnboardingHash.java') $OnboardingHashSource
    Invoke-OnboardingStep $OnboardingContext (Join-Path $OnboardingOptions.JavaHome 'bin/javac.exe') @('-encoding','UTF-8','-cp',$OnboardingBcrypt[0].FullName,(Join-Path $OnboardingContext.Root 'hash-tool/OnboardingHash.java')) $null 'hash-compile'|Out-Null
    $OnboardingAccounts=@()
    foreach($OnboardingAccount in @(@('patient_demo','PATIENT'),@('doctor_a','DOCTOR'),@('doctor_b','DOCTOR'),@('hospital_a','HOSPITAL_ADMIN'),@('platform_admin','PLATFORM_ADMIN'),@('platform_second','PLATFORM_ADMIN'),@('disabled_user','PATIENT'))) {
        $OnboardingPassword=New-OnboardingRandomToken
        $OnboardingHash=Invoke-OnboardingStep $OnboardingContext (Join-Path $OnboardingOptions.JavaHome 'bin/java.exe') @(('-Djava.io.tmpdir='+$env:TEMP),'-cp',((Join-Path $OnboardingContext.Root 'hash-tool')+';'+$OnboardingBcrypt[0].FullName),'OnboardingHash') ($OnboardingPassword+"`n") 'password-hash'
        if ($OnboardingHash -cnotmatch '\A\$2[aby]\$10\$[./A-Za-z0-9]{53}\z') { throw 'BCrypt generation failed' }
        $OnboardingAccounts+=@{username=$OnboardingAccount[0];role=$OnboardingAccount[1];password=$OnboardingPassword;hash=$OnboardingHash}
    }
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'accounts.json') ($OnboardingAccounts|Select-Object username,role,password|ConvertTo-Json)
    foreach($OnboardingName in @('mysql','mysqlMigration')) {
        $OnboardingData=Join-Path $OnboardingContext.Root ($OnboardingName+'-data')
        $OnboardingServer=Join-Path $OnboardingOptions.MySqlBin 'mysqld.exe'
        $OnboardingBase=Split-Path $OnboardingOptions.MySqlBin -Parent
        Invoke-OnboardingStep $OnboardingContext $OnboardingServer @('--no-defaults','--initialize-insecure',('--basedir='+$OnboardingBase),('--datadir='+$OnboardingData)) $null ($OnboardingName+'-init')|Out-Null
        $OnboardingUuidLines=@([IO.File]::ReadAllLines((Join-Path $OnboardingData 'auto.cnf'))|Where-Object{$_ -match '^server-uuid='})
        if ($OnboardingUuidLines.Count -ne 1) { throw 'Initialized MySQL UUID missing' }
        $OnboardingUuid=($OnboardingUuidLines[0] -split '=',2)[1]
        if ($OnboardingUuid -cnotmatch '\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z' -or $OnboardingUuid -eq [guid]::Empty.ToString()) { throw 'Initialized MySQL UUID invalid' }
        Start-OnboardingComponent $OnboardingContext $OnboardingName $OnboardingServer @('--no-defaults','--no-monitor',('--basedir='+$OnboardingBase),('--datadir='+$OnboardingData),('--port='+$OnboardingContext.Ports[$OnboardingName]),'--mysqlx=0','--bind-address=127.0.0.1',('--log-error='+ (Join-Path $OnboardingContext.Root ($OnboardingName+'.err.log')))) $OnboardingData $OnboardingContext.Ports[$OnboardingName] @() $OnboardingUuid
        $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
        $OnboardingRootPassword=if($OnboardingName -eq 'mysql'){$OnboardingContext.Secrets.mysqlRootPassword}else{$OnboardingContext.Secrets.migrationRootPassword}
        Write-OnboardingPrivate (Join-Path $OnboardingContext.Root ($OnboardingName+'-client.cnf')) ("[client]`nuser=root`npassword="+$OnboardingRootPassword+"`nhost=127.0.0.1`nport="+$OnboardingPart.port+"`nprotocol=TCP`ndefault-character-set=utf8mb4`n")
        Invoke-OnboardingSql $OnboardingContext $OnboardingName ("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+$OnboardingRootPassword+"';") ($OnboardingName+'-password')|Out-Null
        $OnboardingPart.authenticated=$true
        $OnboardingPart.credentialFile=Join-Path $OnboardingContext.Root ($OnboardingName+'-client.cnf'); $OnboardingPart.clientExecutable=Join-Path $OnboardingOptions.MySqlBin 'mysql.exe'; $OnboardingPart.host='127.0.0.1'; $OnboardingPart.database='medical_cloud_course'
        $OnboardingSchema=if($OnboardingName -eq 'mysql'){Join-Path $OnboardingContext.Snapshot 'course/deploy/schema.sql'}else{Join-Path $OnboardingContext.Root 'baseline004.sql'}
        Invoke-OnboardingSql $OnboardingContext $OnboardingName ([IO.File]::ReadAllText($OnboardingSchema)) ($OnboardingName+'-schema')|Out-Null
        if ($OnboardingName -eq 'mysqlMigration') {
            Assert-OnboardingMysqlLive $OnboardingContext $OnboardingName
            $env:MEDICAL_COURSE_DB_USER='root'; $env:MEDICAL_COURSE_DB_PASSWORD=$OnboardingRootPassword
            try {
                Invoke-OnboardingStep $OnboardingContext 'powershell.exe' @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $OnboardingContext.Snapshot 'course/deploy/scripts/upgrade-hospital-onboarding-database.ps1'),'-MySqlCli',(Join-Path $OnboardingOptions.MySqlBin 'mysql.exe'),'-MySqlDumpCli',(Join-Path $OnboardingOptions.MySqlBin 'mysqldump.exe'),'-HostName','127.0.0.1','-Port',[string]$OnboardingPart.port,'-Database','medical_cloud_course','-ExpectedServerUuid',$OnboardingPart.serverUuid,'-BackupPath',(Join-Path $OnboardingContext.Root 'migration004-backup.sql')) $null 'migration-upgrade'|Out-Null
            } finally { $env:MEDICAL_COURSE_DB_USER=$null; $env:MEDICAL_COURSE_DB_PASSWORD=$null }
        }
        $OnboardingTables=Invoke-OnboardingSql $OnboardingContext $OnboardingName "SELECT COUNT(*),SUM(table_name='hospital_admin_operation') FROM information_schema.tables WHERE table_schema='medical_cloud_course' AND table_type='BASE TABLE';" ($OnboardingName+'-tables')
        if ($OnboardingTables.Trim() -cne "13`t1") { throw 'Complete 005 table count not verified' }
        $OnboardingPart.schemaVersion='005'
    }
    Invoke-OnboardingSql $OnboardingContext 'mysql' ([IO.File]::ReadAllText((Join-Path $OnboardingContext.Snapshot 'course/deploy/seed-demo.sql'))) 'mysql-seed'|Out-Null
    $OnboardingAccountSql="USE medical_cloud_course; INSERT INTO user_account(id,username,password_hash,role,status,tenant_id) SELECT '40000000-0000-0000-0000-000000000002','platform_second',password_hash,'PLATFORM_ADMIN','ACTIVE',NULL FROM user_account WHERE username='platform_admin';"
    foreach($OnboardingAccount in $OnboardingAccounts) { $OnboardingAccountSql+=" UPDATE user_account SET password_hash='"+$OnboardingAccount.hash+"' WHERE username='"+$OnboardingAccount.username+"';" }
    $OnboardingAccountSql+=" CREATE USER '"+$OnboardingContext.Secrets.dbUser+"'@'127.0.0.1' IDENTIFIED BY '"+$OnboardingContext.Secrets.dbPassword+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON medical_cloud_course.* TO '"+$OnboardingContext.Secrets.dbUser+"'@'127.0.0.1';"
    Invoke-OnboardingSql $OnboardingContext 'mysql' $OnboardingAccountSql 'mysql-accounts'|Out-Null
    $OnboardingContext.Bucket='medical-onboarding-'+$OnboardingOptions.NewRunId
    $OnboardingContext.Prefix='course-attachments/'
    $OnboardingArchive=New-OnboardingIamArchive $OnboardingContext
    $env:MINIO_ROOT_USER=$OnboardingContext.Secrets.storageRootKey; $env:MINIO_ROOT_PASSWORD=$OnboardingContext.Secrets.storageRootSecret; $env:MINIO_BROWSER='on'; $env:MINIO_UPDATE='off'
    try { Start-OnboardingComponent $OnboardingContext 'storage' $OnboardingOptions.MinioExecutable @('server',(Join-Path $OnboardingContext.Root 'storage-data'),'--address',('127.0.0.1:'+$OnboardingContext.Ports.storage),'--console-address',('127.0.0.1:'+$OnboardingContext.Ports.console)) (Join-Path $OnboardingContext.Root 'storage-data') $OnboardingContext.Ports.storage @($OnboardingContext.Ports.console) }
    finally { $env:MINIO_ROOT_USER=$null; $env:MINIO_ROOT_PASSWORD=$null }
    $env:MC_CONFIG_DIR=Join-Path $OnboardingContext.Root 'mc-config'; $env:MC_HOST_onboarding_fixture='http://'+$OnboardingContext.Secrets.storageRootKey+':'+$OnboardingContext.Secrets.storageRootSecret+'@127.0.0.1:'+$OnboardingContext.Ports.storage
    try {
        Invoke-OnboardingStep $OnboardingContext $OnboardingOptions.MinioClientExecutable @('mb',('onboarding_fixture/'+$OnboardingContext.Bucket)) $null 'bucket-create'|Out-Null
        Invoke-OnboardingStep $OnboardingContext $OnboardingOptions.MinioClientExecutable @('anonymous','set','none',('onboarding_fixture/'+$OnboardingContext.Bucket)) $null 'bucket-private'|Out-Null
        $OnboardingIamResult=Invoke-OnboardingStep $OnboardingContext $OnboardingOptions.MinioClientExecutable @('--json','admin','cluster','iam','import','onboarding_fixture',$OnboardingArchive) $null 'iam-import'
        Assert-OnboardingIamResult $OnboardingIamResult $OnboardingContext.Secrets.storageAppKey ('onboarding-'+$OnboardingOptions.NewRunId.Substring(0,12))
    } finally { $env:MC_HOST_onboarding_fixture=$null; $env:MC_CONFIG_DIR=$null }
    $OnboardingStorage=$OnboardingContext.Journal.components.storage
    $OnboardingStorage.consolePort=$OnboardingContext.Ports.console; $OnboardingStorage.bucket=$OnboardingContext.Bucket; $OnboardingStorage.prefix=$OnboardingContext.Prefix; $OnboardingStorage.internalEndpoint=$OnboardingStorage.url; $OnboardingStorage.publicEndpoint=$OnboardingStorage.url
    $OnboardingStorage.clientExecutable=$OnboardingOptions.MinioClientExecutable; $OnboardingStorage.clientConfigDirectory=Join-Path $OnboardingContext.Root 'mc-config'; $OnboardingStorage.credentialsFile=Join-Path $OnboardingContext.Root 'credentials.json'
    Set-OnboardingRuntimeEnvironment $OnboardingContext
    $OnboardingJava=Join-Path $OnboardingOptions.JavaHome 'bin/java.exe'; $OnboardingPaas=Join-Path $OnboardingContext.Root 'paas-base'; $OnboardingPortal=Join-Path $OnboardingContext.Root 'portal-base'
    Start-OnboardingComponent $OnboardingContext 'paas' $OnboardingJava @(('-Djava.io.tmpdir='+$env:TEMP),'-Xms64m','-Xmx512m',('-Djboss.home.dir='+$OnboardingOptions.WildFlyHome),('-Djboss.server.base.dir='+$OnboardingPaas),'-Djboss.bind.address=127.0.0.1','-Djboss.bind.address.management=127.0.0.1',('-Djboss.http.port='+$OnboardingContext.Ports.paas),('-Djboss.management.http.port='+$OnboardingContext.Ports.management),'-jar',(Join-Path $OnboardingOptions.WildFlyHome 'jboss-modules.jar'),'-mp',((Join-Path $OnboardingPaas 'modules')+';'+(Join-Path $OnboardingOptions.WildFlyHome 'modules')),'org.jboss.as.standalone','-c','standalone.xml') $OnboardingPaas $OnboardingContext.Ports.paas @($OnboardingContext.Ports.management)
    $OnboardingContext.Journal.components.paas.managementPort=$OnboardingContext.Ports.management
    Start-OnboardingComponent $OnboardingContext 'portal' $OnboardingJava @(('-Djava.io.tmpdir='+$env:TEMP),('-Donboarding.portal.port='+$OnboardingContext.Ports.portal),('-Donboarding.portal.shutdown.port='+$OnboardingContext.Ports.shutdown),('-Dcatalina.home='+$OnboardingOptions.TomcatHome),('-Dcatalina.base='+$OnboardingPortal),'-cp',((Join-Path $OnboardingOptions.TomcatHome 'bin/bootstrap.jar')+';'+(Join-Path $OnboardingOptions.TomcatHome 'bin/tomcat-juli.jar')),'org.apache.catalina.startup.Bootstrap','start') $OnboardingPortal $OnboardingContext.Ports.portal @($OnboardingContext.Ports.shutdown)
    $OnboardingContext.Journal.components.portal.shutdownPort=$OnboardingContext.Ports.shutdown
    for($OnboardingAttempt=0;$OnboardingAttempt -lt 240;$OnboardingAttempt++) {
        Assert-OnboardingAllProcesses $OnboardingContext
        if (Test-Path -LiteralPath (Join-Path $OnboardingPaas 'deployments/paas.war.failed')) { throw 'Application deployment failed' }
        if (Test-Path -LiteralPath (Join-Path $OnboardingPaas 'deployments/paas.war.deployed')) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath (Join-Path $OnboardingPaas 'deployments/paas.war.deployed'))) { throw 'JPA/application deployment not confirmed' }
    foreach($OnboardingUri in @(($OnboardingStorage.url+'/minio/health/live'),('http://127.0.0.1:'+$OnboardingContext.Ports.portal+'/portal/login'))) {
        Assert-OnboardingAllProcesses $OnboardingContext
        if ((Invoke-OnboardingHttp $OnboardingUri).StatusCode -ne 200) { throw 'Component health failed' }
    }
    Assert-OnboardingAllProcesses $OnboardingContext
    $OnboardingPlatform=@($OnboardingAccounts|Where-Object{$_.username -ceq 'platform_admin'})[0]
    if ((Invoke-OnboardingHttp ($env:MEDICAL_COURSE_PAAS_URL+'/auth/login') (@{username=$OnboardingPlatform.username;password=$OnboardingPlatform.password}|ConvertTo-Json -Compress)).StatusCode -ne 200) { throw 'PaaS login health failed' }
    foreach($OnboardingName in @('mysql','mysqlMigration')) { Assert-OnboardingMysqlLive $OnboardingContext $OnboardingName }
    $OnboardingContext.Journal.state='running'; $OnboardingContext.Journal.stage='complete'; $OnboardingContext.Journal.schemaVersion='005'
    Save-OnboardingJournal $OnboardingContext
    $OnboardingManifest=[ordered]@{ownerId=$OnboardingOptions.NewRunId;runtimeRoot=$OnboardingContext.Root;sourceCommit=$OnboardingOptions.SourceCommit;schemaVersion='005';state='running';snapshot=$OnboardingContext.Snapshot;tools=$OnboardingContext.Journal.tools;mysql=$OnboardingContext.Journal.components.mysql;mysqlMigration=$OnboardingContext.Journal.components.mysqlMigration;storage=$OnboardingContext.Journal.components.storage;paas=$OnboardingContext.Journal.components.paas;portal=$OnboardingContext.Journal.components.portal;accountsFile=(Join-Path $OnboardingContext.Root 'accounts.json');lifecycleValidation='pending-root-stop-start'}
    Assert-OnboardingAllProcesses $OnboardingContext
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'fixture.json') ($OnboardingManifest|ConvertTo-Json -Depth 16)
}
function Wait-OnboardingProcessExit([int]$OnboardingProcessId,[string]$OnboardingName,[int]$OnboardingTimeoutSeconds=90) {
    $OnboardingDeadline=[datetime]::UtcNow.AddSeconds($OnboardingTimeoutSeconds)
    while ([datetime]::UtcNow -lt $OnboardingDeadline) {
        if (-not (Get-Process -Id $OnboardingProcessId -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 400; return }
        Start-Sleep -Milliseconds 300
    }
    throw ($OnboardingName+' did not stop gracefully; no force kill was attempted')
}
function Save-OnboardingLifecycleState($OnboardingContext,[string]$OnboardingState) {
    $OnboardingContext.Journal.state=$OnboardingState
    Save-OnboardingJournal $OnboardingContext
    $OnboardingPrevious=$OnboardingContext.Manifest.lifecycleValidation
    $OnboardingValidation=$OnboardingPrevious
    if ($OnboardingState -eq 'stopped' -and $OnboardingPrevious -eq 'pending-root-stop-start') { $OnboardingValidation='stop-verified' }
    elseif ($OnboardingState -eq 'running' -and $OnboardingPrevious -in @('pending-root-stop-start','stop-verified')) { $OnboardingValidation='root-stop-start-verified' }
    $OnboardingManifest=[ordered]@{ownerId=$OnboardingContext.Journal.ownerId;runtimeRoot=$OnboardingContext.Root;sourceCommit=$OnboardingContext.Journal.sourceCommit;schemaVersion='005';state=$OnboardingState;snapshot=$OnboardingContext.Manifest.snapshot;tools=$OnboardingContext.Journal.tools;mysql=$OnboardingContext.Journal.components.mysql;mysqlMigration=$OnboardingContext.Journal.components.mysqlMigration;storage=$OnboardingContext.Journal.components.storage;paas=$OnboardingContext.Journal.components.paas;portal=$OnboardingContext.Journal.components.portal;accountsFile=$OnboardingContext.Manifest.accountsFile;lifecycleValidation=$OnboardingValidation}
    Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'fixture.json') ($OnboardingManifest|ConvertTo-Json -Depth 16)
    $OnboardingContext.Manifest=ConvertTo-OnboardingMap ($OnboardingManifest|ConvertTo-Json -Depth 16|ConvertFrom-Json)
}
function Stop-OnboardingFixture([hashtable]$OnboardingOptions) {
    $OnboardingContext=Read-OnboardingFixtureContext (Join-Path $OnboardingOptions.RuntimeRoot 'fixture.json') $OnboardingOptions.NewRunId -AllowStopped
    if ($OnboardingContext.Manifest.state -eq 'stopped') { return }
    $OnboardingCredentials=ConvertTo-OnboardingMap ([IO.File]::ReadAllText((Join-Path $OnboardingContext.Root 'credentials.json'))|ConvertFrom-Json -ErrorAction Stop)
    $OnboardingSaved=@{}
    foreach($OnboardingName in @('MC_CONFIG_DIR','MC_HOST_onboarding_fixture','JAVA_HOME')) { $OnboardingSaved[$OnboardingName]=[Environment]::GetEnvironmentVariable($OnboardingName,'Process') }
    try {
        if ($OnboardingContext.Options.JavaHome) { $env:JAVA_HOME=$OnboardingContext.Options.JavaHome }
        $OnboardingContext.Journal.state='stopping'
        Save-OnboardingJournal $OnboardingContext
        foreach($OnboardingName in @('portal','paas','storage','mysqlMigration','mysql')) {
            $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
            if ($OnboardingPart.status -ne 'running') { continue }
            if ($OnboardingName -eq 'portal') {
                $OnboardingTomcat=Join-Path $OnboardingContext.Options.TomcatHome 'bin'
                $OnboardingResult=Invoke-OnboardingNative (Join-Path $OnboardingContext.Options.JavaHome 'bin/java.exe') @(('-Djava.io.tmpdir='+(Join-Path $OnboardingContext.Root 'temp')),('-Dcatalina.home='+$OnboardingContext.Options.TomcatHome),('-Dcatalina.base='+$OnboardingPart.dataDirectory),'-cp',((Join-Path $OnboardingTomcat 'bootstrap.jar')+';'+(Join-Path $OnboardingTomcat 'tomcat-juli.jar')),'org.apache.catalina.startup.Bootstrap','stop') $OnboardingContext.Root $null 'portal-stop'
                if ($OnboardingResult.ExitCode -ne 0) { throw 'Portal graceful stop failed' }
            } elseif ($OnboardingName -eq 'paas') {
                $OnboardingResult=Invoke-OnboardingNative (Join-Path $OnboardingContext.Options.WildFlyHome 'bin/jboss-cli.bat') @('--connect',('--controller=127.0.0.1:'+[string]@($OnboardingPart.extraPorts)[0]),'--command=:shutdown') $OnboardingContext.Root $null 'paas-stop'
                if ($OnboardingResult.ExitCode -ne 0) { throw 'PaaS graceful stop failed' }
            } elseif ($OnboardingName -eq 'storage') {
                $env:MC_CONFIG_DIR=$OnboardingPart.clientConfigDirectory
                $env:MC_HOST_onboarding_fixture=('http://'+$OnboardingCredentials.storageRootKey+':'+$OnboardingCredentials.storageRootSecret+'@127.0.0.1:'+$OnboardingPart.port)
                $OnboardingResult=Invoke-OnboardingNative $OnboardingContext.Options.MinioClientExecutable @('admin','service','stop','onboarding_fixture') $OnboardingContext.Root $null 'storage-stop'
                if ($OnboardingResult.ExitCode -ne 0) { throw 'Storage graceful stop failed' }
            } else {
                $OnboardingResult=Invoke-OnboardingNative (Join-Path $OnboardingContext.Options.MySqlBin 'mysqladmin.exe') @(('--defaults-extra-file=./'+$OnboardingName+'-client.cnf'),'--protocol=TCP','--host=127.0.0.1',('--port='+[string]$OnboardingPart.port),'shutdown') $OnboardingContext.Root $null ($OnboardingName+'-stop')
                if ($OnboardingResult.ExitCode -ne 0) { throw ($OnboardingName+' graceful stop failed') }
            }
            Wait-OnboardingProcessExit $OnboardingPart.pid $OnboardingName
            $OnboardingPart.status='stopped'
            try { Save-OnboardingJournal $OnboardingContext } catch { }
        }
        foreach($OnboardingName in @('mysql','mysqlMigration','storage','paas','portal')) {
            foreach($OnboardingPort in @($OnboardingContext.Journal.components[$OnboardingName].port)+@($OnboardingContext.Journal.components[$OnboardingName].extraPorts)) {
                if ($OnboardingPort -and @(Get-OnboardingListeners $OnboardingPort).Count) { throw ('Listener still bound after stop: '+$OnboardingName) }
            }
        }
        Save-OnboardingLifecycleState $OnboardingContext 'stopped'
    } catch {
        try { Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'failure.txt') ('stop: '+$_.Exception.ToString()) } catch { }
        try { Save-OnboardingLifecycleState $OnboardingContext 'failed' } catch { $OnboardingContext.Journal.state='failed'; try { Save-OnboardingJournal $OnboardingContext } catch { } }
        throw
    } finally {
        foreach($OnboardingName in $OnboardingSaved.Keys) { [Environment]::SetEnvironmentVariable($OnboardingName,$OnboardingSaved[$OnboardingName],'Process') }
    }
}
function Start-OnboardingFixture([hashtable]$OnboardingOptions) {
    $OnboardingContext=Read-OnboardingFixtureContext (Join-Path $OnboardingOptions.RuntimeRoot 'fixture.json') $OnboardingOptions.NewRunId -AllowStopped
    if ($OnboardingContext.Manifest.state -ne 'stopped') { throw 'Start requires a stopped fixture' }
    foreach($OnboardingPath in @((Join-Path $OnboardingContext.Root 'paas-base/modules/com/mysql/main/module.xml'),(Join-Path $OnboardingContext.Root 'paas-base/modules/com/mysql/main/mysql-connector-j.jar'),(Join-Path $OnboardingContext.Root 'paas-base/deployments/paas.war'),(Join-Path $OnboardingContext.Root 'portal-base/webapps/portal.war'),(Join-Path $OnboardingContext.Root 'portal-base/conf/server.xml'),(Join-Path $OnboardingContext.Root 'paas-base/configuration/standalone.xml'))) {
        if (-not (Test-Path -LiteralPath $OnboardingPath -PathType Leaf)) { throw ('Restart artifact missing: '+$OnboardingPath) }
    }
    $OnboardingCredentials=ConvertTo-OnboardingMap ([IO.File]::ReadAllText((Join-Path $OnboardingContext.Root 'credentials.json'))|ConvertFrom-Json -ErrorAction Stop)
    $OnboardingSaved=@{}
    foreach($OnboardingName in @([Environment]::GetEnvironmentVariables('Process').Keys|Where-Object{$_ -match '^(COURSE_|MEDICAL_|MINIO_|MC_)'})) { $OnboardingSaved[$OnboardingName]=[Environment]::GetEnvironmentVariable($OnboardingName,'Process') }
    try {
        Set-OnboardingRuntimeEnvironment $OnboardingContext
        $OnboardingContext.Journal.state='starting'
        Save-OnboardingJournal $OnboardingContext
        foreach($OnboardingName in @('mysql','mysqlMigration')) {
            $OnboardingPart=$OnboardingContext.Journal.components[$OnboardingName]
            Start-OnboardingComponent $OnboardingContext $OnboardingName $OnboardingPart.executable @($OnboardingPart.arguments) $OnboardingPart.dataDirectory ([int]$OnboardingPart.port) @($OnboardingPart.extraPorts) ([string]$OnboardingPart.serverUuid)
        }
        $OnboardingStorage=$OnboardingContext.Journal.components.storage
        $env:MINIO_ROOT_USER=$OnboardingCredentials.storageRootKey; $env:MINIO_ROOT_PASSWORD=$OnboardingCredentials.storageRootSecret
        Start-OnboardingComponent $OnboardingContext 'storage' $OnboardingStorage.executable @($OnboardingStorage.arguments) $OnboardingStorage.dataDirectory ([int]$OnboardingStorage.port) @($OnboardingStorage.extraPorts)
        $env:MINIO_ROOT_USER=$null; $env:MINIO_ROOT_PASSWORD=$null
        $OnboardingPaas=$OnboardingContext.Journal.components.paas
        foreach($OnboardingMarker in @('paas.war.deployed','paas.war.failed','paas.war.isdeploying','paas.war.undeployed')) { $OnboardingMarkerPath=Join-Path $OnboardingPaas.dataDirectory ('deployments/'+$OnboardingMarker); if (Test-Path -LiteralPath $OnboardingMarkerPath) { [IO.File]::Delete($OnboardingMarkerPath) } }
        Start-OnboardingComponent $OnboardingContext 'paas' $OnboardingPaas.executable @($OnboardingPaas.arguments) $OnboardingPaas.dataDirectory ([int]$OnboardingPaas.port) @($OnboardingPaas.extraPorts)
        $OnboardingPortal=$OnboardingContext.Journal.components.portal
        Start-OnboardingComponent $OnboardingContext 'portal' $OnboardingPortal.executable @($OnboardingPortal.arguments) $OnboardingPortal.dataDirectory ([int]$OnboardingPortal.port) @($OnboardingPortal.extraPorts)
        for($OnboardingAttempt=0;$OnboardingAttempt -lt 240;$OnboardingAttempt++) {
            Assert-OnboardingAllProcesses $OnboardingContext
            if (Test-Path -LiteralPath (Join-Path $OnboardingPaas.dataDirectory 'deployments/paas.war.failed')) { throw 'Application deployment failed on restart' }
            if (Test-Path -LiteralPath (Join-Path $OnboardingPaas.dataDirectory 'deployments/paas.war.deployed')) { break }
            Start-Sleep -Milliseconds 250
        }
        if (-not (Test-Path -LiteralPath (Join-Path $OnboardingPaas.dataDirectory 'deployments/paas.war.deployed'))) { throw 'JPA/application deployment not confirmed on restart' }
        if ((Invoke-OnboardingHttp ($OnboardingStorage.url+'/minio/health/live')).StatusCode -ne 200) { throw 'Storage health failed on restart' }
        if ((Invoke-OnboardingHttp ('http://127.0.0.1:'+$OnboardingContext.Ports.portal+'/portal/login')).StatusCode -ne 200) { throw 'Portal health failed on restart' }
        foreach($OnboardingName in @('mysql','mysqlMigration')) { Assert-OnboardingMysqlLive $OnboardingContext $OnboardingName }
        $OnboardingAccounts=@(ConvertTo-OnboardingMap ([IO.File]::ReadAllText($OnboardingContext.Manifest.accountsFile)|ConvertFrom-Json -ErrorAction Stop))
        $OnboardingPlatform=@($OnboardingAccounts|Where-Object{$_.username -ceq 'platform_admin'})[0]
        if ((Invoke-OnboardingHttp ($env:MEDICAL_COURSE_PAAS_URL+'/auth/login') (@{username=$OnboardingPlatform.username;password=$OnboardingPlatform.password}|ConvertTo-Json -Compress)).StatusCode -ne 200) { throw 'PaaS login health failed on restart' }
        Save-OnboardingLifecycleState $OnboardingContext 'running'
    } catch {
        try { Write-OnboardingPrivate (Join-Path $OnboardingContext.Root 'failure.txt') ('start: '+$_.Exception.ToString()) } catch { }
        try { Save-OnboardingLifecycleState $OnboardingContext 'failed' } catch { $OnboardingContext.Journal.state='failed'; try { Save-OnboardingJournal $OnboardingContext } catch { } }
        throw
    } finally {
        foreach($OnboardingName in $OnboardingSaved.Keys) { [Environment]::SetEnvironmentVariable($OnboardingName,$OnboardingSaved[$OnboardingName],'Process') }
    }
}
function New-OnboardingFixture([hashtable]$OnboardingOptions) {
    $ErrorActionPreference='Stop'
    $OnboardingRoot=Assert-OnboardingNewRuntime $OnboardingOptions
    Assert-OnboardingToolchain $OnboardingOptions
    $OnboardingBuildParent=Join-Path $OnboardingOptions.ProjectRoot '.local/builds'
    Assert-OnboardingPathChain $OnboardingBuildParent
    if (-not (Test-Path -LiteralPath $OnboardingBuildParent)) { throw 'Build parent missing' }
    Assert-OnboardingPrivateParent $OnboardingBuildParent
    $OnboardingSnapshot=Join-Path $OnboardingBuildParent ('hob-'+$OnboardingOptions.NewRunId.Substring(0,12))
    if (Test-Path -LiteralPath $OnboardingSnapshot) { throw 'Snapshot already exists' }
    $OnboardingContext=@{Options=$OnboardingOptions;Root=$OnboardingRoot;Snapshot=$OnboardingSnapshot;Journal=[ordered]@{ownerId=$OnboardingOptions.NewRunId;runtimeRoot=$OnboardingRoot;sourceCommit=$OnboardingOptions.SourceCommit;state='intent';stage='preflight';components=[ordered]@{};nativeExits=@()}}
    [IO.Directory]::CreateDirectory($OnboardingRoot)|Out-Null
    Set-OnboardingPrivateAcl $OnboardingRoot
    Write-OnboardingPrivate (Join-Path $OnboardingRoot 'owner.id') $OnboardingOptions.NewRunId
    Save-OnboardingJournal $OnboardingContext
    $OnboardingSaved=@{}
    $OnboardingEnvNames=@('TEMP','TMP','JAVA_HOME','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','MYSQL_PWD','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD','MINIO_BROWSER','MINIO_UPDATE','MC_CONFIG_DIR','MC_HOST_onboarding_fixture','MEDICAL_COURSE_PAAS_URL')+@([Environment]::GetEnvironmentVariables('Process').Keys|Where-Object{$_ -match '^(COURSE_|MEDICAL_|MC_HOST_|MINIO_)'})+@('COURSE_ATTACHMENTS_ENABLED','COURSE_ATTACHMENTS_TEMP_DIR','COURSE_S3_INTERNAL_ENDPOINT','COURSE_S3_PUBLIC_ENDPOINT','COURSE_S3_REGION','COURSE_S3_BUCKET','COURSE_S3_ACCESS_KEY','COURSE_S3_SECRET_KEY','COURSE_S3_PREFIX')
    foreach ($OnboardingName in @($OnboardingEnvNames|Select-Object -Unique)) {
        $OnboardingSaved[$OnboardingName]=[Environment]::GetEnvironmentVariable($OnboardingName,'Process')
        [Environment]::SetEnvironmentVariable($OnboardingName,$null,'Process')
    }
    try {
        $OnboardingTemp=Join-Path $OnboardingRoot 'temp'; [IO.Directory]::CreateDirectory($OnboardingTemp)|Out-Null
        $env:TEMP=$OnboardingTemp; $env:TMP=$OnboardingTemp; $env:JAVA_HOME=$OnboardingOptions.JavaHome
        $env:MAVEN_OPTS='-Djava.io.tmpdir="'+$OnboardingTemp+'"'
        $OnboardingCommit=Invoke-OnboardingChecked 'git.exe' @('-C',$OnboardingOptions.SourceRoot,'rev-parse','--verify',($OnboardingOptions.SourceCommit+'^{commit}')) $OnboardingRoot $null 'source-identity'
        if ($OnboardingCommit.Trim() -cne $OnboardingOptions.SourceCommit) { throw 'Fixed source identity mismatch' }
        $OnboardingZip=Join-Path $OnboardingRoot 'source.zip'
        Invoke-OnboardingChecked 'git.exe' @('-C',$OnboardingOptions.SourceRoot,'archive','--format=zip',('--output='+$OnboardingZip),$OnboardingOptions.SourceCommit,'course') $OnboardingRoot $null 'source-export'|Out-Null
        Expand-OnboardingSource $OnboardingZip $OnboardingSnapshot
        $OnboardingBaseline=Invoke-OnboardingChecked 'git.exe' @('-C',$OnboardingOptions.SourceRoot,'show','309b6757584697f57e37889ff26b7858099ca66a:course/deploy/schema.sql') $OnboardingRoot $null 'baseline-export'
        Write-OnboardingPrivate (Join-Path $OnboardingRoot 'baseline004.sql') $OnboardingBaseline
        $OnboardingVersion=Invoke-OnboardingNative (Join-Path $OnboardingOptions.JavaHome 'bin/javac.exe') @('-version') $OnboardingRoot $null 'java-version'
        if ($OnboardingVersion.ExitCode -ne 0 -or ($OnboardingVersion.Output+$OnboardingVersion.Error) -notmatch 'javac 1\.8\.') { throw 'Java 8 preflight stopped' }
        # Environment gate: compile and package only. The full test suite runs separately
        # and must not decide whether this owned environment may be created.
        Invoke-OnboardingChecked $OnboardingOptions.MavenExecutable @('-o','-f',(Join-Path $OnboardingSnapshot 'course/pom.xml'),('-Dcourse.test.wildfly.home='+$OnboardingOptions.WildFlyHome),'-DskipTests','clean','package') $OnboardingSnapshot $null 'compile'|Out-Null
        $OnboardingContext.Journal.stage='bootstrap'
        Save-OnboardingJournal $OnboardingContext
        Invoke-OnboardingBootstrap $OnboardingContext
        return (Join-Path $OnboardingRoot 'fixture.json')
    } catch {
        $OnboardingContext.Journal.state='failed'
        try { Write-OnboardingPrivate (Join-Path $OnboardingRoot 'failure.txt') ($_.Exception.ToString()) } catch { }
        try { Save-OnboardingJournal $OnboardingContext } catch { }
        throw ('Onboarding setup stopped during '+$OnboardingContext.Journal.stage+'; private journal retained')
    } finally {
        foreach ($OnboardingName in $OnboardingSaved.Keys) { [Environment]::SetEnvironmentVariable($OnboardingName,$OnboardingSaved[$OnboardingName],'Process') }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    if (-not $Execute -and -not $Stop -and -not $Start) { Write-Output 'No action. Explicit -Execute, -Stop or -Start and an owned run are required.'; exit 4 }
    $OnboardingSource=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $OnboardingProject=Split-Path (Split-Path $OnboardingSource -Parent) -Parent
    $OnboardingOptions=@{ProjectRoot=$OnboardingProject;SourceRoot=$OnboardingSource;RuntimeRoot=$RuntimeRoot;MySqlBin=$MySqlBin;JavaHome=$JavaHome;TomcatHome=$TomcatHome;WildFlyHome=$WildFlyHome;MinioExecutable=$MinioExecutable;MinioClientExecutable=$MinioClientExecutable;MavenExecutable=$MavenExecutable;ConnectorJar=$ConnectorJar;SourceCommit=$SourceCommit;NewRunId=$NewRunId}
    try {
        if ($Stop) { Stop-OnboardingFixture $OnboardingOptions; exit 0 }
        if ($Start) { Start-OnboardingFixture $OnboardingOptions; exit 0 }
        New-OnboardingFixture $OnboardingOptions
        exit 0
    } catch { Write-Error 'Onboarding lifecycle stopped; inspect the retained private journal.'; exit 1 }
}
