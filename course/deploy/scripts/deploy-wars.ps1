param(
    [Parameter(Mandatory = $true)][string]$TomcatHome,
    [Parameter(Mandatory = $true)][string]$WildFlyHome,
    [switch]$Replace
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$deployments = @(
    @{Source = (Join-Path $root 'course\paas\target\paas.war'); Target = (Join-Path $WildFlyHome 'standalone\deployments\paas.war')},
    @{Source = (Join-Path $root 'course\portal\target\portal.war'); Target = (Join-Path $TomcatHome 'webapps\portal.war')}
)
foreach ($deployment in $deployments) {
    if (-not (Test-Path -LiteralPath $deployment.Source -PathType Leaf)) {
        throw "Build WARs first: $($deployment.Source)"
    }
    $targetDirectory = Split-Path -Parent $deployment.Target
    if (-not (Test-Path -LiteralPath $targetDirectory -PathType Container)) {
        throw "Container directory missing: $targetDirectory"
    }
    if (Test-Path -LiteralPath $deployment.Target) {
        if (-not $Replace) { throw "WAR already exists; rerun with -Replace: $($deployment.Target)" }
    }
}
foreach ($deployment in $deployments) {
    if (Test-Path -LiteralPath $deployment.Target) {
        $backup = "$($deployment.Target).backup-$(Get-Date -Format yyyyMMddHHmmss)"
        Copy-Item -LiteralPath $deployment.Target -Destination $backup
        Write-Output "Backed up prior WAR to $backup"
    }
    Copy-Item -LiteralPath $deployment.Source -Destination $deployment.Target
    Write-Output "Deployed $($deployment.Source) -> $($deployment.Target)"
}
