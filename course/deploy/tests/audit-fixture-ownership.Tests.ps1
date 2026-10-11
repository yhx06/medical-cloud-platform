$ErrorActionPreference = 'Stop'
$fixture = Join-Path $PSScriptRoot 'upgrade-audit-database.Tests.ps1'
$helper = Join-Path $PSScriptRoot 'audit-fixture-ownership.ps1'
if (Test-Path -LiteralPath $helper) { . $helper }
# Load the real boundary functions without initializing or launching any database.
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($fixture,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Fixture parse failed' }
foreach ($name in @('Invoke-FixtureSql','Invoke-Upgrade','Reset-P0Fixture')) {
    $definition = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('course-audit-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $scratch 'data'
$server = 'D:\MySQL\bin\mysqld.exe'; $port = 45678
$serverUuid = '12345678-1234-4234-8234-123456789abc'
$serverProcess = [pscustomobject]@{Id=1234;StartTime=[datetime]'2030-01-01'}
$owner = [pscustomobject]@{Data=$data;Server=$server;Port=$port;Uuid=$serverUuid;LauncherPid=1234;LauncherCreated=[datetime]'2030-01-01'}
$mysql = 'Invoke-RecordedMysql'; $dump = 'unused'; $upgrade = $fixture
$p0Schema = 'CREATE DATABASE medical_cloud_course'

function Invoke-RecordedMysql {
    $global:LASTEXITCODE = 0
    $sql = @($args | Where-Object { $_ -like '--execute=*' }) -join ''
    if ($sql -eq '--execute=SELECT @@server_uuid,@@port,@@datadir') {
        if ($script:unavailable) { $global:LASTEXITCODE=1; return }
        return $script:sqlIdentity
    }
    if ($sql -match '--execute=(DROP|CREATE|INSERT|ALTER|SET|SHUTDOWN)') { $script:effects.Add($sql) }
}

Describe 'audit fixture exact owned-instance external boundary' {
    BeforeEach {
        $script:effects = New-Object 'System.Collections.Generic.List[string]'
        $script:unavailable = $false
        $script:sqlIdentity = "$serverUuid`t$port`t$data"
        $script:offline = @('[auto]',"server-uuid=$serverUuid")
        $script:processes = @(
            [pscustomobject]@{ProcessId=1234;ParentProcessId=900;ExecutablePath=$server;CommandLine="mysqld --datadir=`"$data`" --port=$port";CreationDate=[datetime]'2030-01-01'},
            [pscustomobject]@{ProcessId=1235;ParentProcessId=1234;ExecutablePath=$server;CommandLine="mysqld --datadir=`"$data`" --port=$port";CreationDate=[datetime]'2030-01-01'}
        )
        Mock Get-CimInstance { return $script:processes }
        Mock Get-Content { return $script:offline } -ParameterFilter { $LiteralPath -eq (Join-Path $data 'auto.cnf') }
        Mock powershell.exe { $script:effects.Add('UPGRADE'); $global:LASTEXITCODE=0 }
    }
    foreach ($case in @('reachableForeignEndpoint','wrongSqlDatadir','wrongSqlUuid','wrongSqlPort','wrongBinary','wrongLauncher','wrongAncestry','wrongCliPort','unavailableSqlIdentity','unavailableOfflineIdentity','unavailableProcessIdentity','reusedLauncherPid')) {
        It "$case refuses every mutation/upgrade/shutdown boundary" {
            switch ($case) {
                reachableForeignEndpoint { $script:sqlIdentity="00000000-0000-0000-0000-000000000000`t$port`tC:\foreign-data" }
                wrongSqlDatadir { $script:sqlIdentity="$serverUuid`t$port`tC:\foreign-data" }
                wrongSqlUuid { $script:sqlIdentity="00000000-0000-0000-0000-000000000000`t$port`t$data" }
                wrongSqlPort { $script:sqlIdentity="$serverUuid`t45679`t$data" }
                wrongBinary { $script:processes[1].ExecutablePath='C:\foreign\mysqld.exe' }
                wrongLauncher { $script:processes[0].ProcessId=2222 }
                wrongAncestry { $script:processes[1].ParentProcessId=2222 }
                wrongCliPort { $script:processes[1].CommandLine="mysqld --datadir=`"$data`" --port=45679" }
                unavailableSqlIdentity { $script:unavailable=$true }
                unavailableOfflineIdentity { $script:offline=@() }
                unavailableProcessIdentity { $script:processes=@() }
                reusedLauncherPid { $script:processes[0].CreationDate=[datetime]'2030-01-02' }
            }
            $refused = 0
            foreach ($sql in @('DROP DATABASE IF EXISTS medical_cloud_course','CREATE DATABASE medical_cloud_course','INSERT INTO tenant VALUES (1)','ALTER TABLE tenant ADD COLUMN x INT','SET GLOBAL general_log=OFF','SHUTDOWN')) {
                try { Invoke-FixtureSql $sql '' | Out-Null } catch { $refused++ }
            }
            try { Invoke-Upgrade 'C:\unused-backup.sql' | Out-Null } catch { $refused++ }
            $script:effects.Count | Should Be 0
            $refused | Should Be 7
        }
    }
    It 'exact offline SQL binary launcher child and port identity permits the boundary' {
        Invoke-FixtureSql 'CREATE DATABASE medical_cloud_course' '' | Out-Null
        (Invoke-Upgrade 'C:\unused-backup.sql') | Should Be 0
        Invoke-FixtureSql 'SHUTDOWN' '' | Out-Null
        ($script:effects -join '|') | Should BeExactly '--execute=CREATE DATABASE medical_cloud_course|UPGRADE|--execute=SHUTDOWN'
    }
    It 'replacement after a permitted call refuses subsequent reset write upgrade and shutdown' {
        Invoke-FixtureSql 'CREATE DATABASE medical_cloud_course' '' | Out-Null
        $script:effects.Count | Should Be 1
        $script:effects.Clear()
        $script:sqlIdentity="00000000-0000-0000-0000-000000000000`t$port`tC:\replacement-data"
        $refused=0
        try { Reset-P0Fixture } catch { $refused++ }
        try { Invoke-FixtureSql 'INSERT INTO tenant VALUES (1)' '' } catch { $refused++ }
        try { Invoke-Upgrade 'C:\unused-backup.sql' } catch { $refused++ }
        try { Invoke-FixtureSql 'SHUTDOWN' '' } catch { $refused++ }
        $script:effects.Count | Should Be 0
        $refused | Should Be 4
    }
    It 'saved process timestamp is compared at the exact CIM microsecond precision' {
        $savedCreated=$owner.LauncherCreated
        try {
            $owner.LauncherCreated=$savedCreated.AddTicks(7)
            Invoke-FixtureSql 'CREATE DATABASE medical_cloud_course' '' | Out-Null
            $script:effects.Count | Should Be 1
        } finally { $owner.LauncherCreated=$savedCreated }
    }
    It 'a launcher in the next microsecond bucket is still refused' {
        $savedCreated=$owner.LauncherCreated
        try {
            $owner.LauncherCreated=$savedCreated.AddTicks(10)
            # Pester3's unqualified Throw returns false with an empty expected
            # message on PS7; require the actual identity refusal explicitly.
            { Invoke-FixtureSql 'SHUTDOWN' '' } | Should Throw 'Audit fixture saved launcher identity unavailable'
            $script:effects.Count | Should Be 0
        } finally { $owner.LauncherCreated=$savedCreated }
    }
}
