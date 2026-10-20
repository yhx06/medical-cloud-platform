# Same native-boundary harness, separate 007 baseline/target and result set.
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'upgrade-payment-reference-database.Tests.ps1') --suite007 @args
