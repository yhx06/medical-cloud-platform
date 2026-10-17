# Run with Windows PowerShell 5.1: exercises actual native stdin, never MySQL.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -ne 5) {
    throw 'Run this native-pipeline regression harness with Windows PowerShell 5.1'
}

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

$projectRoot = [IO.DirectoryInfo]$PSScriptRoot
while ($projectRoot -and -not (Test-Path -LiteralPath (Join-Path $projectRoot.FullName '.local/tests') -PathType Container)) { $projectRoot = $projectRoot.Parent }
if (-not $projectRoot) { throw 'Existing private project tests parent required' }
$scratch = Join-Path $projectRoot.FullName ('.local/tests/e' + [guid]::NewGuid().ToString('N').Substring(0,8))
if (Test-Path -LiteralPath $scratch) { throw 'Unique encoding test scratch already exists' }
[void][IO.Directory]::CreateDirectory($scratch)
$utf8 = New-Object Text.UTF8Encoding($false)
$saved = @{}
foreach ($name in @('TEMP', 'TMP', 'MYSQL_PWD', 'MEDICAL_COURSE_DB_USER', 'MEDICAL_COURSE_DB_PASSWORD', 'ENCODING_CAPTURE_DIR', 'ENCODING_EXIT_CODE')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$savedEncoding = $global:OutputEncoding
$failures = New-Object 'Collections.Generic.List[string]'
try {
    $env:TEMP = $scratch
    $env:TMP = $scratch
    $source = @'
using System;
using System.IO;
class NativeStdinCapture {
    static int Main(string[] args) {
        string root = Environment.GetEnvironmentVariable("ENCODING_CAPTURE_DIR");
        int count = Directory.GetFiles(root, "stdin-*.bin").Length + 1;
        using (Stream input = Console.OpenStandardInput())
        using (Stream output = File.Create(Path.Combine(root, "stdin-" + count + ".bin"))) {
            input.CopyTo(output);
        }
        File.WriteAllLines(Path.Combine(root, "args-" + count + ".txt"), args);
        bool passwordMatches = Environment.GetEnvironmentVariable("MYSQL_PWD") == Environment.GetEnvironmentVariable("MEDICAL_COURSE_DB_PASSWORD");
        File.WriteAllText(Path.Combine(root, "password-" + count + ".txt"), passwordMatches.ToString());
        return Int32.Parse(Environment.GetEnvironmentVariable("ENCODING_EXIT_CODE"));
    }
}
'@
    $sourcePath = Join-Path $scratch 'NativeStdinCapture.cs'
    $native = Join-Path $scratch 'NativeStdinCapture.exe'
    [IO.File]::WriteAllText($sourcePath, $source, $utf8)
    & "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe "/out:$native" $sourcePath
    Assert-True ($LASTEXITCODE -eq 0) 'Native capture helper compilation failed'
    $fixtureScripts = Join-Path $scratch 'deploy\scripts'
    [void][IO.Directory]::CreateDirectory($fixtureScripts)
    $copied = Join-Path $fixtureScripts 'setup-database.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\scripts\setup-database.ps1') -Destination $copied
    # Code points keep the oracle independent of PS5's no-BOM source decoding.
    $clinic = -join ([char[]]@(0x4E91, 0x533B, 0x7597, 0x95E8, 0x8BCA))
    $emoji = [char]::ConvertFromUtf32(0x1F3E5)
    $schema = "-- medical_cloud_course $clinic $emoji schema"
    $seed = "-- medical_cloud_course $clinic $emoji demo"
    [IO.File]::WriteAllText((Join-Path $fixtureScripts '..\schema.sql'), $schema, $utf8)
    [IO.File]::WriteAllText((Join-Path $fixtureScripts '..\seed-demo.sql'), $seed, $utf8)

    function Invoke-Case([string]$name, [int]$exitCode, [bool]$demo, [bool]$missingCredentials, [bool]$unsetPassword) {
        $capture = Join-Path $scratch $name
        [void][IO.Directory]::CreateDirectory($capture)
        $env:ENCODING_CAPTURE_DIR = $capture
        $env:ENCODING_EXIT_CODE = [string]$exitCode
        $env:MEDICAL_COURSE_DB_USER = 'encoding_test_user'
        $env:MEDICAL_COURSE_DB_PASSWORD = 'synthetic-test-password'
        if ($missingCredentials) { $env:MEDICAL_COURSE_DB_PASSWORD = $null }
        $env:MYSQL_PWD = 'synthetic-prior-password'
        if ($unsetPassword) { $env:MYSQL_PWD = $null }
        $priorPassword = $env:MYSQL_PWD
        $priorEncoding = if ($unsetPassword) { New-Object Text.UnicodeEncoding($false, $true) } else { New-Object Text.ASCIIEncoding }
        $global:OutputEncoding = $priorEncoding
        $caught = $null
        try {
            # Dot-sourcing also makes a missing encoding restoration observable.
            $scriptOutput = . $copied -MysqlCli $native -HostName 'test.invalid' -Port 13306 -WithDemoData:$demo
        } catch { $caught = $_ }
        Assert-True ([object]::ReferenceEquals($global:OutputEncoding, $priorEncoding)) "$name did not restore the exact OutputEncoding object"
        Assert-True ($env:MYSQL_PWD -ceq $priorPassword) "$name did not restore MYSQL_PWD"
        if ($missingCredentials) {
            Assert-True ($null -ne $caught) "$name should refuse missing credentials"
            Assert-True ((Get-ChildItem -LiteralPath $capture).Count -eq 0) "$name invoked a native command despite missing credentials"
            return
        }
        Assert-True (($null -ne $caught) -eq ($exitCode -ne 0)) "$name returned the wrong success/failure result"
        if ($exitCode -ne 0) { Assert-True ($caught.Exception.Message -like 'MySQL failed while applying *') "$name did not surface the native failure" }
        $expectedCount = if ($demo -and $exitCode -eq 0) { 2 } else { 1 }
        Assert-True (@(Get-ChildItem -LiteralPath $capture -Filter 'stdin-*.bin').Count -eq $expectedCount) "$name applied an unexpected number of SQL files"
        for ($i = 1; $i -le $expectedCount; $i++) {
            $fixture = if ($i -eq 1) { 'schema.sql' } else { 'seed-demo.sql' }
            $oracle = [IO.File]::ReadAllText((Join-Path $fixtureScripts "..\$fixture"), $utf8)
            $expected = $utf8.GetBytes($oracle + "`r`n")
            $observed = [IO.File]::ReadAllBytes((Join-Path $capture "stdin-$i.bin"))
            Assert-True (-not ($observed.Length -ge 3 -and $observed[0] -eq 239 -and $observed[1] -eq 187 -and $observed[2] -eq 191)) "$name emitted a UTF-8 BOM"
            Assert-True ([Convert]::ToBase64String($observed) -ceq [Convert]::ToBase64String($expected)) "$name native stdin lost Chinese/emoji UTF-8 bytes (question-mark bytes: $(@($observed | Where-Object { $_ -eq 63 }).Count))"
            $observedArgs = [IO.File]::ReadAllLines((Join-Path $capture "args-$i.txt"))
            $expectedArgs = @('--protocol=TCP', '--host=test.invalid', '--port=13306', '--default-character-set=utf8mb4', '--user=encoding_test_user')
            Assert-True (($observedArgs -join '|') -ceq ($expectedArgs -join '|')) "$name changed connection arguments or leaked credentials"
            Assert-True ([IO.File]::ReadAllText((Join-Path $capture "password-$i.txt")) -ceq 'True') "$name did not supply the password through the environment"
        }
    }

    foreach ($case in @(
        @('native-zero-schema-and-demo', 0, $true, $false, $false),
        @('native-seven-stops-before-demo', 7, $true, $false, $false),
        @('native-zero-unset-prior-password', 0, $false, $false, $true),
        @('native-seven-unset-prior-password', 7, $false, $false, $true),
        @('missing-credentials-refusal', 0, $false, $true, $false)
    )) {
        try {
            Invoke-Case @case
            Write-Output "PASS $($case[0])"
        } catch {
            $failures.Add("$($case[0]): $($_.Exception.Message)")
            Write-Output "FAIL $($failures[$failures.Count - 1])"
        }
    }
} finally {
    $global:OutputEncoding = $savedEncoding
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Write-Output "Native boundary evidence: $scratch"
}
if ($failures.Count) { throw "$($failures.Count) encoding regression case(s) failed" }
Write-Output 'All 5 setup-database native encoding cases passed'
