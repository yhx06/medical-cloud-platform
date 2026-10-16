$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot '..\scripts\verify-attachment-mysql.ps1'
if (-not (Test-Path -LiteralPath $runner)) { throw 'RED: guarded MySQL runner is missing' }
. $runner
$scratchParent = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\..'))
$scratch = Join-Path $scratchParent ('attachments-' + [guid]::NewGuid().ToString('N'))
$owner = [guid]::NewGuid().ToString('N')
$uuid = '12345678-1234-1234-1234-123456789abc'
$created = [datetime]'2030-01-01T00:00:00Z'
[IO.Directory]::CreateDirectory((Join-Path $scratch 'mysql-data')) | Out-Null
[IO.File]::WriteAllText((Join-Path $scratch 'owner.id'),$owner)
[IO.File]::WriteAllText((Join-Path $scratch 'mysql-data\auto.cnf'),"[auto]`nserver-uuid=$uuid")
$client = Join-Path $scratch 'mysql.exe'
$server = Join-Path $scratch 'mysqld.exe'
$options = Join-Path $scratch 'mysql-client.cnf'
foreach ($file in @($client,$server,$options)) { [IO.File]::WriteAllText($file,'fake') }
$fixture = @{ownerId=$owner;runtimeRoot=$scratch;mysql=@{pid=23456;created=$created.ToString('o');executable=$server;host='127.0.0.1';port=45678;serverUuid=$uuid;dataDirectory=(Join-Path $scratch 'mysql-data');clientExecutable=$client;credentialFile=$options}}
$fixturePath = Join-Path $scratch 'fixture.json'
function Get-CimInstance { [pscustomobject]@{ProcessId=23456;CreationDate=$created;ExecutablePath=$server;CommandLine="mysqld --datadir=`"$(Join-Path $scratch 'mysql-data')`" --port=45678 --bind-address=127.0.0.1"} }
function Get-NetTCPConnection { [pscustomobject]@{OwningProcess=23456} }
function Get-AttachmentsSqlIdentity { "$uuid`t45678`t$(Join-Path $scratch 'mysql-data')" }
function Invoke-AttachmentMysqlBoundary {
    param($Fixture,$OptionsPath,$Sql)
    $script:sql.Add($Sql)
    if ($Sql -match '^SELECT SCHEMA_NAME') { if ($script:case -eq 'existing') { 'existing' }; return }
    if ($script:case -eq 'sql-failure') { throw 'sensitive-fake-password-never-output' }
}
function Invoke-AttachmentMavenBoundary {
    param($Executable,$Pom)
    if ($env:COURSE_MYSQL_TEST_USER -eq 'root' -or $env:COURSE_MYSQL_OBSERVER_USER -eq $env:COURSE_MYSQL_TEST_USER) { throw 'Unsafe test identities' }
    if ($script:case -eq 'missing') { return 0 }
    $reports = Join-Path (Split-Path $Pom -Parent) 'paas\target\surefire-reports'
    [IO.Directory]::CreateDirectory($reports) | Out-Null
    foreach ($suite in @('MySqlAttachmentConcurrencyTest','MySqlAttachmentTransactionTest')) {
        $name = 'com.medcloud.course.paas.attachment.' + $suite
        if ($script:case -eq 'wrong') { $name = 'wrong.' + $suite }
        $skipped = if ($script:case -eq 'skipped') { 1 } else { 0 }
        $failures = if ($script:case -eq 'failed') { 1 } else { 0 }
        $tests = if ($script:case -eq 'zero') { 0 } else { 1 }
        $child = if ($tests -gt 0) { '<testcase name="proof" />' } else { '' }
        [IO.File]::WriteAllText((Join-Path $reports ("TEST-$suite.xml")),"<testsuite name=`"$name`" tests=`"$tests`" failures=`"$failures`" errors=`"0`" skipped=`"$skipped`">$child</testsuite>")
    }
    if ($script:case -eq 'maven') { return 7 }; return 0
}
function Get-AttachmentJavaBoundary { 'java version "1.8.0_402"' }
$count = 0
foreach ($script:case in @('owner','remote','stale','unsafe-schema','schema-delete','schema-qualified','snapshot-junction','evidence-junction','existing','sql-failure','wrong','missing','skipped','failed','zero','maven','good')) {
    $snapshot = Join-Path $scratch ('mysql-source-' + [guid]::NewGuid().ToString('N'))
    $external = Join-Path $scratchParent ('mysql-external-' + [guid]::NewGuid().ToString('N'))
    $physicalSnapshot = if ($script:case -eq 'snapshot-junction') { $external } else { $snapshot }
    $course = Join-Path $physicalSnapshot 'course'
    foreach ($directory in @('deploy','paas\src\test\java\com\medcloud\course\paas\attachment')) { [IO.Directory]::CreateDirectory((Join-Path $course $directory)) | Out-Null }
    [IO.File]::WriteAllText((Join-Path $physicalSnapshot 'owner.id'),$owner)
    [IO.File]::WriteAllText((Join-Path $course 'pom.xml'),'<project />')
    [IO.File]::WriteAllText((Join-Path $course 'deploy\schema.sql'),[IO.File]::ReadAllText((Join-Path $PSScriptRoot '..\schema.sql')))
    [IO.File]::WriteAllText((Join-Path $course 'paas\src\test\java\com\medcloud\course\paas\attachment\MySqlAttachmentConcurrencyTest.java'),'map.put("hibernate.hbm2ddl.auto","validate");')
    if ($script:case -eq 'stale') { [IO.Directory]::CreateDirectory((Join-Path $course 'paas\target')) | Out-Null }
    if ($script:case -eq 'unsafe-schema') { [IO.File]::AppendAllText((Join-Path $course 'deploy\schema.sql'),'DROP DATABASE unsafe;') }
    if ($script:case -eq 'schema-delete') { [IO.File]::AppendAllText((Join-Path $course 'deploy\schema.sql'),'DELETE FROM mysql.user;') }
    if ($script:case -eq 'schema-qualified') { [IO.File]::AppendAllText((Join-Path $course 'deploy\schema.sql'),'CREATE TABLE other_schema.proof (id INT);') }
    if ($script:case -eq 'snapshot-junction') {
        New-Item -ItemType Junction -Path $snapshot -Target $external | Out-Null
        $course = Join-Path $snapshot 'course'
    }
    $evidence = Join-Path $scratch 'evidence'
    if ($script:case -eq 'evidence-junction') {
        [IO.Directory]::CreateDirectory($external) | Out-Null
        $junction = Join-Path $scratch ('evidence-link-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Junction -Path $junction -Target $external | Out-Null
        $evidence = Join-Path $junction 'new-child'
    }
    $fixture.ownerId = if ($script:case -eq 'owner') { '0' * 32 } else { $owner }
    $fixture.mysql.host = if ($script:case -eq 'remote') { '192.0.2.1' } else { '127.0.0.1' }
    [IO.File]::WriteAllText($fixturePath,($fixture | ConvertTo-Json -Depth 8))
    $script:sql = New-Object 'System.Collections.Generic.List[string]'
    $prior = @{}
    foreach ($name in @('JAVA_HOME','MYSQL_PWD','COURSE_MYSQL_TEST_URL','COURSE_MYSQL_TEST_USER','COURSE_MYSQL_TEST_PASSWORD','COURSE_MYSQL_OBSERVER_URL','COURSE_MYSQL_OBSERVER_USER','COURSE_MYSQL_OBSERVER_PASSWORD')) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    $result = Invoke-AttachmentMysqlVerification -FixturePath $fixturePath -EvidenceRoot $evidence -MavenExecutable $client -JavaHome $scratch -CoursePom (Join-Path $course 'pom.xml')
    if ($script:case -eq 'evidence-junction' -and (Test-Path -LiteralPath (Join-Path $external 'new-child'))) { throw 'Evidence created outside runtime before ancestor validation' }
    if (($result.exitCode -eq 0) -ne ($script:case -eq 'good')) { throw "Case $script:case had wrong exit at $($result.stage)" }
    $stages = @{owner='fixture';remote='fixture';stale='snapshot';'unsafe-schema'='schema';'schema-delete'='schema';'schema-qualified'='schema';'snapshot-junction'='snapshot';'evidence-junction'='evidence';existing='new-database';'sql-failure'='new-database';wrong='reports';missing='reports';skipped='reports';failed='reports';zero='reports';maven='maven';good='reports'}
    if ($result.stage -ne $stages[$script:case]) { throw "Case $script:case did not reach intended validation" }
    if ($script:case -in @('owner','remote','stale','unsafe-schema','schema-delete','schema-qualified','snapshot-junction','evidence-junction') -and $script:sql.Count -ne 0) { throw 'Invalid target/source reached SQL' }
    if ($script:case -eq 'existing' -and @($script:sql | Where-Object { $_ -match 'CREATE|GRANT' }).Count -gt 0) { throw 'Existing database reached mutation' }
    foreach ($name in $prior.Keys) { if ([Environment]::GetEnvironmentVariable($name,'Process') -cne $prior[$name]) { throw "Environment not restored: $name" } }
    if ($script:case -eq 'good' -and ($result.tests -ne 2 -or $result.suites -ne 2)) { throw 'Report counts incorrect' }
    if ($script:case -eq 'good') {
        $grants = @($script:sql | Where-Object { $_ -match 'GRANT SELECT,INSERT,UPDATE,DELETE' }) -join "`n"
        if ($grants -match '\.\*' -or $grants -notmatch 'ON `course_attachment_test_[a-f0-9]{32}`\.`[a-z_]+` TO') { throw 'Test DML grants must target exact individual tables, never database patterns' }
    }
    if (($result | ConvertTo-Json) -match 'sensitive-fake-password-never-output') { throw 'Exception leaked' }
    $count++
}
Write-Output "PASS: $count behavioral fake-boundary cases (not real MySQL acceptance); scratch retained"
