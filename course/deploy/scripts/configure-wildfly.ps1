param(
    [Parameter(Mandatory = $true)][string]$WildFlyHome,
    [Parameter(Mandatory = $true)][string]$ConnectorJar,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Controller,
    [ValidateRange(1, 65535)][int]$DbPort = 3306,
    [switch]$DriverOnly
)

$ErrorActionPreference = 'Stop'
$wildflyRoot = (Resolve-Path -LiteralPath $WildFlyHome).Path
$jar = (Resolve-Path -LiteralPath $ConnectorJar).Path
$cli = Join-Path $wildflyRoot 'bin\jboss-cli.bat'
if (-not (Test-Path -LiteralPath $cli)) { throw "WildFly CLI missing: $cli" }
$previousJbossHome = $env:JBOSS_HOME
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$previousOutputEncoding = [Console]::OutputEncoding
# Read native streams directly: PS5.1 otherwise promotes Java stderr banners to errors.
function Invoke-WildFlyCli([string]$Operation) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $env:ComSpec
    $start.Arguments = '/d /s /c ""' + $cli + '" "--controller=' + $Controller + '" "--connect" "' + $Operation + '""'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $output }
    } catch {
        throw 'Could not execute WildFly CLI'
    } finally {
        $process.Dispose()
    }
}
function Test-CliSuccess($Response) {
    return ($Response.ExitCode -eq 0 -and $Response.Output -match '"outcome"\s*=>\s*"success"' -and
        $Response.Output -notmatch '"outcome"\s*=>\s*"failed"')
}
function Assert-DatasourceConfiguration($Response) {
    if (-not (Test-CliSuccess $Response)) { throw 'Could not verify datasource configuration' }
    $expectedAttributes = @{
        'jndi-name' = 'java:/jdbc/MedicalCloudCourseDS'
        'connection-url' = "jdbc:mysql://127.0.0.1:$DbPort/medical_cloud_course?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
        'driver-name' = 'mysql'
        'transaction-isolation' = 'TRANSACTION_REPEATABLE_READ'
    }
    foreach ($attribute in $expectedAttributes.Keys) {
        $pattern = '(?m)^\s*"' + [regex]::Escape($attribute) + '"\s*=>\s*"' +
            [regex]::Escape($expectedAttributes[$attribute]) + '"\s*,?\s*$'
        if ($Response.Output -cnotmatch $pattern) { throw 'Datasource does not match requested configuration' }
    }
    if ($Response.Output -cnotmatch '(?m)^\s*"enabled"\s*=>\s*true\s*,?\s*$' -or
        $Response.Output -cnotmatch '(?m)^\s*"user-name"\s*=>\s*expression\s+"\$\{env\.MEDICAL_COURSE_DB_USER\}"\s*,?\s*$' -or
        $Response.Output -cnotmatch '(?m)^\s*"password"\s*=>\s*expression\s+"\$\{env\.MEDICAL_COURSE_DB_PASSWORD\}"\s*,?\s*$') {
        throw 'Datasource does not match requested configuration'
    }
}
try {
    $env:JBOSS_HOME = $wildflyRoot
    $env:JAVA_TOOL_OPTIONS = (($previousJavaToolOptions, '-Dfile.encoding=UTF-8' |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ' ')
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $homeResponse = Invoke-WildFlyCli '--command=/core-service=server-environment:read-attribute(name=home-dir)'
    $homeRead = $homeResponse.Output
    $homeMatch = [regex]::Match($homeRead, '"result"\s*=>\s*"((?:[^"\\]|\\.)*)"')
    if (-not (Test-CliSuccess $homeResponse) -or
        -not $homeMatch.Success) {
        throw 'Could not verify WildFly controller home-dir'
    }
    $reportedHome = $homeMatch.Groups[1].Value -replace '\\\\', '\'
    $connectedRoot = [IO.Path]::GetFullPath($reportedHome).TrimEnd([char[]]@('\', '/'))
    if (-not [string]::Equals($connectedRoot, $wildflyRoot.TrimEnd([char[]]@('\', '/')),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "WildFly controller $Controller belongs to $connectedRoot; expected $wildflyRoot"
    }
    $moduleDirectory = Join-Path $wildflyRoot 'modules\system\layers\base\com\mysql\main'
    New-Item -ItemType Directory -Force -Path $moduleDirectory | Out-Null
    $installedJar = Join-Path $moduleDirectory 'mysql-connector-j.jar'
    if (Test-Path -LiteralPath $installedJar) {
        if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash) {
            throw "Existing JDBC driver differs: $installedJar"
        }
    } else {
        Copy-Item -LiteralPath $jar -Destination $installedJar
    }
    $moduleXml = Join-Path $moduleDirectory 'module.xml'
    if (-not (Test-Path -LiteralPath $moduleXml)) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\mysql-module.xml') -Destination $moduleXml
    }
    $driverRead = Invoke-WildFlyCli '--command=/subsystem=datasources/jdbc-driver=mysql:read-resource'
    if (-not (Test-CliSuccess $driverRead)) {
        if ($driverRead.ExitCode -notin @(0, 1) -or $driverRead.Output -notmatch '"outcome"\s*=>\s*"failed"' -or
            $driverRead.Output -notmatch 'WFLYCTL0216') { throw 'Could not read MySQL driver' }
        $driverAdd = Invoke-WildFlyCli '--command=/subsystem=datasources/jdbc-driver=mysql:add(driver-name=mysql,driver-module-name=com.mysql,driver-class-name=com.mysql.cj.jdbc.Driver)'
        if (-not (Test-CliSuccess $driverAdd)) {
            throw 'Could not register MySQL driver'
        }
    }
    if ($DriverOnly) { Write-Output 'MySQL driver registered; datasource not configured'; return }
    if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER) -or
        [string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_PASSWORD)) {
        throw 'Set MEDICAL_COURSE_DB_USER and MEDICAL_COURSE_DB_PASSWORD before starting WildFly'
    }
    $source = Join-Path $PSScriptRoot '..\wildfly-datasource.cli'
    $datasourceReadCommand = '--command=/subsystem=datasources/data-source=MedicalCloudCourseDS:read-resource(resolve-expressions=false)'
    $datasourceRead = Invoke-WildFlyCli $datasourceReadCommand
    if (Test-CliSuccess $datasourceRead) {
        Assert-DatasourceConfiguration $datasourceRead
        Write-Output 'Datasource java:/jdbc/MedicalCloudCourseDS already configured'
        return
    }
    if ($datasourceRead.ExitCode -notin @(0, 1) -or $datasourceRead.Output -notmatch '"outcome"\s*=>\s*"failed"' -or
        $datasourceRead.Output -notmatch 'WFLYCTL0216') { throw 'Could not read datasource configuration' }
    $temporaryCli = New-TemporaryFile
    try {
        $template = Get-Content -LiteralPath $source -Raw -Encoding UTF8
        [IO.File]::WriteAllText($temporaryCli.FullName, $template.Replace('__DB_PORT__', [string]$DbPort), [Text.UTF8Encoding]::new($false))
        $result = Invoke-WildFlyCli "--file=$($temporaryCli.FullName)"
    } finally {
        Remove-Item -LiteralPath $temporaryCli.FullName
    }
    # The data-source shorthand can succeed silently; the readback proves its effect.
    if ($result.ExitCode -ne 0 -or $result.Output -match '"outcome"\s*=>\s*"failed"') {
        throw 'Datasource configuration failed'
    }
    Assert-DatasourceConfiguration (Invoke-WildFlyCli $datasourceReadCommand)
    Write-Output 'Configured java:/jdbc/MedicalCloudCourseDS (credentials remain environment expressions)'
} finally {
    $env:JBOSS_HOME = $previousJbossHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    [Console]::OutputEncoding = $previousOutputEncoding
}
