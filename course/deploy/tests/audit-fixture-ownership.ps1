# Fixture-only ownership boundary. Identity reads are the only unguarded SQL.
function Get-AuditFixtureOfflineUuid([string]$Data) {
    $lines = @(Get-Content -LiteralPath (Join-Path $Data 'auto.cnf') -ErrorAction Stop)
    $uuids = @($lines | Where-Object { $_ -match '^server-uuid=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$' } | ForEach-Object { ($_ -split '=',2)[1].ToLowerInvariant() })
    if ($uuids.Count -ne 1) { throw 'Audit fixture offline UUID unavailable; mutation/shutdown refused; retained data' }
    return $uuids[0]
}

function Assert-OwnedAuditFixture {
    param($Owner,[string]$MySqlCli)
    if (-not $Owner -or -not $Owner.Data -or -not $Owner.Server -or -not $Owner.Uuid -or
        $Owner.LauncherPid -lt 1 -or -not $Owner.LauncherCreated -or $Owner.Port -lt 1 -or
        $Owner.Port -gt 65535 -or $Owner.Port -in @(3318,8180,8188,10090)) {
        throw 'Audit fixture saved identity incomplete; mutation/shutdown refused; retained data'
    }
    $expectedData = [IO.Path]::GetFullPath($Owner.Data).TrimEnd('\','/')
    $expectedServer = [IO.Path]::GetFullPath($Owner.Server)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + '\'
    $scratchPath = Split-Path $expectedData -Parent
    if ($expectedData -ine (Join-Path $scratchPath 'data') -or
        -not $scratchPath.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($scratchPath) -notmatch '^course-audit-[a-f0-9]{32}$' -or
        (Get-AuditFixtureOfflineUuid $expectedData) -ine $Owner.Uuid) {
        throw 'Audit fixture canonical datadir/offline UUID mismatch; mutation/shutdown refused; retained data'
    }
    $matchData = '(?:^|\s)--datadir="?' + [regex]::Escape($expectedData) + '"?(?:\s|$)'
    $owned = @(Get-CimInstance Win32_Process -Filter "name='mysqld.exe'" -ErrorAction Stop | Where-Object { $_.CommandLine -match $matchData })
    $launcher = @($owned | Where-Object { $_.ProcessId -eq $Owner.LauncherPid })
    # CIM exposes microseconds; Process.StartTime has 100ns ticks. Compare the exact
    # microsecond bucket, not a time tolerance that could accept another launch.
    $savedCreated = [datetime]$Owner.LauncherCreated
    $savedCreated = $savedCreated.AddTicks(-($savedCreated.Ticks % 10))
    if ($owned.Count -lt 1 -or $owned.Count -gt 2 -or $launcher.Count -ne 1 -or
        -not $launcher[0].CreationDate -or [datetime]$launcher[0].CreationDate -ne $savedCreated) {
        throw 'Audit fixture saved launcher identity unavailable; mutation/shutdown refused; retained data'
    }
    foreach ($process in $owned) {
        if (-not $process.ExecutablePath -or [IO.Path]::GetFullPath($process.ExecutablePath) -ine $expectedServer -or
            $process.CommandLine -notmatch ('(?:^|\s)--port=' + $Owner.Port + '(?:\s|$)') -or
            ($process.ProcessId -ne $Owner.LauncherPid -and $process.ParentProcessId -ne $Owner.LauncherPid)) {
            throw 'Audit fixture binary/ancestry/CLI port mismatch; mutation/shutdown refused; retained data'
        }
    }
    $saved = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = ''
        $identity = @(& $MySqlCli --no-defaults --protocol=TCP --connect-timeout=2 --host=127.0.0.1 "--port=$($Owner.Port)" --user=root --batch --skip-column-names --execute='SELECT @@server_uuid,@@port,@@datadir' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $identity.Count -ne 1) { throw 'Audit fixture SQL identity unavailable; mutation/shutdown refused; retained data' }
        $parts = $identity[0] -split [char]9
        if ($parts.Count -ne 3 -or $parts[0] -ine $Owner.Uuid -or $parts[1] -ne [string]$Owner.Port -or
            -not $parts[2] -or [IO.Path]::GetFullPath($parts[2]).TrimEnd('\','/') -ine $expectedData) {
            throw 'Audit fixture SQL UUID/port/datadir mismatch; mutation/shutdown refused; retained data'
        }
    } finally { $env:MYSQL_PWD = $saved }
}
