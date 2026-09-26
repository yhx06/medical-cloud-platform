param(
    [string]$PortalUrl = 'http://127.0.0.1:8088/portal/login',
    [string]$CatalogUrl = 'http://127.0.0.1:8080/paas/api/catalog/slots?tenantId=11111111-1111-1111-1111-111111111111'
)

$ErrorActionPreference = 'Stop'
foreach ($target in @(@{Name = 'Tomcat portal'; Url = $PortalUrl},
                     @{Name = 'WildFly catalog'; Url = $CatalogUrl})) {
    $result = Invoke-WebRequest -Uri $target.Url -UseBasicParsing -TimeoutSec 10
    if ($result.StatusCode -ne 200) { throw "$($target.Name) returned HTTP $($result.StatusCode)" }
    [pscustomobject]@{Component = $target.Name; Status = $result.StatusCode; Url = $target.Url}
}
