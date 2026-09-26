param(
    [Parameter(Mandatory = $true)][string]$ModelPath,
    [Parameter(Mandatory = $true)][string]$DiagramPath
)

$ErrorActionPreference = 'Stop'
$expectedTables = @('tenant', 'department', 'user_account', 'doctor_profile', 'schedule_slot',
                    'appointment', 'clinical_record', 'payment_order')
if (-not (Test-Path -LiteralPath $ModelPath -PathType Leaf)) {
    throw "PowerDesigner model missing: $ModelPath"
}
if (-not (Test-Path -LiteralPath $DiagramPath -PathType Leaf)) {
    throw "PowerDesigner diagram missing: $DiagramPath"
}

$application = New-Object -ComObject 'PowerDesigner.Application.16.5'
$model = $null
try {
    $model = $application.OpenModel((Resolve-Path -LiteralPath $ModelPath).Path, 0)
    if ($null -eq $model) { throw 'PowerDesigner could not open the model' }
    $tables = @($model.Tables | ForEach-Object { $_ })
    $codes = @($tables | ForEach-Object { $_.Code })
    foreach ($tableCode in $expectedTables) {
        if ($codes -notcontains $tableCode) { throw "Missing table: $tableCode" }
    }
    if ($tables.Count -ne $expectedTables.Count) { throw "Expected 8 tables, found $($tables.Count)" }
    foreach ($table in $tables) {
        $primary = @($table.Columns | Where-Object { $_.Primary })
        if ($primary.Count -eq 0) { throw "No primary key on $($table.Code)" }
    }
    if ($model.References.Count -lt 14) { throw "Expected 14 references, found $($model.References.Count)" }
    if ($model.PhysicalDiagrams.Count -eq 0) { throw 'Physical diagram missing' }
    [pscustomobject]@{Model = $ModelPath; Tables = $tables.Count; References = $model.References.Count;
                       DiagramBytes = (Get-Item -LiteralPath $DiagramPath).Length}
} finally {
    if ($null -ne $model) {
        $model.Close($false)
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($model)
    }
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($application)
}
