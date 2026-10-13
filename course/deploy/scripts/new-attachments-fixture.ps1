param(
    [Parameter(Mandatory=$true)][string]$RuntimeRoot,
    [Parameter(Mandatory=$true)][string]$MySqlBin,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$TomcatHome,
    [Parameter(Mandatory=$true)][string]$WildFlyHome,
    [Parameter(Mandatory=$true)][string]$MinioExecutable,
    [Parameter(Mandatory=$true)][string]$MinioClientExecutable
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\','/')
if (-not [IO.Path]::IsPathRooted($RuntimeRoot) -or $root -notmatch '[\\/]attachments-[0-9a-fA-F]{32}$' -or
    $root -match '(?i)task8-runtime-20260928|teacher|sharedminio') { throw 'Runtime root must be a new absolute attachments-ID directory' }
if (Test-Path -LiteralPath $root) { throw 'Runtime root already exists; no files changed' }
$parent = Split-Path $root -Parent
if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw 'Runtime parent is missing; no files changed' }
if ((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Runtime parent is a reparse point' }
$required = @(
    (Join-Path $MySqlBin 'mysqld.exe'),(Join-Path $MySqlBin 'mysql.exe'),(Join-Path $MySqlBin 'mysqladmin.exe'),
    (Join-Path $JavaHome 'bin\java.exe'),(Join-Path $TomcatHome 'bin\bootstrap.jar'),
    (Join-Path $WildFlyHome 'jboss-modules.jar'),$MinioExecutable,$MinioClientExecutable
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Required local runtime binary unavailable; no files changed' }
}

function New-Secret([int]$Length) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] $Length
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join @($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}
function Get-FreePort {
    do {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        $listener.Start()
        try { $candidate = ([Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
    } while ($candidate -in @(3318,8180,8188,10090) -or $script:chosenPorts.Contains($candidate))
    [void]$script:chosenPorts.Add($candidate)
    return $candidate
}
function Wait-Loopback([int]$Port,[int]$ProcessId) {
    for ($attempt=0; $attempt -lt 240; $attempt++) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { throw 'New fixture service exited before becoming ready' }
        $listeners = @(Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) {
            if (@($listeners | Where-Object { $_.OwningProcess -eq $ProcessId }).Count -eq 1) { return }
            throw 'Allocated loopback port was taken by another process'
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'New fixture service did not listen on its allocated loopback port'
}
function Save-PrivateText([string]$Path,[string]$Value) {
    [IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($rule in @($acl.Access)) { [void]$acl.RemoveAccessRuleAll($rule) }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $access = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','Allow')
    $acl.AddAccessRule($access)
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Invoke-PrivateMysql([string]$InputSql,[string]$OptionFile) {
    $client = Join-Path $MySqlBin 'mysql.exe'
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        Assert-NewMysqlBootstrapOwnership $OptionFile
        if ($OptionFile) { $InputSql | & $client "--defaults-extra-file=$OptionFile" --batch --skip-column-names 2>$null | Out-Null }
        else { $InputSql | & $client --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$mysqlPort" --user=root --batch --skip-column-names 2>$null | Out-Null }
        if ($LASTEXITCODE -ne 0) { throw 'New fixture SQL setup failed' }
    } finally { $env:MYSQL_PWD = $saved }
}
function Assert-NewMysqlBootstrapOwnership([string]$OptionFile) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($mysqlProcess.Id)"
    $actualCreated = ([datetime]$process.CreationDate).ToUniversalTime()
    $savedCreated = $mysqlProcess.StartTime.ToUniversalTime()
    if (-not $process -or [IO.Path]::GetFullPath($process.ExecutablePath) -ine $serverExe -or
        ($actualCreated.Ticks - ($actualCreated.Ticks % 10)) -ne ($savedCreated.Ticks - ($savedCreated.Ticks % 10)) -or
        $process.CommandLine -notmatch [regex]::Escape($mysqlData) -or
        $process.CommandLine -notmatch "(?:^|\s)--port=$mysqlPort(?:\s|$)") { throw 'Bootstrap MySQL process ownership mismatch' }
    Assert-AttachmentsOwnedListener $mysqlPort $mysqlProcess.Id
    $offline = @([IO.File]::ReadAllLines((Join-Path $mysqlData 'auto.cnf')) | Where-Object { $_ -match '^server-uuid=([0-9a-fA-F-]{36})$' })
    if ($offline.Count -ne 1) { throw 'Bootstrap MySQL UUID missing' }
    [string[]]$queryArgs = if ($OptionFile) { @("--defaults-extra-file=$OptionFile") } else { @('--no-defaults') }
    $identity = @(& (Join-Path $MySqlBin 'mysql.exe') @queryArgs --protocol=TCP --host=127.0.0.1 "--port=$mysqlPort" --user=root --batch --skip-column-names --execute='SELECT @@server_uuid,@@port,@@datadir' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $identity.Count -ne 1) { throw 'Bootstrap MySQL identity unavailable' }
    $values = $identity[0] -split [char]9
    if ($values.Count -ne 3 -or $values[0] -ine ($offline[0] -split '=',2)[1] -or $values[1] -ne [string]$mysqlPort -or
        [IO.Path]::GetFullPath($values[2]).TrimEnd('\','/') -ine $mysqlData) { throw 'Bootstrap MySQL live ownership mismatch' }
}
function Invoke-PrivateMc([string[]]$Arguments) {
    Assert-OwnedAttachmentsFixture ([pscustomobject]$fixture) 'storage'
    & $MinioClientExecutable @Arguments 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'New fixture object-store setup failed' }
}

$script:chosenPorts = New-Object 'System.Collections.Generic.HashSet[int]'
$mysqlPort = Get-FreePort
$storagePort = Get-FreePort
$consolePort = Get-FreePort
$portalPort = Get-FreePort
$portalShutdownPort = Get-FreePort
do {
    $paasPort = Get-FreePort
    $offset = $paasPort - 8080
    $managementPort = 9990 + $offset
} while ($offset -le 0 -or $managementPort -gt 65535 -or $managementPort -in @(3318,8180,8188,10090) -or $script:chosenPorts.Contains($managementPort))
[void]$script:chosenPorts.Add($managementPort)

$ownerId = ([IO.Path]::GetFileName($root) -split '-',2)[1].ToLowerInvariant()
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$evidenceRoot = Join-Path $repo ('.superpowers\sdd\2026-10-12-medical-cloud-course-attachments\fixtures\' + $ownerId)
if (Test-Path -LiteralPath $evidenceRoot) { throw 'Fixture evidence directory already exists; no files changed' }
$mysqlData = Join-Path $root 'mysql-data'
$storageData = Join-Path $root 'storage-data'
$mcConfig = Join-Path $root 'mc-config'
$portalBase = Join-Path $root 'portal-base'
$paasBase = Join-Path $root 'paas-base'
[IO.Directory]::CreateDirectory($root) | Out-Null
[IO.File]::WriteAllText((Join-Path $root 'owner.id'),$ownerId)
foreach ($dir in @($mysqlData,$storageData,$mcConfig,$portalBase,$paasBase,$evidenceRoot)) { [IO.Directory]::CreateDirectory($dir) | Out-Null }
$rootPassword = New-Secret 48
$dbPassword = New-Secret 48
$storageRootKey = 'fixture' + (New-Secret 22)
$storageRootSecret = New-Secret 48
$storageAppKey = 'app' + (New-Secret 22)
$storageAppSecret = New-Secret 48
$dbName = 'medical_cloud_course'
$dbUser = 'attach_' + $ownerId.Substring(0,12)
$bucket = 'medical-attachments-' + $ownerId.Substring(0,12)
$prefix = 'files/'
$mysqlOptions = Join-Path $root 'mysql-client.cnf'
$credentialsFile = Join-Path $root 'credentials.json'
$fixturePath = Join-Path $evidenceRoot 'fixture.json'
$partialPath = Join-Path $evidenceRoot 'partial.json'
$fixture = [ordered]@{ownerId=$ownerId;runtimeRoot=$root;mysql=$null;paas=$null;portal=$null;storage=$null}
$started = New-Object 'System.Collections.Generic.List[object]'
$stage = 'MySQL initialization'
. (Join-Path $PSScriptRoot '..\tests\attachments-fixture-ownership.ps1')
try {
    $serverExe = [IO.Path]::GetFullPath((Join-Path $MySqlBin 'mysqld.exe'))
    $baseDir = [IO.Path]::GetFullPath((Split-Path $MySqlBin -Parent))
    $init = Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults','--initialize-insecure',"`"--basedir=$baseDir`"","`"--datadir=$mysqlData`"") -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $root 'mysql-init.out.log') -RedirectStandardError (Join-Path $root 'mysql-init.err.log')
    if ($init.ExitCode -ne 0) { throw 'New fixture MySQL initialization failed' }
    $stage = 'MySQL startup'
    $mysqlProcess = Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults','--no-monitor',"`"--basedir=$baseDir`"","`"--datadir=$mysqlData`"","--port=$mysqlPort",'--mysqlx=0','--bind-address=127.0.0.1',"`"--log-error=$(Join-Path $root 'mysql.err.log')`"") -WindowStyle Hidden -PassThru
    $started.Add([pscustomobject]@{component='mysql';pid=$mysqlProcess.Id;port=$mysqlPort;executable=$serverExe;dataDirectory=$mysqlData})
    Wait-Loopback $mysqlPort $mysqlProcess.Id
    $stage = 'MySQL account/database setup'
    Save-PrivateText $mysqlOptions "[client]`nuser=root`npassword=$rootPassword`nhost=127.0.0.1`nport=$mysqlPort`nprotocol=TCP`n"
    $rootSql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';"
    Invoke-PrivateMysql $rootSql ''
    Invoke-PrivateMysql "CREATE DATABASE $dbName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER '$dbUser'@'127.0.0.1' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'127.0.0.1';" $mysqlOptions
    $offline = @([IO.File]::ReadAllLines((Join-Path $mysqlData 'auto.cnf')) | Where-Object { $_ -match '^server-uuid=([0-9a-fA-F-]{36})$' })
    if ($offline.Count -ne 1) { throw 'New fixture MySQL UUID unavailable' }
    $serverUuid = ($offline[0] -split '=',2)[1]
    $fixture.mysql = [ordered]@{host='127.0.0.1';port=$mysqlPort;database=$dbName;serverUuid=$serverUuid;pid=$mysqlProcess.Id;created=$mysqlProcess.StartTime.ToUniversalTime().ToString('o');executable=$serverExe;dataDirectory=$mysqlData;clientExecutable=(Join-Path $MySqlBin 'mysql.exe');credentialFile=$mysqlOptions}
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $partialPath -Encoding UTF8

    $credentials = [ordered]@{mysqlRootPassword=$rootPassword;mysqlApplicationUser=$dbUser;mysqlApplicationPassword=$dbPassword;rootAccessKey=$storageRootKey;rootSecretKey=$storageRootSecret;applicationAccessKey=$storageAppKey;applicationSecretKey=$storageAppSecret}
    Save-PrivateText $credentialsFile ($credentials | ConvertTo-Json -Depth 4)
    $stage = 'MinIO startup'
    $oldRootUser=$env:MINIO_ROOT_USER; $oldRootPassword=$env:MINIO_ROOT_PASSWORD
    try {
        $env:MINIO_ROOT_USER=$storageRootKey; $env:MINIO_ROOT_PASSWORD=$storageRootSecret
        $storageProcess=Start-Process -FilePath $MinioExecutable -ArgumentList @('server',"`"$storageData`"",'--address',"127.0.0.1:$storagePort",'--console-address',"127.0.0.1:$consolePort") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'minio.out.log') -RedirectStandardError (Join-Path $root 'minio.err.log')
        $started.Add([pscustomobject]@{component='storage';pid=$storageProcess.Id;port=$storagePort;executable=$MinioExecutable;dataDirectory=$storageData})
    } finally { $env:MINIO_ROOT_USER=$oldRootUser; $env:MINIO_ROOT_PASSWORD=$oldRootPassword }
    Wait-Loopback $storagePort $storageProcess.Id
    $fixture.storage=[ordered]@{internalEndpoint="http://127.0.0.1:$storagePort";publicEndpoint="http://127.0.0.1:$storagePort";url="http://127.0.0.1:$storagePort";bucket=$bucket;prefix=$prefix;port=$storagePort;consolePort=$consolePort;pid=$storageProcess.Id;created=$storageProcess.StartTime.ToUniversalTime().ToString('o');executable=[IO.Path]::GetFullPath($MinioExecutable);dataDirectory=$storageData;clientExecutable=[IO.Path]::GetFullPath($MinioClientExecutable);clientConfigDirectory=$mcConfig;credentialsFile=$credentialsFile}
    $stage = 'MinIO private bucket/account setup'
    $oldMcHost=$env:MC_HOST_attachments_fixture; $oldMcConfig=$env:MC_CONFIG_DIR
    try {
        $env:MC_CONFIG_DIR=$mcConfig
        $env:MC_HOST_attachments_fixture='http://' + $storageRootKey + ':' + $storageRootSecret + '@127.0.0.1:' + $storagePort
        Invoke-PrivateMc @('mb',"attachments_fixture/$bucket")
        Invoke-PrivateMc @('anonymous','set','none',"attachments_fixture/$bucket")
        $policyPath=Join-Path $root 'storage-policy.json'
        $policy=[ordered]@{Version='2012-10-17';Statement=@(
            [ordered]@{Effect='Allow';Action=@('s3:ListBucket');Resource=@("arn:aws:s3:::$bucket");Condition=@{'StringLike'=@{'s3:prefix'=@($prefix + '*')}}},
            [ordered]@{Effect='Allow';Action=@('s3:GetObject','s3:PutObject','s3:DeleteObject');Resource=@("arn:aws:s3:::$bucket/$prefix*")}
        )}
        Save-PrivateText $policyPath ($policy | ConvertTo-Json -Depth 10)
        Invoke-PrivateMc @('admin','user','add','attachments_fixture',$storageAppKey,$storageAppSecret)
        $policyName='attachments-' + $ownerId.Substring(0,12)
        Invoke-PrivateMc @('admin','policy','create','attachments_fixture',$policyName,$policyPath)
        Invoke-PrivateMc @('admin','policy','attach','attachments_fixture',$policyName,'--user',$storageAppKey)
    } finally { $env:MC_HOST_attachments_fixture=$oldMcHost; $env:MC_CONFIG_DIR=$oldMcConfig }
    $fixture.storage=[ordered]@{internalEndpoint="http://127.0.0.1:$storagePort";publicEndpoint="http://127.0.0.1:$storagePort";url="http://127.0.0.1:$storagePort";bucket=$bucket;prefix=$prefix;port=$storagePort;consolePort=$consolePort;pid=$storageProcess.Id;created=$storageProcess.StartTime.ToUniversalTime().ToString('o');executable=[IO.Path]::GetFullPath($MinioExecutable);dataDirectory=$storageData;clientExecutable=[IO.Path]::GetFullPath($MinioClientExecutable);clientConfigDirectory=$mcConfig;credentialsFile=$credentialsFile}
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $partialPath -Encoding UTF8

    Copy-Item -LiteralPath (Join-Path $TomcatHome 'conf') -Destination (Join-Path $portalBase 'conf') -Recurse
    foreach ($name in @('logs','webapps','temp','work')) { [IO.Directory]::CreateDirectory((Join-Path $portalBase $name)) | Out-Null }
    $stage = 'Tomcat startup'
    $serverXmlPath=Join-Path $portalBase 'conf\server.xml'
    [xml]$serverXml=[IO.File]::ReadAllText($serverXmlPath)
    $serverXml.Server.SetAttribute('port',[string]$portalShutdownPort)
    foreach ($connector in @($serverXml.Server.Service.Connector)) {
        if ($connector.protocol -match 'HTTP/1.1|Http11') { $connector.SetAttribute('port',[string]$portalPort); $connector.SetAttribute('address','127.0.0.1') }
        else { [void]$connector.ParentNode.RemoveChild($connector) }
    }
    $serverXml.Save($serverXmlPath)
    $javaExe=[IO.Path]::GetFullPath((Join-Path $JavaHome 'bin\java.exe'))
    $bootstrap=Join-Path $TomcatHome 'bin\bootstrap.jar'; $juli=Join-Path $TomcatHome 'bin\tomcat-juli.jar'
    $portalProcess=Start-Process -FilePath $javaExe -ArgumentList @("`"-Dcatalina.home=$TomcatHome`"","`"-Dcatalina.base=$portalBase`"",'-cp',"`"$bootstrap;$juli`"",'org.apache.catalina.startup.Bootstrap','start') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'portal.out.log') -RedirectStandardError (Join-Path $root 'portal.err.log')
    $started.Add([pscustomobject]@{component='portal';pid=$portalProcess.Id;port=$portalPort;executable=$javaExe;dataDirectory=$portalBase})
    Wait-Loopback $portalPort $portalProcess.Id
    $fixture.portal=[ordered]@{url="http://127.0.0.1:$portalPort";port=$portalPort;shutdownPort=$portalShutdownPort;pid=$portalProcess.Id;created=$portalProcess.StartTime.ToUniversalTime().ToString('o');executable=$javaExe;dataDirectory=$portalBase;shutdownExecutable=(Join-Path $TomcatHome 'bin\catalina.bat')}
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $partialPath -Encoding UTF8

    Copy-Item -LiteralPath (Join-Path $WildFlyHome 'standalone\configuration') -Destination (Join-Path $paasBase 'configuration') -Recurse
    foreach ($name in @('data','deployments','log','tmp')) { [IO.Directory]::CreateDirectory((Join-Path $paasBase $name)) | Out-Null }
    $stage = 'WildFly startup'
    $modules=Join-Path $WildFlyHome 'jboss-modules.jar'; $modulePath=Join-Path $WildFlyHome 'modules'
    $paasProcess=Start-Process -FilePath $javaExe -ArgumentList @('-Xms64m','-Xmx512m',"`"-Djboss.home.dir=$WildFlyHome`"","`"-Djboss.server.base.dir=$paasBase`"",'-Djboss.bind.address=127.0.0.1','-Djboss.bind.address.management=127.0.0.1',"-Djboss.socket.binding.port-offset=$offset",'-jar',"`"$modules`"",'-mp',"`"$modulePath`"",'org.jboss.as.standalone','-c','standalone.xml') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'paas.out.log') -RedirectStandardError (Join-Path $root 'paas.err.log')
    $started.Add([pscustomobject]@{component='paas';pid=$paasProcess.Id;port=$paasPort;executable=$javaExe;dataDirectory=$paasBase})
    Wait-Loopback $paasPort $paasProcess.Id
    $fixture.paas=[ordered]@{url="http://127.0.0.1:$paasPort";port=$paasPort;managementPort=$managementPort;pid=$paasProcess.Id;created=$paasProcess.StartTime.ToUniversalTime().ToString('o');executable=$javaExe;dataDirectory=$paasBase;shutdownExecutable=(Join-Path $WildFlyHome 'bin\jboss-cli.bat')}
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $partialPath -Encoding UTF8
    $stage = 'final ownership verification'
    . (Join-Path $PSScriptRoot '..\tests\attachments-fixture-ownership.ps1')
    foreach ($component in @('mysql','storage','portal','paas')) { Assert-OwnedAttachmentsFixture ([pscustomobject]$fixture) $component }
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fixturePath -Encoding UTF8
    Write-Output $fixturePath
} catch {
    $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $partialPath -Encoding UTF8
    $started.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'started-processes.json') -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'failure.txt'), ($stage + ': ' + $_.Exception.Message))
    throw "New fixture setup stopped during $stage; partial identity and logs retained in the ignored evidence/runtime directories"
}
