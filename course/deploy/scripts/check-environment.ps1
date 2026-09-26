param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$TomcatHome,
    [Parameter(Mandatory = $true)][string]$WildFlyHome,
    [string]$MysqlCli = 'mysql.exe'
)

$ErrorActionPreference = 'Stop'
$java = Join-Path $JavaHome 'bin\java.exe'
$tomcat = Join-Path $TomcatHome 'bin\catalina.bat'
$wildfly = Join-Path $WildFlyHome 'bin\standalone.bat'
foreach ($path in @($java, $tomcat, $wildfly)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required executable missing: $path" }
}
$versionOutput = (& $java -version 2>&1 | Out-String)
if ($versionOutput -notmatch 'version "1\.8\.') { throw "Course requires JDK 8: $versionOutput" }
$mysqlVersion = (& $MysqlCli --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "MySQL client unavailable: $MysqlCli" }
[pscustomobject]@{
    Java = ($versionOutput.Trim() -split '\r?\n')[0]
    Tomcat = (Resolve-Path -LiteralPath $TomcatHome).Path
    WildFly = (Resolve-Path -LiteralPath $WildFlyHome).Path
    MySQL = $mysqlVersion.Trim()
    DatabaseUserConfigured = -not [string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER)
    DatabasePasswordConfigured = -not [string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_PASSWORD)
    JwtSecretConfigured = $env:MEDICAL_COURSE_JWT_SECRET.Length -ge 32
}
