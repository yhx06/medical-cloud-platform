$ErrorActionPreference = 'Stop'
$guard = Join-Path $PSScriptRoot 'attachments-fixture-ownership.ps1'
if (-not (Test-Path -LiteralPath $guard)) { throw 'RED: attachment ownership guard is missing' }
. $guard
$script:assertionCount = 0

function Assert-Throws([scriptblock]$Action,[string]$Name) {
    $script:assertionCount++
    try { & $Action; throw "ASSERTION FAILED: $Name accepted unsafe input" }
    catch { if ($_.Exception.Message -like 'ASSERTION FAILED:*') { throw } }
}
function Assert-True([bool]$Value,[string]$Name) {
    $script:assertionCount++
    if (-not $Value) { throw "ASSERTION FAILED: $Name" }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('attachments-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$data = Join-Path $testRoot 'mysql-data'
[IO.Directory]::CreateDirectory($data) | Out-Null
$ownerId = [guid]::NewGuid().ToString('N')
[IO.File]::WriteAllText((Join-Path $testRoot 'owner.id'),$ownerId)
$uuid = '12345678-1234-4234-8234-123456789abc'
[IO.File]::WriteAllText((Join-Path $data 'auto.cnf'),"[auto]`nserver-uuid=$uuid`n")
$mysqlExe = Join-Path $testRoot 'mysqld.exe'
[IO.File]::WriteAllText($mysqlExe,'fake')
$created = [datetime]'2030-01-01T00:00:00Z'
$fixture = [pscustomobject]@{
    ownerId=$ownerId; runtimeRoot=$testRoot
    mysql=[pscustomobject]@{host='127.0.0.1';port=45678;database='attachments_test';serverUuid=$uuid;pid=23456;created=$created;executable=$mysqlExe;dataDirectory=$data;clientExecutable='mock-mysql'}
}
$script:fakeProcess = [pscustomobject]@{ProcessId=23456;CreationDate=$created;ExecutablePath=$mysqlExe;CommandLine="`"$mysqlExe`" --datadir=`"$data`" --port=45678 --bind-address=127.0.0.1"}
$script:identity = "$uuid`t45678`t$data"
$script:stopped = 0
$script:sqlCalls = 0
$script:listenerOwner = 23456
function Get-CimInstance { return $script:fakeProcess }
function Get-NetTCPConnection {
    param($LocalAddress,$LocalPort,$State,$ErrorAction)
    if ($script:fakeListenersAbsent) { return @() }
    if ($script:storageConsolePort -and $LocalPort -eq $script:storageConsolePort) { return [pscustomobject]@{OwningProcess=$script:storageConsoleOwner;LocalPort=$LocalPort} }
    if ($script:portalShutdownPort -and $LocalPort -eq $script:portalShutdownPort) { return [pscustomobject]@{OwningProcess=$script:portalShutdownOwner} }
    return [pscustomobject]@{OwningProcess=$script:listenerOwner;LocalPort=$(if ($LocalPort) { $LocalPort } else { 45678 })}
}
function Get-AttachmentsSqlIdentity { $script:sqlCalls++; return $script:identity }
function Stop-Process { $script:stopped++ }

foreach ($port in @(3318,8180,8188,10090)) {
    $fixture.mysql.port = $port
    Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } "rejectsProtectedPortsAndRuntimeRoot port $port"
}
$fixture.mysql.port = 45678
$fixture.runtimeRoot = 'C:\task8-runtime-20260928'
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } 'rejectsProtectedPortsAndRuntimeRoot old runtime'
$fixture.runtimeRoot = $testRoot

$fixture.mysql.pid = 99999
Assert-Throws { Stop-OwnedAttachmentsFixture $fixture 'mysql' } 'foreignPidOrDatadirCannotBeStopped pid'
$fixture.mysql.pid = 23456
$fixture.mysql.dataDirectory = Join-Path $testRoot 'foreign-data'
Assert-Throws { Stop-OwnedAttachmentsFixture $fixture 'mysql' } 'foreignPidOrDatadirCannotBeStopped datadir'
$fixture.mysql.dataDirectory = $data
Assert-True ($script:stopped -eq 0) 'foreign process was stopped'

$fixture.mysql.serverUuid = '00000000-0000-0000-0000-000000000000'
$sqlBeforeWrongUuid = $script:sqlCalls
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } 'wrongUuidCannotExecuteSql'
Assert-True ($script:sqlCalls -eq $sqlBeforeWrongUuid) 'wrong UUID executed SQL identity probe'
$fixture.mysql.serverUuid = $uuid
Assert-True ($script:stopped -eq 0) 'wrong UUID caused shutdown'
Assert-OwnedAttachmentsFixture $fixture 'mysql'
$script:listenerOwner = 99999
$sqlBeforeForeignListener = $script:sqlCalls
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } 'foreign MySQL listener is refused before credential-bearing identity query'
Assert-True ($script:sqlCalls -eq $sqlBeforeForeignListener) 'foreign MySQL listener received SQL identity probe'
$script:listenerOwner = 23456
$script:identity = "00000000-0000-0000-0000-000000000000`t45678`t$data"
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } 'live foreign UUID refused'
$script:identity = "$uuid`t45678`t$data"
$script:fakeProcess.CreationDate = [datetime]'2030-01-02T00:00:00Z'
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'mysql' } 'reused PID refused'
$script:fakeProcess.CreationDate = $created
$storageData = Join-Path $testRoot 'storage-data'
[IO.Directory]::CreateDirectory($storageData) | Out-Null
$storageExe = Join-Path $testRoot 'minio.exe'
[IO.File]::WriteAllText($storageExe,'fake')
$fixture | Add-Member -NotePropertyName storage -NotePropertyValue ([pscustomobject]@{pid=23457;created=$created;executable=$storageExe;port=45679;consolePort=45682;url='http://127.0.0.1:45679';dataDirectory=$storageData})
$script:storageConsolePort = 45682
$script:storageConsoleOwner = 23457
$script:fakeProcess = [pscustomobject]@{ProcessId=23457;CreationDate=$created;ExecutablePath=$storageExe;CommandLine="`"$storageExe`" server `"$storageData`" --address 127.0.0.1:12345 --console-address 127.0.0.1:45682"}
$script:listenerOwner = 23457
Assert-Throws { Stop-OwnedAttachmentsFixture $fixture 'storage' } 'storage port mismatch cannot be stopped'
Assert-True ($script:stopped -eq 0) 'storage wrong port was stopped'
$script:fakeProcess.CommandLine = "`"$storageExe`" server `"$storageData`" --address 127.0.0.1:45679 --console-address 127.0.0.1:45682"
$script:listenerOwner = 99999
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'storage' } 'foreign storage listener refused'
$script:listenerOwner = 23457
Assert-OwnedAttachmentsFixture $fixture 'storage'
foreach ($badConsolePort in @(0,-1,65536,3318,8180,8188,10090)) {
    $fixture.storage.consolePort = $badConsolePort
    Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'storage' } "storage invalid/protected console $badConsolePort refused before operations"
}
$fixture.storage.consolePort = 45682
$script:fakeProcess.CommandLine = "`"$storageExe`" server `"$storageData`" --address 127.0.0.1:45679 --console-address 127.0.0.1:12345"
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'storage' } 'storage console command address mismatch refused'
$script:fakeProcess.CommandLine = "`"$storageExe`" server `"$storageData`" --address 127.0.0.1:45679 --console-address 0.0.0.0:45682"
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'storage' } 'storage wildcard console bind refused'
$script:fakeProcess.CommandLine = "`"$storageExe`" server `"$storageData`" --address 127.0.0.1:45679 --console-address 127.0.0.1:45682"
$script:storageConsoleOwner = 99999
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'storage' } 'foreign storage console listener refused before operations'
$script:storageConsoleOwner = 23457
Assert-OwnedAttachmentsFixture $fixture 'storage'
Assert-Throws { Stop-OwnedAttachmentsFixture $fixture 'storage' } 'storage without graceful shutdown credentials is refused'
Assert-True ($script:stopped -eq 0) 'storage without graceful shutdown was force stopped'
$script:fakeProcess = [pscustomobject]@{ProcessId=23456;CreationDate=$created;ExecutablePath=$mysqlExe;CommandLine="`"$mysqlExe`" --datadir=`"$data`" --port=45678 --bind-address=127.0.0.1"}
$portalBase = Join-Path $testRoot 'portal-base'
[IO.Directory]::CreateDirectory((Join-Path $portalBase 'conf')) | Out-Null
$script:portalShutdownPort = 45681
[IO.File]::WriteAllText((Join-Path $portalBase 'conf\server.xml'),'<Server port="45681"><Service><Connector port="45680" address="127.0.0.1" /></Service></Server>')
$javaExe = Join-Path $testRoot 'java.exe'
[IO.File]::WriteAllText($javaExe,'fake')
$fixture | Add-Member -NotePropertyName portal -NotePropertyValue ([pscustomobject]@{pid=23458;created=$created;executable=$javaExe;port=45680;shutdownPort=45681;url='http://127.0.0.1:45680';dataDirectory=$portalBase})
$script:fakeProcess = [pscustomobject]@{ProcessId=23458;CreationDate=$created;ExecutablePath=$javaExe;CommandLine="`"$javaExe`" -Dcatalina.base=`"$portalBase`" org.apache.catalina.startup.Bootstrap"}
$script:listenerOwner = 23458
$script:portalShutdownOwner = 99999
Assert-Throws { Assert-OwnedAttachmentsFixture $fixture 'portal' } 'foreign portal shutdown port refused'
$script:portalShutdownPort = 0
$script:listenerOwner = 23456
$script:fakeProcess = [pscustomobject]@{ProcessId=23456;CreationDate=$created;ExecutablePath=$mysqlExe;CommandLine="`"$mysqlExe`" --datadir=`"$data`" --port=45678 --bind-address=127.0.0.1"}

$newScript = Join-Path $PSScriptRoot '..\scripts\new-attachments-fixture.ps1'
if (-not (Test-Path -LiteralPath $newScript)) { throw 'RED: attachment fixture creator is missing' }
$existing = Join-Path ([IO.Path]::GetTempPath()) ('attachments-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($existing) | Out-Null
$sentinel = Join-Path $existing 'sentinel.txt'
[IO.File]::WriteAllText($sentinel,'unchanged')
Assert-Throws { & $newScript -RuntimeRoot $existing -MySqlBin 'missing' -JavaHome 'missing' -TomcatHome 'missing' -WildFlyHome 'missing' -MinioExecutable 'missing' -MinioClientExecutable 'missing' 6>&1 | Out-Null } 'existingDirectoryIsNotOverwritten'
Assert-True (([IO.File]::ReadAllText($sentinel)) -eq 'unchanged') 'existing directory changed'
$missingMinioRoot = Join-Path ([IO.Path]::GetTempPath()) ('attachments-' + [guid]::NewGuid().ToString('N'))
Assert-Throws { & $newScript -RuntimeRoot $missingMinioRoot -MySqlBin 'D:\MySQL\bin' -JavaHome 'D:\Java\jdk1.8.0_341' -TomcatHome 'D:\apache-tomcat-9.0.24' -WildFlyHome 'D:\wildfly-10.0.0.Final' -MinioExecutable 'missing-minio.exe' -MinioClientExecutable 'missing-mc.exe' | Out-Null } 'missing MinIO fails before fixture creation'
Assert-True (-not (Test-Path -LiteralPath $missingMinioRoot)) 'missing MinIO created runtime tree'

$output = ''
try {
    $output = @(& $newScript -RuntimeRoot (Join-Path ([IO.Path]::GetTempPath()) ('attachments-' + [guid]::NewGuid().ToString('N'))) -MySqlBin 'missing' -JavaHome 'missing' -TomcatHome 'missing' -WildFlyHome 'missing' -MinioExecutable 'missing' -MinioClientExecutable 'missing' 6>&1 2>&1 | Out-String) -join ''
} catch { $output += $_.Exception.Message }
Assert-True ($output -notmatch '(?i)(password|secret|access.?key|https?://|jdbc:)') 'credentialsAndUrlsNotPrinted'
$creatorText = [IO.File]::ReadAllText($newScript)
Assert-True ($creatorText -match 'Assert-NewMysqlBootstrapOwnership') 'bootstrap SQL must assert fresh PID/datadir/UUID ownership'
Assert-True ($creatorText -match '--mysqlx=0') 'fixture must disable unallocated MySQL X listener'
Assert-True ($creatorText -match '--no-monitor') 'Windows MySQL monitor must not change listener PID'
Assert-True ($creatorText -match '\[string\[\]\]\$queryArgs') 'SQL identity argument splat must retain array type'
Assert-True ($creatorText -match '\$dbName = ''medical_cloud_course''') 'fixture database must match protected upgrader contract'
Assert-True ($creatorText -match 'CREATE DATABASE \$dbName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;') 'empty fixture must preserve audited schema default collation'
Assert-True ([bool](Get-Command Wait-OwnedAttachmentsFixtureStopped -ErrorAction SilentlyContinue)) 'normal stop must verify original process and listener disappearance'
Assert-Throws { Wait-OwnedAttachmentsFixtureStopped $fixture.mysql 0 } 'exit zero with live original process cannot be reported stopped'
$script:fakeProcess = $null
Assert-Throws { Wait-OwnedAttachmentsFixtureStopped $fixture.mysql 0 } 'absent process with remaining listener cannot be reported stopped'
$script:fakeListenersAbsent = $true
Wait-OwnedAttachmentsFixtureStopped $fixture.mysql 0
Write-Output "PASS: attachment fixture ownership guards ($script:assertionCount assertions, 5 required scenarios)"
