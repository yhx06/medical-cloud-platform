param(
    [Parameter(Mandatory = $true)][string]$WildFlyHome,
    [Parameter(Mandatory = $true)][string]$ConnectorJar,
    [ValidateRange(1, 65535)][int]$DbPort = 3306,
    [switch]$DriverOnly
)

$ErrorActionPreference = 'Stop'
$wildflyRoot = (Resolve-Path -LiteralPath $WildFlyHome).Path
$jar = (Resolve-Path -LiteralPath $ConnectorJar).Path
$cli = Join-Path $wildflyRoot 'bin\jboss-cli.bat'
if (-not (Test-Path -LiteralPath $cli)) { throw "WildFly CLI missing: $cli" }
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
$env:JBOSS_HOME = $wildflyRoot
$driverRead = (& $cli --connect '--command=/subsystem=datasources/jdbc-driver=mysql:read-resource' 2>&1 | Out-String)
if ($driverRead -notmatch '"outcome"\s*=>\s*"success"') {
    $driverAdd = (& $cli --connect '--command=/subsystem=datasources/jdbc-driver=mysql:add(driver-name=mysql,driver-module-name=com.mysql,driver-class-name=com.mysql.cj.jdbc.Driver)' 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $driverAdd -notmatch '"outcome"\s*=>\s*"success"') {
        throw "Could not register MySQL driver: $driverAdd"
    }
}
if ($DriverOnly) { Write-Output 'MySQL driver registered; datasource not configured'; return }
if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER) -or
    [string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_PASSWORD)) {
    throw 'Set MEDICAL_COURSE_DB_USER and MEDICAL_COURSE_DB_PASSWORD before starting WildFly'
}
$source = Join-Path $PSScriptRoot '..\wildfly-datasource.cli'
$temporaryCli = New-TemporaryFile
try {
    $template = Get-Content -LiteralPath $source -Raw -Encoding UTF8
    $template.Replace('__DB_PORT__', [string]$DbPort) | Set-Content -LiteralPath $temporaryCli.FullName -Encoding UTF8
    $result = (& $cli --connect "--file=$($temporaryCli.FullName)" 2>&1 | Out-String)
} finally {
    Remove-Item -LiteralPath $temporaryCli.FullName
}
if ($LASTEXITCODE -ne 0 -or $result -match '"outcome"\s*=>\s*"failed"') {
    throw "Datasource configuration failed: $result"
}
Write-Output 'Configured java:/jdbc/MedicalCloudCourseDS (credentials remain environment expressions)'
