param([ValidateSet('Banner','Bom','Empty','ExistingMismatch','All')][string]$Regression = 'All')
$ErrorActionPreference = 'Stop'
$script = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\scripts\configure-wildfly.ps1')).Path
$scratch = Join-Path ([IO.Path]::GetTempPath()) ("course-wildfly-preflight-" + [guid]::NewGuid().ToString('N'))
$originalTrace = $env:CLI_TRACE
$originalResponse = $env:CLI_RESPONSE_FILE
$originalJbossHome = $env:JBOSS_HOME
$originalDbUser = $env:MEDICAL_COURSE_DB_USER
$originalDbPassword = $env:MEDICAL_COURSE_DB_PASSWORD
$originalJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$originalEncoding = [Console]::OutputEncoding
$originalBanner = $env:CLI_BANNER
$originalExit = $env:CLI_EXIT
$originalStage = $env:CLI_FAIL_STAGE
$originalEmpty = $env:CLI_EMPTY_SUCCESS
$originalDatasource = $env:CLI_DATASOURCE_FILE
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
if "%CLI_BANNER%"=="yes" echo Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8 1>&2
if "%CLI_EXIT%"=="yes" exit /b 9
if "%CLI_FAIL_STAGE%"=="driver" if "%~3"=="--command=/subsystem=datasources/jdbc-driver=mysql:read-resource" exit /b 7
if "%~3"=="--command=/subsystem=datasources/data-source=MedicalCloudCourseDS:read-resource(resolve-expressions=false)" goto datasource
if "%~3"=="--command=/subsystem=datasources/data-source=MedicalCloudCourseDS:read-resource" goto datasource
call :capture "%~3"
if "%~3"=="--file" call :record "%~4"
if "%~4"=="--file" call :record "%~5"
if "%CLI_FAIL_STAGE%"=="file" if "%CLI_IS_FILE%"=="yes" exit /b 8
if "%CLI_FAIL_STAGE%"=="file-refused" if "%CLI_IS_FILE%"=="yes" goto refused
if "%CLI_EMPTY_SUCCESS%"=="yes" if "%CLI_IS_FILE%"=="yes" exit /b 0
type "%CLI_RESPONSE_FILE%"
exit /b 0
:refused
echo {"outcome" =^> "failed", "failure-description" =^> "fixture-password UNTRUSTED-DETAIL"}
exit /b 0
:datasource
if "%CLI_FAIL_STAGE%"=="readback" if exist "%CLI_TRACE%.installed" exit /b 9
if "%CLI_FAIL_STAGE%"=="no-effect" goto missing
if exist "%CLI_TRACE%.installed" goto existing
:missing
echo {"outcome" =^> "failed", "failure-description" =^> "WFLYCTL0216: Management resource not found"}
exit /b 1
:existing
type "%CLI_DATASOURCE_FILE%"
exit /b 0
:capture
set "arg=%~1"
if not "%arg:~0,7%"=="--file=" exit /b 0
set "cliFile=%arg:~7%"
set "CLI_IS_FILE=yes"
copy /y "%cliFile%" "%CLI_TRACE%.input" >nul
echo %cliFile%>"%CLI_TRACE%.path"
echo yes>"%CLI_TRACE%.installed"
type "%cliFile%">>"%CLI_TRACE%"
exit /b 0
:record
set "CLI_IS_FILE=yes"
type "%~1">>"%CLI_TRACE%"
copy /y "%~1" "%CLI_TRACE%.input" >nul
echo %~1>"%CLI_TRACE%.path"
echo yes>"%CLI_TRACE%.installed"
exit /b 0
'@
    $jar = Join-Path $scratch 'connector.jar'
    Set-Content -LiteralPath $jar -Encoding Ascii -Value 'fake connector'
    $env:CLI_TRACE = Join-Path $scratch 'cli-arguments.txt'
    $env:CLI_RESPONSE_FILE = Join-Path $scratch 'cli-response.txt'
    $env:CLI_DATASOURCE_FILE = Join-Path $scratch 'datasource-response.txt'
    $matchingDatasource = @'
{
    "outcome" => "success",
    "result" => {
        "jndi-name" => "java:/jdbc/MedicalCloudCourseDS",
        "connection-url" => "jdbc:mysql://127.0.0.1:63306/medical_cloud_course?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
        "driver-name" => "mysql",
        "transaction-isolation" => "TRANSACTION_REPEATABLE_READ",
        "enabled" => true,
        "user-name" => expression "${env.MEDICAL_COURSE_DB_USER}",
        "password" => expression "${env.MEDICAL_COURSE_DB_PASSWORD}"
    }
}
'@
    [IO.File]::WriteAllText($env:CLI_DATASOURCE_FILE, $matchingDatasource, [Text.UTF8Encoding]::new($false))
    $module = Join-Path $expected 'modules\system\layers\base\com\mysql\main'
    $controller = '127.0.0.1:19990'
    $env:CLI_EXIT = ''; $env:CLI_BANNER = ''; $env:CLI_FAIL_STAGE = ''; $env:CLI_EMPTY_SUCCESS = ''

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
    if (($arguments -join "`n") -match '--file|/data-source=') { throw 'DriverOnly configured a datasource' }
    Clear-Content -LiteralPath $env:CLI_TRACE
    $env:MEDICAL_COURSE_DB_USER = 'fixture-user'
    $env:MEDICAL_COURSE_DB_PASSWORD = 'fixture-password'
    if ($Regression -eq 'ExistingMismatch') {
        [IO.File]::WriteAllText("$env:CLI_TRACE.installed", 'yes')
        [IO.File]::WriteAllText($env:CLI_DATASOURCE_FILE, $matchingDatasource.Replace(':63306/', ':63307/'))
        $rejected = $false
        try { & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DbPort 63306 | Out-Null }
        catch { $rejected = $true }
        if (-not $rejected) { throw 'Existing datasource on wrong database port was accepted as configured' }
        Write-Output 'configure-wildfly existing datasource mismatch: PASS'
        return
    }
    if ($Regression -ne 'Bom') { $env:CLI_BANNER = 'yes' }
    if ($Regression -in @('Empty','All')) { $env:CLI_EMPTY_SUCCESS = 'yes' }
    & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DbPort 63306 | Out-Null
    $bytes = [IO.File]::ReadAllBytes("$env:CLI_TRACE.input")
    if ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) { throw 'CLI input starts with UTF8 BOM' }
    $temporaryPath = (Get-Content -LiteralPath "$env:CLI_TRACE.path" -Raw).Trim()
    if (Test-Path -LiteralPath $temporaryPath) { throw 'Temporary CLI source was not removed' }
    if ($env:JBOSS_HOME -ne $originalJbossHome -or $env:JAVA_TOOL_OPTIONS -ne $originalJavaToolOptions -or
        [Console]::OutputEncoding.CodePage -ne $originalEncoding.CodePage) { throw 'Process environment or console encoding not restored on success' }
    $submitted = Get-Content -LiteralPath $env:CLI_TRACE -Raw
    if ($submitted -notmatch '--transaction-isolation=TRANSACTION_REPEATABLE_READ' -or
        $submitted -notmatch '127\.0\.0\.1:63306/medical_cloud_course' -or
        $submitted -match 'fixture-password') {
        throw 'Datasource CLI did not receive explicit isolation/port with environment-only credentials'
    }
    Clear-Content -LiteralPath $env:CLI_TRACE
    & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DbPort 63306 | Out-Null
    if ((Get-Content -LiteralPath $env:CLI_TRACE -Raw) -match '--file') { throw 'Repeated configuration added datasource twice' }
    # Every target attribute must reject a mismatch both before and after a silent add.
    $mutations = @(
        @{ Name = 'JNDI'; From = 'java:/jdbc/MedicalCloudCourseDS'; To = 'java:/jdbc/OtherDS' },
        @{ Name = 'URL'; From = ':63306/'; To = ':63307/' },
        @{ Name = 'database-case'; From = '/medical_cloud_course?'; To = '/MEDICAL_CLOUD_COURSE?' },
        @{ Name = 'driver'; From = '"mysql"'; To = '"other"' },
        @{ Name = 'isolation'; From = 'TRANSACTION_REPEATABLE_READ'; To = 'TRANSACTION_READ_COMMITTED' },
        @{ Name = 'enabled'; From = '"enabled" => true'; To = '"enabled" => false' },
        @{ Name = 'user-expression'; From = 'expression "${env.MEDICAL_COURSE_DB_USER}"'; To = '"fixture-user"' },
        @{ Name = 'password-expression'; From = 'expression "${env.MEDICAL_COURSE_DB_PASSWORD}"'; To = '"fixture-password"' }
    )
    foreach ($mutation in $mutations) {
        [IO.File]::WriteAllText($env:CLI_DATASOURCE_FILE, $matchingDatasource.Replace($mutation.From, $mutation.To))
        foreach ($phase in @('existing','post-add')) {
            if ($phase -eq 'post-add') { Remove-Item -LiteralPath "$env:CLI_TRACE.installed" }
            Clear-Content -LiteralPath $env:CLI_TRACE
            $rejected = $false
            try { & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DbPort 63306 | Out-Null }
            catch {
                $rejected = $true
                if ($_.Exception.Message -match 'fixture-password|fixture-user') { throw 'Mismatch exposed credentials' }
            }
            if (-not $rejected) { throw "$phase mismatch accepted: $($mutation.Name)" }
            $trace = Get-Content -LiteralPath $env:CLI_TRACE -Raw
            if ($phase -eq 'existing' -and $trace -match '--file') { throw 'Mismatched existing datasource was mutated' }
            if ($phase -eq 'post-add' -and ($trace -notmatch '--file' -or
                @($trace -split "`n" | Where-Object { $_ -match 'data-source=.*read-resource' }).Count -ne 2)) {
                throw 'Silent add was not checked using post-readback'
            }
            if ($env:JBOSS_HOME -ne $originalJbossHome -or $env:JAVA_TOOL_OPTIONS -ne $originalJavaToolOptions) {
                throw 'Mismatch did not restore process environment'
            }
        }
    }
    [IO.File]::WriteAllText($env:CLI_DATASOURCE_FILE, $matchingDatasource)
    $installedDriver = Join-Path $module 'mysql-connector-j.jar'
    Set-Content -LiteralPath $installedDriver -Encoding ASCII -Value 'different connector'
    $rejected = $false
    try { & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DriverOnly | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Different driver hash was accepted' }
    Copy-Item -LiteralPath $jar -Destination $installedDriver -Force
    foreach ($stage in @('home','driver','file','file-refused','readback','no-effect')) {
        $env:CLI_EXIT = ''; $env:CLI_FAIL_STAGE = $stage
        if ($stage -eq 'home') { $env:CLI_EXIT = 'yes' }
        if (Test-Path -LiteralPath "$env:CLI_TRACE.installed") { Remove-Item -LiteralPath "$env:CLI_TRACE.installed" }
        $rejected = $false
        try { & $script -WildFlyHome $expected -ConnectorJar $jar -Controller $controller -DbPort 63306 | Out-Null }
        catch {
            $rejected = $true
            if ($_.Exception.Message -match 'fixture-password|UNTRUSTED-DETAIL') { throw 'CLI error leaked unknown details or password' }
        }
        if (-not $rejected) { throw "CLI failure at $stage was swallowed" }
        if ($env:JBOSS_HOME -ne $originalJbossHome -or $env:JAVA_TOOL_OPTIONS -ne $originalJavaToolOptions -or
            [Console]::OutputEncoding.CodePage -ne $originalEncoding.CodePage) { throw "Environment not restored after $stage failure" }
        if ($stage -like 'file*') {
            $temporaryPath = (Get-Content -LiteralPath "$env:CLI_TRACE.path" -Raw).Trim()
            if (Test-Path -LiteralPath $temporaryPath) { throw "Temporary source remains after $stage failure" }
        }
    }
    Write-Output "configure-wildfly: PASS (32 behavioral checks; PowerShell $($PSVersionTable.PSVersion); $Regression)"
} finally {
    $env:CLI_TRACE = $originalTrace
    $env:CLI_RESPONSE_FILE = $originalResponse
    $env:JBOSS_HOME = $originalJbossHome
    $env:MEDICAL_COURSE_DB_USER = $originalDbUser
    $env:MEDICAL_COURSE_DB_PASSWORD = $originalDbPassword
    $env:JAVA_TOOL_OPTIONS = $originalJavaToolOptions
    $env:CLI_BANNER = $originalBanner
    $env:CLI_EXIT = $originalExit
    $env:CLI_FAIL_STAGE = $originalStage
    $env:CLI_EMPTY_SUCCESS = $originalEmpty
    $env:CLI_DATASOURCE_FILE = $originalDatasource
    [Console]::OutputEncoding = $originalEncoding
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    if ($resolvedScratch.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),
            [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedScratch).StartsWith('course-wildfly-preflight-')) {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    }
}
