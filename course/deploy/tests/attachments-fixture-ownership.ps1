function Assert-AttachmentsOwnedPath([string]$Path,[string]$Root,[string]$Kind) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) { throw "$Kind path is not absolute" }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    if (-not $full.StartsWith($Root + '\',[StringComparison]::OrdinalIgnoreCase)) { throw "$Kind is outside owned runtime" }
    if (-not (Test-Path -LiteralPath $full)) { throw "$Kind is missing" }
    $item = Get-Item -LiteralPath $full -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "$Kind is a reparse point" }
    return $full
}

function Get-AttachmentsSqlIdentity($Fixture) {
    $client = $Fixture.mysql.clientExecutable
    if (-not $client -or -not (Test-Path -LiteralPath $client)) { throw 'MySQL identity client unavailable' }
    $root = [IO.Path]::GetFullPath($Fixture.runtimeRoot).TrimEnd('\','/')
    $options = Assert-AttachmentsOwnedPath $Fixture.mysql.credentialFile $root 'MySQL credentials'
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        $value = @(& $client "--defaults-extra-file=$options" --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$($Fixture.mysql.port)" --batch --skip-column-names --execute='SELECT @@server_uuid,@@port,@@datadir' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $value.Count -ne 1) { throw 'MySQL identity unavailable' }
        return $value[0]
    } finally { $env:MYSQL_PWD = $saved }
}

function Assert-AttachmentsOwnedListener([int]$Port,[int]$ProcessId) {
    $listener = @(Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort $Port -State Listen -ErrorAction Stop)
    if ($listener.Count -ne 1 -or $listener[0].OwningProcess -ne $ProcessId) { throw 'Loopback listener ownership mismatch' }
}

function Assert-OwnedAttachmentsFixture {
    param($Fixture,[ValidateSet('mysql','paas','portal','storage')][string]$Component)
    if (-not $Fixture -or -not $Fixture.ownerId -or -not $Fixture.runtimeRoot -or -not $Fixture.$Component) { throw 'Fixture identity incomplete' }
    if ($Fixture.ownerId -notmatch '^[0-9a-fA-F]{32}$') { throw 'Fixture owner ID invalid' }
    $root = [IO.Path]::GetFullPath($Fixture.runtimeRoot).TrimEnd('\','/')
    if (-not [IO.Path]::IsPathRooted($Fixture.runtimeRoot) -or $root -notmatch '[\\/]attachments-[0-9a-fA-F]{32}$' -or
        $root -match '(?i)task8-runtime-20260928|teacher|sharedminio') { throw 'Runtime root is not an owned attachment fixture' }
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Runtime root missing' }
    $rootItem = Get-Item -LiteralPath $root -Force
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Runtime root is a reparse point' }
    $marker = Join-Path $root 'owner.id'
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf) -or ([IO.File]::ReadAllText($marker)).Trim() -ine $Fixture.ownerId) { throw 'Fixture owner marker mismatch' }
    $part = $Fixture.$Component
    if (-not $part.pid -or $part.pid -lt 1 -or -not $part.created -or -not $part.executable -or
        -not $part.port -or $part.port -lt 1 -or $part.port -gt 65535 -or $part.port -in @(3318,8180,8188,10090)) { throw 'Component identity incomplete or protected port' }
    $expectedExe = [IO.Path]::GetFullPath($part.executable)
    $process = @(Get-CimInstance Win32_Process -Filter "ProcessId=$($part.pid)" -ErrorAction Stop)
    $savedCreated = ([datetime]$part.created).ToUniversalTime()
    $savedCreated = $savedCreated.AddTicks(-($savedCreated.Ticks % 10))
    $actualCreated = if ($process.Count -eq 1 -and $process[0].CreationDate) { ([datetime]$process[0].CreationDate).ToUniversalTime() } else { [datetime]::MinValue }
    $actualCreated = $actualCreated.AddTicks(-($actualCreated.Ticks % 10))
    if ($process.Count -ne 1 -or $process[0].ProcessId -ne $part.pid -or -not $process[0].ExecutablePath -or
        [IO.Path]::GetFullPath($process[0].ExecutablePath) -ine $expectedExe -or
        $actualCreated -ne $savedCreated) { throw 'Process identity mismatch' }
    $command = [string]$process[0].CommandLine
    if ($Component -eq 'mysql' -and $command -notmatch ('(?:^|\s)--port=' + [regex]::Escape([string]$part.port) + '(?:\s|$)')) {
        throw 'Process port mismatch'
    }
    if ($Component -eq 'mysql') {
        if ($part.host -ne '127.0.0.1' -or $part.serverUuid -notmatch '^[0-9a-fA-F-]{36}$') { throw 'MySQL identity invalid' }
        $data = Assert-AttachmentsOwnedPath $part.dataDirectory $root 'MySQL data directory'
        if ($data -ine (Join-Path $root 'mysql-data')) { throw 'MySQL data directory mismatch' }
        if ($command -notmatch [regex]::Escape($data)) { throw 'Process data directory mismatch' }
        $offline = @([IO.File]::ReadAllLines((Join-Path $data 'auto.cnf')) | Where-Object { $_ -match '^server-uuid=([0-9a-fA-F-]{36})$' })
        if ($offline.Count -ne 1 -or ($offline[0] -split '=',2)[1] -ine $part.serverUuid) { throw 'Offline MySQL UUID mismatch' }
        $identity = (Get-AttachmentsSqlIdentity $Fixture) -split [char]9
        if ($identity.Count -ne 3 -or $identity[0] -ine $part.serverUuid -or $identity[1] -ne [string]$part.port -or
            [IO.Path]::GetFullPath($identity[2]).TrimEnd('\','/') -ine $data) { throw 'Live MySQL UUID/port/datadir mismatch' }
        Assert-AttachmentsOwnedListener $part.port $part.pid
    } else {
        $uri = [uri]$part.url
        if ($uri.Host -notin @('127.0.0.1','localhost') -or $uri.Port -ne $part.port) { throw 'Component URL/port mismatch' }
        $ownedDir = Assert-AttachmentsOwnedPath $part.dataDirectory $root "$Component data directory"
        if ($command -notmatch [regex]::Escape($ownedDir)) { throw 'Process runtime directory mismatch' }
        if ($Component -eq 'storage' -and $command -notmatch ('(?:^|\s)--address(?:=|\s+)127\.0\.0\.1:' + [regex]::Escape([string]$part.port) + '(?:\s|$)')) { throw 'Storage process port mismatch' }
        if ($Component -eq 'paas') {
            $offset = $part.port - 8080
            if ($offset -lt 0 -or $command -notmatch ('(?:^|\s)-Djboss\.socket\.binding\.port-offset=' + $offset + '(?:\s|$)')) { throw 'PaaS process port mismatch' }
            if (-not $part.managementPort -or $part.managementPort -ne (9990 + $offset)) { throw 'PaaS management port mismatch' }
            Assert-AttachmentsOwnedListener $part.managementPort $part.pid
        }
        if ($Component -eq 'portal') {
            $config = Join-Path $ownedDir 'conf\server.xml'
            if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { throw 'Portal connector config missing' }
            [xml]$serverXml = [IO.File]::ReadAllText($config)
            if (@($serverXml.Server.Service.Connector | Where-Object { $_.port -eq [string]$part.port -and $_.address -eq '127.0.0.1' }).Count -ne 1) { throw 'Portal connector port mismatch' }
            if (-not $part.shutdownPort -or $part.shutdownPort -in @(3318,8180,8188,10090) -or
                $serverXml.Server.port -ne [string]$part.shutdownPort) { throw 'Portal shutdown port mismatch' }
            Assert-AttachmentsOwnedListener $part.shutdownPort $part.pid
        }
        Assert-AttachmentsOwnedListener $part.port $part.pid
    }
}

function Wait-OwnedAttachmentsFixtureStopped($Part,[int]$TimeoutMilliseconds=30000) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $process = @(Get-CimInstance Win32_Process -Filter "ProcessId=$($Part.pid)" -ErrorAction Stop | Where-Object { $null -ne $_ })
        if ($process.Count -gt 0) {
            $actual = ([datetime]$process[0].CreationDate).ToUniversalTime()
            $expected = ([datetime]$Part.created).ToUniversalTime()
            if ($process.Count -ne 1 -or ($actual.Ticks - ($actual.Ticks % 10)) -ne ($expected.Ticks - ($expected.Ticks % 10))) {
                throw 'PID was reused during graceful shutdown; no further operation attempted'
            }
        }
        $ports = @($Part.port)
        foreach ($name in @('managementPort','shutdownPort','consolePort')) { if ($Part.$name) { $ports += [int]$Part.$name } }
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in $ports })
        if ($process.Count -eq 0 -and $listeners.Count -eq 0) { return }
        if (@($listeners | Where-Object { $_.OwningProcess -ne $Part.pid }).Count -gt 0) {
            throw 'A shutdown port is owned by another process; no further operation attempted'
        }
        if ($timer.ElapsedMilliseconds -ge $TimeoutMilliseconds) { throw 'Graceful shutdown did not remove the owned process and listeners within the deadline' }
        Start-Sleep -Milliseconds 200
    } while ($true)
}

