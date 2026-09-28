param(
    [string]$ModelPath = (Join-Path $PSScriptRoot 'medical-cloud-iaas-generated.eam')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ModelPath)) {
    throw "EAM model not found: $ModelPath"
}

[xml]$model = Get-Content -LiteralPath $ModelPath -Raw -Encoding UTF8
function Get-NamedObjects([string]$type) {
    $model.SelectNodes("//*[local-name()='$type' and @Id]")
}
function Get-ObjectName($object) {
    $nameNode = $object.SelectSingleNode("./*[local-name()='Name']")
    if ($null -eq $nameNode) { return '' }
    return $nameNode.InnerText
}

$diagrams = @(Get-NamedObjects 'TechnologyInfrastructureDiagram')
if ($diagrams.Count -ne 2) { throw "Expected 2 technology diagrams, found $($diagrams.Count)" }
$diagramNames = @($diagrams | ForEach-Object { Get-ObjectName $_ })
foreach ($required in @('Medical Cloud Application Scenario', 'IaaS Server Cluster - 70 Logical Servers')) {
    if ($required -notin $diagramNames) { throw "Missing diagram: $required" }
}

$servers = @(Get-NamedObjects 'HardwareServer')
$iaasServers = @($servers | Where-Object { (Get-ObjectName $_) -match '^IaaS-\d{2}$' })
if ($iaasServers.Count -ne 70) { throw "Expected 70 numbered IaaS servers, found $($iaasServers.Count)" }
$actualNames = @($iaasServers | ForEach-Object { Get-ObjectName $_ })
if (@($actualNames | Sort-Object -Unique).Count -ne 70) { throw 'Duplicate IaaS server number' }
foreach ($number in 1..70) {
    $expectedName = 'IaaS-{0:00}' -f $number
    if ($expectedName -notin $actualNames) { throw "Missing $expectedName" }
}

$configuration = @('Intel i5 3.2 GHz', '3.2*2^10*2^10', '4 GB', '100 GB', 'IPv4', 'IPv6', 'Windows', 'antivirus', 'firewall', 'intrusion')
foreach ($server in $iaasServers) {
    $comment = $server.SelectSingleNode("./*[local-name()='Comment']")
    $text = if ($null -eq $comment) { '' } else { $comment.InnerText }
    foreach ($required in $configuration) {
        if (-not $text.Contains($required)) { throw "$(Get-ObjectName $server) missing configuration: $required" }
    }
}

$nodes = @(Get-NamedObjects 'NetworkNode')
if ($nodes.Count -lt 7) { throw "Expected at least 7 network nodes, found $($nodes.Count)" }
$links = @(Get-NamedObjects 'InfrastructureLink')
if ($links.Count -lt 77) { throw "Expected at least 77 infrastructure links, found $($links.Count)" }
$areas = @(Get-NamedObjects 'ArchitectureArea')
if ($areas.Count -lt 6) { throw "Expected at least 6 architecture areas, found $($areas.Count)" }

Write-Output "PASS: 2 diagrams, 70 unique configured IaaS servers, $($nodes.Count) network nodes, $($links.Count) links, $($areas.Count) areas."
