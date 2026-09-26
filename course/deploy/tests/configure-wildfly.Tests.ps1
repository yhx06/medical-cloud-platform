$ErrorActionPreference = 'Stop'
$script = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\scripts\configure-wildfly.ps1')).Path
$scratch = Join-Path ([IO.Path]::GetTempPath()) ("course-wildfly-preflight-" + [guid]::NewGuid().ToString('N'))
$originalTrace = $env:CLI_TRACE
$originalResponse = $env:CLI_RESPONSE_FILE
$originalJbossHome = $env:JBOSS_HOME
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $expected = Join-Path $scratch '课程-wildfly'
    $other = Join-Path $scratch 'other-wildfly'
    $bin = Join-Path $expected 'bin'
    New-Item -ItemType Directory -Path $bin, $other | Out-Null
    $fakeCli = Join-Path $bin 'jboss-cli.bat'
    Set-Content -LiteralPath $fakeCli -Encoding Ascii -Value @'
@echo off
echo %*>>"%CLI_TRACE%"
type "%CLI_RESPONSE_FILE%"
'@
    $jar = Join-Path $scratch 'connector.jar'
    Set-Content -LiteralPath $jar -Encoding Ascii -Value 'fake connector'
    $env:CLI_TRACE = Join-Path $scratch 'cli-arguments.txt'
    $env:CLI_RESPONSE_FILE = Join-Path $scratch 'cli-response.txt'
    $module = Join-Path $expected 'modules\system\layers\base\com\mysql\main'
    $controller = '127.0.0.1:19990'

    Set-Content -LiteralPath $env:CLI_RESPONSE_FILE -Encoding UTF8 -Value @(
        '{',
        '    "outcome" => "success",',
        ('    "result" => "{0}"' -f $other.Replace('\', '\\')),
        '}'
    )
    $rejected = $false
    $rejection = $null
    try {
        & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DriverOnly | Out-Null
    } catch {
        $rejected = $true
        $rejection = $_
    }
    if (-not $rejected) { throw 'Wrong WildFly controller was accepted' }
    if (Test-Path -LiteralPath $module) { throw 'Module files were written before controller validation' }
    if (-not (Test-Path -LiteralPath $env:CLI_TRACE)) {
        throw "Controller validation stopped before the required read-only CLI preflight: $rejection"
    }
    $arguments = @(Get-Content -LiteralPath $env:CLI_TRACE)
    if ($arguments.Count -ne 1 -or $arguments[0] -notmatch 'read-attribute\(name=home-dir\)' -or
        $arguments[0] -notmatch [regex]::Escape("--controller=$controller")) {
        throw "Expected one read-only CLI preflight with explicit controller: $arguments"
    }

    Clear-Content -LiteralPath $env:CLI_TRACE
    Set-Content -LiteralPath $env:CLI_RESPONSE_FILE -Encoding UTF8 -Value @(
        '{',
        '    "outcome" => "failed",',
        '    "failure-description" => "controller unavailable"',
        '}'
    )
    $rejected = $false
    try {
        & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DriverOnly | Out-Null
    } catch {
        $rejected = $true
    }
    if (-not $rejected -or (Test-Path -LiteralPath $module)) {
        throw 'Failed controller probe did not stop before module writes'
    }

    Clear-Content -LiteralPath $env:CLI_TRACE
    Set-Content -LiteralPath $env:CLI_RESPONSE_FILE -Encoding UTF8 -Value @(
        '{',
        '    "outcome" => "success",',
        ('    "result" => "{0}"' -f $expected.Replace('\', '\\')),
        '}'
    )
    & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DriverOnly | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $module 'mysql-connector-j.jar'))) {
        throw 'Matching controller did not install the connector'
    }
    $arguments = @(Get-Content -LiteralPath $env:CLI_TRACE)
    if ($arguments.Count -lt 2 -or @($arguments | Where-Object {
                $_ -notmatch [regex]::Escape("--controller=$controller")
            }).Count -ne 0) {
        throw 'Controller argument was not passed to every CLI invocation'
    }
    Write-Output 'configure-wildfly controller preflight: PASS'
} finally {
    $env:CLI_TRACE = $originalTrace
    $env:CLI_RESPONSE_FILE = $originalResponse
    $env:JBOSS_HOME = $originalJbossHome
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    if ($resolvedScratch.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),
            [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedScratch).StartsWith('course-wildfly-preflight-')) {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
}
