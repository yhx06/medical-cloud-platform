param(
    [string]$MysqlCli = 'mysql.exe',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [switch]$WithDemoData
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_USER) -or
    [string]::IsNullOrWhiteSpace($env:MEDICAL_COURSE_DB_PASSWORD)) {
    throw 'Set MEDICAL_COURSE_DB_USER and MEDICAL_COURSE_DB_PASSWORD in this terminal first'
}
$originalMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:MEDICAL_COURSE_DB_PASSWORD
try {
    $scripts = @((Join-Path $PSScriptRoot '..\schema.sql'))
    if ($WithDemoData) { $scripts += (Join-Path $PSScriptRoot '..\seed-demo.sql') }
    foreach ($script in $scripts) {
        $sql = Get-Content -LiteralPath $script -Raw -Encoding UTF8
        if ($sql -notmatch 'medical_cloud_course') { throw "Course database guard failed: $script" }
        $sql | & $MysqlCli --protocol=TCP --host=$HostName --port=$Port --default-character-set=utf8mb4 --user=$env:MEDICAL_COURSE_DB_USER
        if ($LASTEXITCODE -ne 0) { throw "MySQL failed while applying $script" }
        Write-Output "Applied $script to medical_cloud_course"
    }
} finally {
    $env:MYSQL_PWD = $originalMysqlPassword
}