function Stop-OwnedAttachmentsFixture {
    param($Fixture,[ValidateSet('mysql','paas','portal','storage')][string]$Component)
    Assert-OwnedAttachmentsFixture $Fixture $Component
    $part = $Fixture.$Component
    if ($Component -eq 'mysql') {
        $admin = Join-Path (Split-Path $part.executable -Parent) 'mysqladmin.exe'
        if (-not (Test-Path -LiteralPath $admin)) { throw 'MySQL graceful shutdown client unavailable' }
        $options = Assert-AttachmentsOwnedPath $part.credentialFile ([IO.Path]::GetFullPath($Fixture.runtimeRoot).TrimEnd('\','/')) 'MySQL credentials'
        & $admin "--defaults-extra-file=$options" --protocol=TCP --host=127.0.0.1 "--port=$($part.port)" shutdown
        if ($LASTEXITCODE -ne 0) { throw 'MySQL graceful shutdown failed' }
    } elseif ($Component -eq 'storage') {
        if (-not $part.clientExecutable -or -not (Test-Path -LiteralPath $part.clientExecutable -PathType Leaf) -or -not $part.credentialsFile) {
            throw 'Storage graceful shutdown credentials unavailable'
        }
        $ownedRoot = [IO.Path]::GetFullPath($Fixture.runtimeRoot).TrimEnd('\','/')
        $credentialsFile = Assert-AttachmentsOwnedPath $part.credentialsFile $ownedRoot 'Storage credentials'
        $mcConfig = Assert-AttachmentsOwnedPath $part.clientConfigDirectory $ownedRoot 'Storage client configuration'
        $credentials = [IO.File]::ReadAllText($credentialsFile) | ConvertFrom-Json
        if (-not $credentials.rootAccessKey -or -not $credentials.rootSecretKey) { throw 'Storage graceful shutdown credentials incomplete' }
        $priorHost = $env:MC_HOST_attachments_fixture
        $priorConfig = $env:MC_CONFIG_DIR
        try {
            $env:MC_CONFIG_DIR = $mcConfig
            $env:MC_HOST_attachments_fixture = 'http://' + [uri]::EscapeDataString($credentials.rootAccessKey) + ':' + [uri]::EscapeDataString($credentials.rootSecretKey) + '@127.0.0.1:' + $part.port
            & $part.clientExecutable admin service stop attachments_fixture 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Storage graceful shutdown failed' }
        } finally { $env:MC_HOST_attachments_fixture = $priorHost; $env:MC_CONFIG_DIR = $priorConfig }
    } elseif ($Component -eq 'portal') {
        if (-not $part.shutdownExecutable -or -not (Test-Path -LiteralPath $part.shutdownExecutable -PathType Leaf)) { throw 'Portal graceful shutdown command unavailable' }
        $runtimeHome = Split-Path (Split-Path $part.shutdownExecutable -Parent) -Parent
        $bootstrap = Join-Path $runtimeHome 'bin\bootstrap.jar'
        $juli = Join-Path $runtimeHome 'bin\tomcat-juli.jar'
        if (-not (Test-Path -LiteralPath $bootstrap -PathType Leaf) -or -not (Test-Path -LiteralPath $juli -PathType Leaf)) { throw 'Portal shutdown classpath unavailable' }
        $shutdown = Start-Process -FilePath $part.executable -ArgumentList @("`"-Dcatalina.home=$runtimeHome`"","`"-Dcatalina.base=$($part.dataDirectory)`"",'-cp',"`"$bootstrap;$juli`"",'org.apache.catalina.startup.Bootstrap','stop') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $Fixture.runtimeRoot 'portal-stop.out.log') -RedirectStandardError (Join-Path $Fixture.runtimeRoot 'portal-stop.err.log')
        if ($shutdown.ExitCode -ne 0) { throw 'Portal graceful shutdown failed' }
    } else {
        if (-not $part.shutdownExecutable -or -not (Test-Path -LiteralPath $part.shutdownExecutable -PathType Leaf) -or
            -not $part.managementPort -or $part.managementPort -in @(3318,8180,8188,10090)) { throw 'PaaS graceful shutdown command unavailable' }
        $runtimeHome = Split-Path (Split-Path $part.shutdownExecutable -Parent) -Parent
        $client = Join-Path $runtimeHome 'bin\client\jboss-cli-client.jar'
        if (-not (Test-Path -LiteralPath $client -PathType Leaf)) { throw 'PaaS shutdown client unavailable' }
        $shutdown = Start-Process -FilePath $part.executable -ArgumentList @('-jar',"`"$client`"",'--connect',"--controller=127.0.0.1:$($part.managementPort)",'--command=:shutdown') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $Fixture.runtimeRoot 'paas-stop.out.log') -RedirectStandardError (Join-Path $Fixture.runtimeRoot 'paas-stop.err.log')
        if ($shutdown.ExitCode -ne 0) { throw 'PaaS graceful shutdown failed' }
    }
    Wait-OwnedAttachmentsFixtureStopped $part
}
