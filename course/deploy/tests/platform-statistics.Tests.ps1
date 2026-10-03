$ErrorActionPreference = 'Stop'
$deploy = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$mysql = (Get-Command mysql.exe).Source
$server = (Get-Command mysqld.exe).Source
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('course-platform-statistics-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $scratch 'data'
$prior = @{}
foreach ($name in @('COURSE_MYSQL_TEST_URL','COURSE_MYSQL_TEST_USER','COURSE_MYSQL_TEST_PASSWORD','MYSQL_PWD')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$fixtureServerProcess = $null
try {
    New-Item -ItemType Directory -Path $data | Out-Null
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    $base = Split-Path (Split-Path $server -Parent) -Parent
    $env:MYSQL_PWD = ''
    & $server --no-defaults --initialize-insecure "--basedir=$base" "--datadir=$data" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL initialization failed' }
    $fixtureServerProcess = Start-Process -FilePath $server -ArgumentList @('--no-defaults',"--basedir=$base","--datadir=$data",'--bind-address=127.0.0.1',"--port=$port",'--mysqlx=0','--console') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $scratch 'server.err')
    $arguments = @('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$port",'--user=root','--batch','--skip-column-names')
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        & $mysql @arguments --execute='SELECT 1' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable MySQL did not start' }
    $database = 'course_platform_test_' + [guid]::NewGuid().ToString('N')
    & $mysql @arguments "--execute=CREATE DATABASE $database CHARACTER SET utf8mb4"
    if ($LASTEXITCODE -ne 0) { throw 'Disposable database creation failed' }
    $env:COURSE_MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$port/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    $env:COURSE_MYSQL_TEST_USER = 'root'
    $env:COURSE_MYSQL_TEST_PASSWORD = ''
    Describe 'real MySQL hospital lifecycle and statistics' {
        It 'runs every concurrent writer, stale snapshot, NULL and midnight assertion without skips' {
            & mvn -f (Join-Path $deploy '../pom.xml') '-Dtest=MySqlTenantLifecycleTest,MySqlStatisticsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
            $LASTEXITCODE | Should Be 0
            $reports = Join-Path $deploy '../paas/target/surefire-reports'
            foreach ($test in @('MySqlTenantLifecycleTest','MySqlStatisticsTest')) {
                [xml]$result = Get-Content -LiteralPath (Join-Path $reports "TEST-com.medcloud.course.paas.$test.xml") -Raw
                [int]$result.testsuite.tests | Should BeGreaterThan 0
                [int]$result.testsuite.skipped | Should Be 0
                [int]$result.testsuite.failures | Should Be 0
                [int]$result.testsuite.errors | Should Be 0
            }
        }
    }
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^course-platform-statistics-[a-f0-9]{32}$') {
        throw 'Refused cleanup outside the exact disposable temp fixture'
    }
    try {
        # Pester runs test bodies in child scopes. Resolve the actual owned server by
        # its exact datadir before stopping it; never stop another mysqld instance.
        $ownedServers = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object {
            $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)')
        })
        if ($ownedServers.Count) {
            if (@($ownedServers | Where-Object { $_.Name -ne 'mysqld.exe' }).Count) { throw 'Unexpected fixture process identity' }
            $env:MYSQL_PWD = ''
            & $mysql @arguments --execute='SHUTDOWN' 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Disposable server shutdown failed; datadir retained' }
            for ($wait=0; $wait -lt 40; $wait++) {
                $stillOwned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object {
                    $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)')
                })
                if (-not $stillOwned.Count) { break }
                Start-Sleep -Milliseconds 250
            }
        }
        if (@(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" | Where-Object {
            $_.CommandLine -match ('(?:^|\s)--datadir="?' + [regex]::Escape($data) + '"?(?:\s|$)')
        }).Count) { throw 'Disposable server still owns datadir; refused deletion' }
    } finally {
        foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
    }
    if (Test-Path -LiteralPath $resolved) {
        for ($attempt=0; $attempt -lt 10; $attempt++) {
            try { Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop; break }
            catch { if ($attempt -eq 9) { throw }; Start-Sleep -Milliseconds 500 }
        }
    }
}
