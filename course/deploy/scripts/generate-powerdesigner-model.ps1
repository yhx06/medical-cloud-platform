param(
    [string]$SchemaPath = (Join-Path $PSScriptRoot '..\schema.sql'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\..\docs\course-architecture'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$schema = Get-Content -LiteralPath $SchemaPath -Raw -Encoding UTF8
$directory = [System.IO.Path]::GetFullPath($OutputDirectory)
$modelPath = Join-Path $directory 'medical-cloud-course.pdm'
$diagramPath = Join-Path $directory 'medical-cloud-er.png'
if (-not $Force -and ((Test-Path -LiteralPath $modelPath) -or (Test-Path -LiteralPath $diagramPath))) {
    throw 'Model or diagram already exists; use -Force only when intentionally regenerating both files'
}
New-Item -ItemType Directory -Force -Path $directory | Out-Null

$tablePattern = '(?ms)CREATE TABLE IF NOT EXISTS\s+([A-Za-z_][\w]*)\s*\((.*?)\r?\n\);'
$tableMatches = [regex]::Matches($schema, $tablePattern)
if ($tableMatches.Count -ne 8) { throw "Expected 8 tables in schema, found $($tableMatches.Count)" }

$application = New-Object -ComObject 'PowerDesigner.Application.16.5'
$model = $null
try {
    $model = $application.CreateModel(-840675807, '|DBMS=MySQL 5.0|Diagram=PhysicalDiagram', 0)
    if ($null -eq $model) { throw 'PowerDesigner could not create a MySQL physical model' }
    $model.Name = 'Medical Cloud Course Database'
    $model.Code = 'MedicalCloudCourse'
    $model.Comment = 'IaaS data resource for the Java 8, Tomcat 9 and WildFly 10 course system.'
    $tables = @{}
    $columns = @{}
    foreach ($entry in $tableMatches) {
        $tableCode = $entry.Groups[1].Value
        $table = $model.CreateObject(940288576, '', -1, $false)
        $table.Name = $tableCode.Replace('_', ' ')
        $table.Code = $tableCode
        $table.Comment = "medical_cloud_course.$tableCode"
        $tables[$tableCode] = $table
        foreach ($line in ($entry.Groups[2].Value -split '\r?\n')) {
            $definition = $line.Trim().TrimEnd(',')
            if ($definition -notmatch '^([A-Za-z_][\w]*)\s+(VARCHAR\(\d+\)|DECIMAL\(\d+,\d+\)|INT|DATE|TIME)') {
                continue
            }
            $columnCode = $Matches[1]
            $column = $table.Columns.CreateNew(940288577)
            $column.Name = $columnCode.Replace('_', ' ')
            $column.Code = $columnCode
            $column.DataType = $Matches[2].ToLowerInvariant()
            $column.Mandatory = $definition -match '\bNOT NULL\b|\bPRIMARY KEY\b'
            $column.Primary = $definition -match '\bPRIMARY KEY\b'
            $columns["$tableCode.$columnCode"] = $column
        }
    }

    $foreignPattern = 'CONSTRAINT\s+([A-Za-z_][\w]*)\s+FOREIGN KEY\s*\(([A-Za-z_][\w]*)\)\s+REFERENCES\s+([A-Za-z_][\w]*)\s*\(([A-Za-z_][\w]*)\)'
    $referenceCount = 0
    foreach ($entry in $tableMatches) {
        $childTableCode = $entry.Groups[1].Value
        foreach ($link in [regex]::Matches($entry.Groups[2].Value, $foreignPattern)) {
            $constraintCode = $link.Groups[1].Value
            $childColumnCode = $link.Groups[2].Value
            $parentTableCode = $link.Groups[3].Value
            $parentColumnCode = $link.Groups[4].Value
            $parentColumn = $columns["$parentTableCode.$parentColumnCode"]
            $childColumn = $columns["$childTableCode.$childColumnCode"]
            if ($null -eq $parentColumn -or $null -eq $childColumn) {
                throw "Cannot model foreign key $constraintCode"
            }
            $reference = $model.CreateObject(1852601152, '', -1, $false)
            $reference.Name = $constraintCode.Replace('_', ' ')
            $reference.Code = $constraintCode
            $reference.ParentTable = $tables[$parentTableCode]
            $reference.ChildTable = $tables[$childTableCode]
            if ($reference.Joins.Count -ne 1) {
                throw "Expected one column join for $constraintCode"
            }
            $join = $reference.Joins.Item(0)
            $join.ParentTableColumn = $parentColumn
            $join.ChildTableColumn = $childColumn
            $referenceCount++
        }
    }
    if ($referenceCount -ne 14) { throw "Expected 14 foreign keys, found $referenceCount" }

    $diagram = $model.PhysicalDiagrams.Item(0)
    $diagram.Name = 'Medical Cloud ER Diagram'
    [void]$diagram.AttachAllObjects()
    [void]$diagram.AutoLayout()
    $model.Save($modelPath, $true, '')
    $diagram.ExportImage($diagramPath)
    [pscustomobject]@{Model = $modelPath; Diagram = $diagramPath;
                       Tables = $model.Tables.Count; References = $model.References.Count}
} finally {
    if ($null -ne $model) {
        $model.Close($false)
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($model)
    }
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($application)
}
