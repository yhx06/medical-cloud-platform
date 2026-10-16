[CmdletBinding()]
param([string]$FixturePath,[string]$EvidenceRoot,[string]$MavenExecutable,[string]$JavaHome,[string]$CoursePom)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\tests\attachments-fixture-ownership.ps1')

function Assert-AttachmentMysqlPathChain([string]$Path,[string]$Root,[switch]$AllowMissing) {
    if (-not [IO.Path]::IsPathRooted($Path)) { throw 'Owned path must be absolute' }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    if (-not $full.StartsWith($Root + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Path is outside runtime' }
    $current = $Root
    foreach ($segment in $full.Substring($Root.Length + 1).Split([char]'\')) {
        $current = Join-Path $current $segment
        if (-not (Test-Path -LiteralPath $current)) {
            if ($AllowMissing) { break }
            throw 'Owned path component is missing'
        }
        if ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Owned path component is a reparse point' }
    }
    return $full
}

function Get-AttachmentJavaBoundary([string]$JavaHome) {
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = Join-Path $JavaHome 'bin\java.exe'
    $process.StartInfo.Arguments = '-version'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Java unavailable' }
        return ($stdout + $stderr)
    } finally { $process.Dispose() }
}
function Invoke-AttachmentMysqlBoundary($Fixture,[string]$OptionsPath,[string]$Sql) {
    # Secrets and SQL travel on stdin, never in process command lines or evidence.
    $rows = @($Sql | & $Fixture.mysql.clientExecutable "--defaults-extra-file=$OptionsPath" --protocol=TCP --host=127.0.0.1 "--port=$($Fixture.mysql.port)" --connect-timeout=5 --batch --skip-column-names 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'MySQL operation failed' }
    return $rows
}
function Invoke-AttachmentMavenBoundary([string]$Executable,[string]$Pom,[string]$PrivateDirectory) {
    # The caller has already restricted this unique directory's ACL. Fixed log
    # names keep credential-bearing diagnostics off public output/summary.
    $stdout = Join-Path $PrivateDirectory 'maven.stdout.log'
    $stderr = Join-Path $PrivateDirectory 'maven.stderr.log'
    [IO.File]::WriteAllText($stdout,'')
    [IO.File]::WriteAllText($stderr,'')
    $priorPreference = $ErrorActionPreference
    try {
        # PS5.1 converts native stderr into ErrorRecords. Continue applies only
        # to this invocation; neither stderr nor a stale status can decide pass.
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = $null
        & $Executable -o -B -f $Pom -pl paas -am '-Dtest=MySqlAttachmentConcurrencyTest,MySqlAttachmentTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 1>$stdout 2>$stderr
        $nativeExit = $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $priorPreference }
    if ($nativeExit -isnot [int]) { throw 'Native process exit status unavailable' }
    return $nativeExit
}
function Invoke-AttachmentMysqlVerification {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$FixturePath,[Parameter(Mandatory=$true)][string]$EvidenceRoot,
        [Parameter(Mandatory=$true)][string]$MavenExecutable,[Parameter(Mandatory=$true)][string]$JavaHome,
        [Parameter(Mandatory=$true)][string]$CoursePom)
    $names = @('JAVA_HOME','MYSQL_PWD','COURSE_MYSQL_TEST_URL','COURSE_MYSQL_TEST_USER','COURSE_MYSQL_TEST_PASSWORD','COURSE_MYSQL_OBSERVER_URL','COURSE_MYSQL_OBSERVER_USER','COURSE_MYSQL_OBSERVER_PASSWORD')
    $prior = @{}
    foreach ($name in $names) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    $summary = [ordered]@{exitCode=1;suites=0;tests=0;failures=0;errors=0;skipped=0;environment='owned loopback MySQL; Java 8; RESOURCE_LOCAL';limitations='Does not verify EJB/JTA. Database, unique users, and evidence are retained; no cleanup DROP is issued.';status='refused'}
    $private = $null; $run = $null
    try {
        $summary.stage = 'fixture'
        $fixture = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $FixturePath).Path) | ConvertFrom-Json
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        $root = [IO.Path]::GetFullPath($fixture.runtimeRoot).TrimEnd('\','/')
        $null = Assert-AttachmentMysqlPathChain $fixture.mysql.credentialFile $root
        $options = Assert-AttachmentsOwnedPath $fixture.mysql.credentialFile $root 'MySQL credentials'
        $summary.stage = 'snapshot'
        $null = Assert-AttachmentMysqlPathChain $CoursePom $root
        $pom = Assert-AttachmentsOwnedPath $CoursePom $root 'Source POM'
        $course = Split-Path $pom -Parent
        $snapshot = Split-Path $course -Parent
        if ((Split-Path $pom -Leaf) -ne 'pom.xml' -or (Split-Path $course -Leaf) -ne 'course' -or
            (Split-Path $snapshot -Parent) -ine $root -or (Split-Path $snapshot -Leaf) -notmatch '^mysql-source-[a-f0-9]{32}$' -or
            [IO.File]::ReadAllText((Join-Path $snapshot 'owner.id')).Trim() -cne $fixture.ownerId) { throw 'Source snapshot ownership refused' }
        # Reject stale reports/build outputs and links before any SQL mutation.
        $items = @(Get-ChildItem -LiteralPath $snapshot -Recurse -Force)
        if (@($items | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -or ($_.PSIsContainer -and $_.Name -eq 'target') }).Count -gt 0) { throw 'Source snapshot is not fresh and isolated' }
        $summary.stage = 'schema'
        $schema = [IO.File]::ReadAllText((Join-Path $course 'deploy\schema.sql'))
        # Bind privileged DDL to the reviewed aae787e schema, with only platform
        # CRLF/LF normalization. Any content change requires a fresh review/hash.
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $schemaHash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($schema.Replace("`r`n","`n")))).Replace('-','').ToLowerInvariant() }
        finally { $sha.Dispose() }
        if ($schemaHash -cne '18557d6e270a3058d7ea640e6f15ac3fb9f4463fbcb0e5220ddccf750f978a94') { throw 'Schema differs from reviewed content' }
        $header = '(?is)\ACREATE DATABASE IF NOT EXISTS medical_cloud_course\s+CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\s*USE medical_cloud_course;'
        if ($schema -notmatch $header) { throw 'Unexpected schema header' }
        $ddl = [regex]::Replace($schema,$header,'')
        if ($ddl -match '(?i)\b(DROP|USE|CREATE\s+DATABASE)\b|medical_cloud_course') { throw 'Unsafe schema body' }
        $fixtureJava = [IO.File]::ReadAllText((Join-Path $course 'paas\src\test\java\com\medcloud\course\paas\attachment\MySqlAttachmentConcurrencyTest.java'))
        if ($fixtureJava -notmatch 'map\.put\("hibernate\.hbm2ddl\.auto","validate"\)' -or $fixtureJava -match 'create-drop') { throw 'Tests must validate schema without dropping DDL' }
        $summary.stage = 'tools'
        if (-not (Test-Path -LiteralPath $MavenExecutable -PathType Leaf) -or (Get-AttachmentJavaBoundary $JavaHome) -notmatch 'version "1\.8\.') { throw 'Explicit Maven and Java 8 required' }
        $summary.stage = 'evidence'
        $evidence = Assert-AttachmentMysqlPathChain $EvidenceRoot $root -AllowMissing
        [IO.Directory]::CreateDirectory($evidence) | Out-Null
        $null = Assert-AttachmentMysqlPathChain $evidence $root
        $null = Assert-AttachmentsOwnedPath $evidence $root 'Evidence'
        $id = [guid]::NewGuid().ToString('N')
        $database = 'course_attachment_test_' + $id
        $testUser = 'cat_' + $id.Substring(0,24)
        $observerUser = 'cao_' + $id.Substring(0,24)
        $testPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        $observerPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        $run = Join-Path $evidence ('mysql-verification-' + $id)
        [IO.Directory]::CreateDirectory($run) | Out-Null
        $summary.stage = 'private-options'
        $private = Join-Path $run 'private'
        [IO.Directory]::CreateDirectory($private) | Out-Null
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true,$false)
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $private -AclObject $acl
        $privateOptions = Join-Path $private 'client.cnf'
        [IO.File]::WriteAllText($privateOptions,[IO.File]::ReadAllText($options))
        $env:MYSQL_PWD = $null
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        $summary.stage = 'new-database'
        $existing = @(Invoke-AttachmentMysqlBoundary $fixture $privateOptions "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$database';")
        if ($existing.Count -ne 0) { throw 'New database already exists' }
        $summary.database = $database
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        Invoke-AttachmentMysqlBoundary $fixture $privateOptions "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        Invoke-AttachmentMysqlBoundary $fixture $privateOptions "USE $database;`n$ddl" | Out-Null
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        $tableNames = @([regex]::Matches($ddl,'(?im)^CREATE TABLE IF NOT EXISTS ([a-z_]+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
        if ($tableNames.Count -eq 0) { throw 'No schema tables' }
        # Table-level grants treat database/table identifiers literally, avoiding
        # database-pattern underscore semantics regardless of partial_revokes.
        $tableGrants = @($tableNames | ForEach-Object { 'GRANT SELECT,INSERT,UPDATE,DELETE ON `{0}`.`{1}` TO ''{2}''@''127.0.0.1'';' -f $database,$_,$testUser }) -join "`n"
        Invoke-AttachmentMysqlBoundary $fixture $privateOptions "CREATE USER '$testUser'@'127.0.0.1' IDENTIFIED BY '$testPassword';`n$tableGrants`nCREATE USER '$observerUser'@'127.0.0.1' IDENTIFIED BY '$observerPassword'; GRANT SELECT ON performance_schema.data_lock_waits TO '$observerUser'@'127.0.0.1'; GRANT SELECT ON performance_schema.threads TO '$observerUser'@'127.0.0.1';" | Out-Null
        $env:JAVA_HOME = $JavaHome
        $env:COURSE_MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$($fixture.mysql.port)/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
        $env:COURSE_MYSQL_TEST_USER = $testUser; $env:COURSE_MYSQL_TEST_PASSWORD = $testPassword
        $env:COURSE_MYSQL_OBSERVER_URL = "jdbc:mysql://127.0.0.1:$($fixture.mysql.port)/performance_schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
        $env:COURSE_MYSQL_OBSERVER_USER = $observerUser; $env:COURSE_MYSQL_OBSERVER_PASSWORD = $observerPassword
        Assert-OwnedAttachmentsFixture $fixture 'mysql'
        $summary.stage = 'maven'
        $summary.mavenLogs = [ordered]@{stdout='private/maven.stdout.log';stderr='private/maven.stderr.log'}
        $mavenExit = Invoke-AttachmentMavenBoundary $MavenExecutable $pom $private
        if ($mavenExit -is [int]) { $summary.mavenExitCode = $mavenExit }
        if ($null -eq $mavenExit -or $mavenExit -ne 0) { throw 'Maven failed' }
        $summary.stage = 'reports'
        $reports = Join-Path $course 'paas\target\surefire-reports'
        $expected = @('com.medcloud.course.paas.attachment.MySqlAttachmentConcurrencyTest','com.medcloud.course.paas.attachment.MySqlAttachmentTransactionTest')
        $seen = @{}
        foreach ($file in @(Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File)) {
            $settings = New-Object Xml.XmlReaderSettings
            $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
            $settings.XmlResolver = $null
            $reader = [Xml.XmlReader]::Create($file.FullName,$settings)
            try { $xml = New-Object Xml.XmlDocument; $xml.XmlResolver = $null; $xml.Load($reader) } finally { $reader.Dispose() }
            $suite = $xml.DocumentElement
            if ($suite.LocalName -ne 'testsuite' -or $suite.GetAttribute('name') -notin $expected -or $seen.ContainsKey($suite.GetAttribute('name'))) { throw 'Unexpected or duplicate suite' }
            $seen[$suite.GetAttribute('name')] = $true
            foreach ($key in @('tests','failures','errors','skipped')) {
                $value = $suite.GetAttribute($key)
                if ($value -notmatch '^\d+$') { throw 'Invalid report counts' }
                $summary[$key] += [int]$value
            }
            if ([int]$suite.GetAttribute('tests') -le 0 -or @($suite.SelectNodes('testcase')).Count -ne [int]$suite.GetAttribute('tests') -or
                @($suite.SelectNodes('testcase/skipped | testcase/failure | testcase/error')).Count -gt 0) { throw 'Required tests absent or not passed' }
        }
        $summary.suites = $seen.Count
        if ($seen.Count -ne 2 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 0) { throw 'Required reports not passed' }
        $summary.exitCode = 0; $summary.status = 'passed'; $summary.database = $database
    } catch { $summary.status = 'failed or refused; diagnostics suppressed to protect credentials' }
    finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
        if ($private -and (Test-Path -LiteralPath (Join-Path $private 'client.cnf'))) { Remove-Item -LiteralPath (Join-Path $private 'client.cnf') -Force }
        if ($run) { [IO.File]::WriteAllText((Join-Path $run 'summary.json'),($summary | ConvertTo-Json -Depth 4)) }
    }
    return [pscustomobject]$summary
}
if ($MyInvocation.InvocationName -ne '.') {
    $result = Invoke-AttachmentMysqlVerification @PSBoundParameters
    $result | ConvertTo-Json -Depth 4
    exit $result.exitCode
}
