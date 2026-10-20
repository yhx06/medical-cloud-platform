# Offline acceptance tests for the hospital-onboarding verify driver (Task 7).
# Only external boundaries are replaced: SQL, HTTP and the Maven/native process.
# The driver's own gate registry, consumption record, identity re-checks, safe
# error handling and strict summary are exercised against the real code.
#
# Intentionally ASCII for native Windows PowerShell 5.1 without a BOM.
param([string]$CaseFilter = '*')
$ErrorActionPreference = 'Stop'
$AcceptSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$AcceptProject = Split-Path (Split-Path $AcceptSource -Parent) -Parent
$AcceptTestRoot = Join-Path $AcceptProject ('.local/tests/hob-acceptance-' + [guid]::NewGuid().ToString('N'))
$AcceptSavedTemp = $env:TEMP
$AcceptSavedTmp = $env:TMP
$AcceptTests = 0
$AcceptFailures = New-Object 'Collections.Generic.List[string]'
[IO.Directory]::CreateDirectory($AcceptTestRoot) | Out-Null
$env:TEMP = $AcceptTestRoot
$env:TMP = $AcceptTestRoot

function Test-AcceptBehavior([string]$AcceptName, [scriptblock]$AcceptAction) {
    if ($AcceptName -notlike $CaseFilter) { return }
    $script:AcceptTests++
    try { & $AcceptAction; Write-Output ('PASS ' + $AcceptName) }
    catch { $script:AcceptFailures.Add($AcceptName + ': ' + $_.Exception.Message); Write-Output ('FAIL ' + $AcceptName + ': ' + $_.Exception.Message) }
}
function Assert-AcceptTest([bool]$AcceptCondition, [string]$AcceptMessage) { if (-not $AcceptCondition) { throw $AcceptMessage } }
function Assert-AcceptTestThrows([scriptblock]$AcceptAction, [string]$AcceptPattern = '*') {
    $AcceptCaught = $false
    try { & $AcceptAction | Out-Null } catch {
        $AcceptCaught = $true
        if ($_.Exception.Message -notlike $AcceptPattern) { throw ('Unexpected refusal: ' + $_.Exception.Message) }
    }
    if (-not $AcceptCaught) { throw 'Unsafe operation was accepted' }
}

$AcceptEntry = Join-Path $PSScriptRoot '../scripts/verify-hospital-onboarding.ps1'

# ---- Scenario scaffolding ---------------------------------------------------
# A 32-hex owner and fictional manifest/journal files consumed by the real
# Read-OnboardingFixtureContext. No actual service is created or contacted.
$AcceptOwner = [guid]::NewGuid().ToString('N')
$AcceptSandbox = $AcceptProject
$AcceptRuntime = Join-Path $AcceptSandbox ('.local/runtime/attachments-' + $AcceptOwner)
[IO.Directory]::CreateDirectory($AcceptRuntime) | Out-Null
[IO.File]::WriteAllText((Join-Path $AcceptRuntime 'owner.id'), $AcceptOwner)
[IO.File]::WriteAllText((Join-Path $AcceptRuntime 'accounts.json'), (@(
    @{username = 'platform_admin'; role = 'PLATFORM_ADMIN'; password = 'platform-secret-1'},
    @{username = 'platform_second'; role = 'PLATFORM_ADMIN'; password = 'platform-secret-2'},
    @{username = 'hospital_a'; role = 'HOSPITAL_ADMIN'; password = 'hospital-secret'},
    @{username = 'doctor_a'; role = 'DOCTOR'; password = 'doctor-secret'},
    @{username = 'patient_demo'; role = 'PATIENT'; password = 'patient-secret'}
) | ConvertTo-Json -Depth 4))
[IO.File]::WriteAllText((Join-Path $AcceptRuntime 'credentials.json'), (@{
    dbPassword = 'db-secret'; mysqlRootPassword = 'root-secret'; storageRootSecret = 'storage-root-secret'; storageAppSecret = 'storage-app-secret'; jwtSecret = 'jwt-fixture-secret'; migrationRootPassword = 'migration-fixture-secret'; tomcatShutdown = 'shutdown-fixture-secret'
} | ConvertTo-Json))
$AcceptEvidence = Join-Path $AcceptSandbox ('.local\evidence\hob-offline-' + $AcceptOwner)
[IO.Directory]::CreateDirectory($AcceptEvidence) | Out-Null

$AcceptContext = @{
    Root = $AcceptRuntime
    Options = @{NewRunId = $AcceptOwner; MySqlBin = (Join-Path $AcceptSandbox 'mysql'); MavenExecutable = (Join-Path $AcceptSandbox 'mvn.cmd'); WildFlyHome = (Join-Path $AcceptSandbox 'wildfly')}
    Journal = @{ownerId = $AcceptOwner; sourceCommit = ('a' * 40); schemaVersion = '007'; state = 'running'; components = @{
        mysql = @{port = 49101; status = 'running'; serverUuid = '11111111-1111-1111-1111-111111111111'; pid = 1001; created = '2026-01-01T00:00:00Z'; executable = (Join-Path $AcceptSandbox 'mysqld.exe'); dataDirectory = (Join-Path $AcceptRuntime 'mysql-data'); arguments = @('--port=49101'); credentialFile = (Join-Path $AcceptRuntime 'mysql-client.cnf'); database = 'medical_cloud_course'; host = '127.0.0.1'; authenticated = $true; schemaVersion = '007'; clientExecutable = (Join-Path $AcceptSandbox 'mysql.exe')}
        mysqlMigration = @{port = 49102; status = 'running'; serverUuid = '22222222-2222-2222-2222-222222222222'; pid = 1002; created = '2026-01-01T00:00:00Z'; executable = (Join-Path $AcceptSandbox 'mysqld.exe'); dataDirectory = (Join-Path $AcceptRuntime 'mysqlMigration-data'); arguments = @('--port=49102'); credentialFile = (Join-Path $AcceptRuntime 'mysqlMigration-client.cnf'); database = 'medical_cloud_course'; host = '127.0.0.1'; authenticated = $true; schemaVersion = '007'; clientExecutable = (Join-Path $AcceptSandbox 'mysql.exe')}
        storage = @{port = 49103; status = 'running'; pid = 1003; created = '2026-01-01T00:00:00Z'; executable = (Join-Path $AcceptSandbox 'minio.exe'); dataDirectory = (Join-Path $AcceptRuntime 'storage-data'); arguments = @('server'); serverUuid = ''}
        paas = @{port = 49104; status = 'running'; pid = 1004; created = '2026-01-01T00:00:00Z'; executable = (Join-Path $AcceptSandbox 'java.exe'); dataDirectory = (Join-Path $AcceptRuntime 'paas-base'); arguments = @('--port=49104'); serverUuid = ''}
        portal = @{port = 49105; status = 'running'; pid = 1005; created = '2026-01-01T00:00:00Z'; executable = (Join-Path $AcceptSandbox 'java.exe'); dataDirectory = (Join-Path $AcceptRuntime 'portal-base'); arguments = @('--port=49105'); serverUuid = ''}
    }}
    Manifest = @{ownerId = $AcceptOwner; runtimeRoot = $AcceptRuntime; sourceCommit = ('a' * 40); schemaVersion = '007'; state = 'running'; accountsFile = (Join-Path $AcceptRuntime 'accounts.json')}
    Ports = @{}
}

# Import the verify driver module without running its acceptance entry.
. $AcceptEntry -FixturePath (Join-Path $AcceptRuntime 'fixture.json') -EvidenceRoot $AcceptEvidence -NewRunId $AcceptOwner -Execute

# ---- Reusable overrides and reset ------------------------------------------
$AcceptContext.Options.MySqlBin = Join-Path $AcceptTestRoot 'mysql'
$AcceptContext.Options.JavaHome = Join-Path $AcceptTestRoot 'java8'
$AcceptContext.Options.MavenExecutable = Join-Path $AcceptTestRoot 'mvn.cmd'
foreach ($AcceptTool in @('java8/bin/java.exe', 'java8/bin/javac.exe', 'mysql/mysql.exe', 'mvn.cmd')) {
    $AcceptToolPath = Join-Path $AcceptTestRoot $AcceptTool
    [void][IO.Directory]::CreateDirectory((Split-Path $AcceptToolPath -Parent))
    [IO.File]::WriteAllText($AcceptToolPath, 'fictional offline tool')
}
$AcceptContext.Manifest.tools = @{host = $AcceptContext.Options}
$AcceptContext.Journal.tools = $AcceptContext.Manifest.tools
$AcceptContext.Journal.runtimeRoot = $AcceptRuntime
$AcceptContext.Journal.nativeExits = @()
foreach ($AcceptComponent in $AcceptContext.Journal.components.Keys) {
    $AcceptPart = $AcceptContext.Journal.components[$AcceptComponent]
    $AcceptPart.url = 'http://127.0.0.1:' + $AcceptPart.port
    $AcceptPart.extraPorts = @()
    if ($AcceptComponent -like 'mysql*') { $AcceptPart.clientExecutable = Join-Path $AcceptContext.Options.MySqlBin 'mysql.exe' }
    $AcceptContext.Manifest[$AcceptComponent] = $AcceptPart
    [void][IO.Directory]::CreateDirectory($AcceptPart.dataDirectory)
    if ($AcceptComponent -like 'mysql*') {
        [IO.File]::WriteAllText((Join-Path $AcceptPart.dataDirectory 'auto.cnf'), ('server-uuid=' + $AcceptPart.serverUuid))
        [IO.File]::WriteAllText($AcceptPart.credentialFile, 'fictional offline client')
    }
}
foreach ($AcceptDirectory in @($AcceptRuntime, $AcceptEvidence)) { Set-OnboardingPrivateAcl $AcceptDirectory }
foreach ($AcceptRelative in @('paas-base/log', 'portal-base/logs', 'portal-base/work', 'portal-base/temp', 'paas-base/tmp')) { [void][IO.Directory]::CreateDirectory((Join-Path $AcceptRuntime $AcceptRelative)) }
foreach ($AcceptFile in @(Get-ChildItem -LiteralPath $AcceptRuntime -File)) { Set-OnboardingPrivateAcl $AcceptFile.FullName }
function Write-AcceptFixture {
    [IO.File]::WriteAllText((Join-Path $AcceptRuntime 'owner.id'), $AcceptOwner)
    [IO.File]::WriteAllText((Join-Path $AcceptRuntime 'journal.json'), ($AcceptContext.Journal | ConvertTo-Json -Depth 16))
    [IO.File]::WriteAllText((Join-Path $AcceptRuntime 'fixture.json'), ($AcceptContext.Manifest | ConvertTo-Json -Depth 16))
    foreach ($AcceptFile in @(Get-ChildItem -LiteralPath $AcceptRuntime -File)) { Set-OnboardingPrivateAcl $AcceptFile.FullName }
}
$script:AcceptSqlCalls = New-Object 'Collections.Generic.List[string]'
$script:AcceptMavenResult = $null
$script:AcceptHttpTokens = @{}
$script:AcceptHttpKeys = @{}
$script:AcceptDisabled = @{}
$script:AcceptAddAdminId = '40000000-0000-0000-0000-000000000002'
$script:AcceptIdentityThrows = $false
$script:AcceptMysqlLiveThrows = $false
$script:AcceptIntentBeforeSql = $false

function Reset-AcceptState {
    $script:verifyGates = [ordered]@{}
    foreach ($AcceptName in $verifyRequired) { $script:verifyGates[$AcceptName] = 'unverified' }
    $script:verifySecrets = New-Object 'Collections.Generic.List[string]'
    $script:verifyCredentialSecrets = New-Object 'Collections.Generic.List[string]'
    $script:verifyFacts = [ordered]@{executedCases = 0; observedLocks = 0; apiRequests = 0; secretViolations = 0}
    $script:verifyCompleteDriverRun = $false
    $script:verifySummaryWritten = $false
    $script:verifyContext = $null
    $script:verifyExitCode = 0
    $script:verifyConsumptionPath = $null
    $script:verifyIdentityLost = $false
    $script:verifyClaimOwned = $false
    $script:verifySourceRoot = $null
    $script:verifyFailureCode = $null
    $script:verifyEvidenceRoot = $AcceptEvidence
    $script:verifyTokens = @{}
    $script:verifyAccounts = @()
    $script:AcceptSqlCalls = New-Object 'Collections.Generic.List[string]'
    $script:AcceptMavenResult = $null
    $script:AcceptHttpTokens = @{}
    $script:AcceptHttpKeys = @{}
    $script:AcceptDisabled = @{}
    $script:AcceptIdentityThrows = $false
    $script:AcceptMysqlLiveThrows = $false
    $script:AcceptIntentBeforeSql = $false
    $script:AcceptSqlText = New-Object 'Collections.Generic.List[string]'
    $script:AcceptHttpCalls = New-Object 'Collections.Generic.List[string]'
    $script:AcceptLoseAfterMaven = $false
    $script:AcceptLeakPassword = $false
    $script:AcceptLeakHash = $false
    $script:AcceptSqlLeak = $false
    $script:AcceptTokenVariant = $null
    $script:AcceptSchemaExists = $false
    $script:AcceptSourceMismatch = $false
    $script:AcceptFinalizeLock = $null
    $script:AcceptLoseBeforeFinalize = $false
    foreach ($AcceptLog in @(Get-ChildItem -LiteralPath (Join-Path $AcceptRuntime 'paas-base/log') -File)) { Remove-Item -LiteralPath $AcceptLog.FullName -Force }
    foreach ($AcceptBinary in @('portal-base/work/Offline.class', 'portal-base/temp/offline.session')) {
        $AcceptBinaryPath = Join-Path $AcceptRuntime $AcceptBinary
        if (Test-Path -LiteralPath $AcceptBinaryPath) { Remove-Item -LiteralPath $AcceptBinaryPath -Force }
    }
    foreach ($AcceptFile in @(Get-ChildItem -LiteralPath $AcceptRuntime -Filter '*consumption.json' -File)) { Remove-Item -LiteralPath $AcceptFile.FullName -Force }
    Write-AcceptFixture
    foreach ($AcceptFile in @(Get-ChildItem -LiteralPath $AcceptEvidence -File -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $AcceptFile.FullName -Force }
}

# Only external process/listener/native/HTTP boundaries are doubled. Fixture,
# owner, ACL, path, claim, manifest and persistence logic remain production code.
function Get-OnboardingProcess([int]$AcceptPid) {
    if ($script:AcceptIdentityThrows) { return @() }
    $AcceptPart = @($AcceptContext.Journal.components.Values | Where-Object { $_.pid -eq $AcceptPid })[0]
    return [pscustomobject]@{ProcessId = $AcceptPid; CreationDate = $AcceptPart.created; ExecutablePath = $AcceptPart.executable; CommandLine = ($AcceptPart.dataDirectory + ' ' + ($AcceptPart.arguments -join ' '))}
}
function Get-OnboardingListeners([int]$AcceptPort) {
    $AcceptPart = @($AcceptContext.Journal.components.Values | Where-Object { $_.port -eq $AcceptPort })[0]
    return [pscustomobject]@{OwningProcess = $AcceptPart.pid; LocalAddress = '127.0.0.1'}
}
function Invoke-OnboardingNative($AcceptExecutable, $AcceptArguments, $AcceptDirectory, $AcceptInput, $AcceptLabel) {
    if ($AcceptLabel -like '*-identity') {
        if ($AcceptLabel -eq 'verify-source-identity') { return @{ExitCode = 0; Output = $(if ($script:AcceptSourceMismatch) { 'b' * 40 } else { 'a' * 40 }); Error = ''} }
        $AcceptPart = $AcceptContext.Journal.components[($AcceptLabel -replace '-identity$', '')]
        if (-not $AcceptPart) { throw 'UNEXPECTED_NATIVE_IDENTITY' }
        $AcceptHex = [BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($AcceptPart.dataDirectory)).Replace('-', '')
        return @{ExitCode = 0; Output = ($AcceptPart.serverUuid + "`t" + $AcceptPart.port + "`t" + $AcceptHex + "`tWin64"); Error = ''}
    }
    if ($AcceptLabel -eq 'verify-source-export') {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $AcceptArchive = ($AcceptArguments | Where-Object { $_ -like '--output=*' }) -replace '^--output=', ''
        $AcceptZip = [IO.Compression.ZipFile]::Open($AcceptArchive, 'Create')
        try {
            foreach ($AcceptEntryName in @('course/pom.xml', 'course/deploy/schema.sql')) {
                $AcceptZipEntry = $AcceptZip.CreateEntry($AcceptEntryName)
                $AcceptWriter = New-Object IO.StreamWriter($AcceptZipEntry.Open())
                try { $AcceptWriter.Write($(if ($AcceptEntryName -like '*schema.sql') { "CREATE DATABASE IF NOT EXISTS medical_cloud_course;`nUSE medical_cloud_course;`nCREATE TABLE fictional(id INT);" } else { '<project/>' })) } finally { $AcceptWriter.Dispose() }
            }
        } finally { $AcceptZip.Dispose() }
        return @{ExitCode = 0; Output = ''; Error = ''}
    }
    if ($AcceptLabel -eq 'verify-java-version') { return @{ExitCode = 0; Output = 'javac 1.8.0_fictional'; Error = ''} }
    if ($AcceptLabel -eq 'verify-maven') {
        $script:AcceptMavenEnvironment = @{}
        foreach ($AcceptEnvName in @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_ARGS', 'MAVEN_OPTS', 'MYSQL_PWD', 'TEMP', 'TMP')) { $script:AcceptMavenEnvironment[$AcceptEnvName] = [Environment]::GetEnvironmentVariable($AcceptEnvName) }
        $script:AcceptMavenArguments = $AcceptArguments
        if ($script:AcceptLoseAfterMaven) { $script:AcceptIdentityThrows = $true }
        return @{ExitCode = $script:AcceptMavenResult.ExitCode; Output = $script:AcceptMavenResult.Output; Error = ''}
    }
    if ($AcceptLabel -like 'verify-*') {
        if ($script:AcceptSqlCalls.Count -eq 0) { $script:AcceptIntentBeforeSql = Test-Path -LiteralPath (Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json')) }
        $script:AcceptSqlCalls.Add($AcceptLabel)
        $script:AcceptSqlText.Add([string]$AcceptInput)
        if ($AcceptLabel -eq 'verify-schema-create' -and $script:AcceptSqlLeak) { return @{ExitCode = 0; Output = 'db-secret'; Error = ''} }
        if ($AcceptLabel -eq 'verify-health-ping' -and $script:AcceptLoseBeforeFinalize) { $script:AcceptFinalizeLock = [IO.File]::Open((Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json')), 'Open', 'Read', 'None') }
        return @{ExitCode = 0; Output = $(if ($AcceptLabel -eq 'verify-schema-absent' -and $script:AcceptSchemaExists) { '1' } elseif ($AcceptLabel -eq 'verify-schema-absent') { '0' } else { '' }); Error = ''}
    }
    throw ('UNEXPECTED_NATIVE_BOUNDARY_' + $AcceptLabel)
}
function New-AcceptMavenPassOutput {
    $AcceptLines = New-Object 'Collections.Generic.List[string]'
    foreach ($AcceptCase in @(
        'schemaJpaValidate',
        'sameKeyBothDirections:open-first', 'sameKeyBothDirections:add-first',
        'caseInsensitiveGlobalUsernameRace:PATIENT:onboarding-first', 'caseInsensitiveGlobalUsernameRace:PATIENT:other-first',
        'caseInsensitiveGlobalUsernameRace:DOCTOR:onboarding-first', 'caseInsensitiveGlobalUsernameRace:DOCTOR:other-first',
        'disableLastTwoAdminsBothDirections:original-first', 'disableLastTwoAdminsBothDirections:extra-first',
        'disableVsEachHospitalWriterBothDirections:createDepartment:disable-first', 'disableVsEachHospitalWriterBothDirections:createDepartment:writer-first',
        'disableVsEachHospitalWriterBothDirections:updateDepartment:disable-first', 'disableVsEachHospitalWriterBothDirections:updateDepartment:writer-first',
        'disableVsEachHospitalWriterBothDirections:createDoctor:disable-first', 'disableVsEachHospitalWriterBothDirections:createDoctor:writer-first',
        'disableVsEachHospitalWriterBothDirections:updateDoctor:disable-first', 'disableVsEachHospitalWriterBothDirections:updateDoctor:writer-first',
        'disableVsEachHospitalWriterBothDirections:createSlot:disable-first', 'disableVsEachHospitalWriterBothDirections:createSlot:writer-first',
        'disableVsEachHospitalWriterBothDirections:updateSlot:disable-first', 'disableVsEachHospitalWriterBothDirections:updateSlot:writer-first',
        'freezeVsAdminMaintenance:add:freeze-first', 'freezeVsAdminMaintenance:add:maintenance-first',
        'freezeVsAdminMaintenance:enable:freeze-first', 'freezeVsAdminMaintenance:enable:maintenance-first',
        'freezeVsAdminMaintenance:disable:freeze-first', 'freezeVsAdminMaintenance:disable:maintenance-first',
        'auditFailureRollsBackBusiness:open', 'auditFailureRollsBackBusiness:add', 'auditFailureRollsBackBusiness:status',
        'replayFrozenAndDisabled', 'zeroActiveRecovery')) {
        $AcceptLines.Add('ONBOARDING_REQUIRED_CASE=' + $AcceptCase)
        $AcceptLines.Add('ONBOARDING_PASSED_CASE=' + $AcceptCase)
        if ($AcceptCase -match '^(sameKey|caseInsensitive|disable|freeze)') { $AcceptLines.Add('ONBOARDING_OBSERVED_LOCK=' + $AcceptCase + ':innodb-wait') }
    }
    $AcceptLines.Add('Tests run: 32, Failures: 0, Errors: 0, Skipped: 0')
    return $AcceptLines -join "`n"
}
function Invoke-VerifyTransport([string]$AcceptUri, [string]$AcceptMethod = 'GET', [string]$AcceptToken = $null, [string]$AcceptJson = $null) {
    $script:AcceptHttpCalls.Add($AcceptMethod + ' ' + $AcceptUri)
    $AcceptAddress = [uri]$AcceptUri
    $AcceptPath = $AcceptAddress.AbsolutePath -replace '^/paas/api/', ''
    $AcceptBody = $null
    if ($AcceptJson) { $AcceptBody = $AcceptJson | ConvertFrom-Json }
    if ($AcceptPath -eq 'auth/login') {
        $AcceptRole = switch ($AcceptBody.username) {
            'platform_admin' { 'PLATFORM_ADMIN' }
            'platform_second' { 'PLATFORM_ADMIN' }
            default { 'HOSPITAL_ADMIN' }
        }
        $AcceptTokenValue = 'tok-' + $AcceptBody.username
        switch ($script:AcceptTokenVariant) {
            'password' { $AcceptTokenValue = $AcceptBody.password }
            'escaped-password' { $AcceptTokenValue = $AcceptBody.password }
            'other-credential' { $AcceptTokenValue = 'db-secret' }
            'hash' { $AcceptTokenValue = '$2b$12$' + ('a' * 53) }
        }
        $script:AcceptHttpTokens[$AcceptTokenValue] = $AcceptBody.username
        $AcceptLoginText = @{code = 'OK'; message = 'logged in'; data = @{accessToken = $AcceptTokenValue; username = $AcceptBody.username; role = $AcceptRole}} | ConvertTo-Json -Depth 4
        if ($script:AcceptTokenVariant -ceq 'escaped-password') { $AcceptLoginText = $AcceptLoginText.Replace('platform-secret-1', '\u0070latform-secret-1') }
        return @{Status = 200; Text = $AcceptLoginText}
    }
    $AcceptUser = $script:AcceptHttpTokens[$AcceptToken]
    if ($AcceptToken -and $script:AcceptDisabled.ContainsKey($AcceptUser)) {
        return @{Status = 401; Text = (@{code = 'UNAUTHORIZED'; message = 'account disabled'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
    }
    if (-not $AcceptUser -and $AcceptToken) {
        return @{Status = 401; Text = (@{code = 'UNAUTHORIZED'; message = 'token rejected'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
    }
    if ($AcceptPath -eq 'platform/tenants' -and $AcceptMethod -eq 'POST') {
        if ($AcceptUser -cne 'platform_admin' -and $AcceptUser -cne 'platform_second') {
            return @{Status = 403; Text = (@{code = 'FORBIDDEN'; message = 'platform only'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
        }
        $AcceptKey = $AcceptBody.operationKey
        $AcceptHash = $AcceptBody.hospitalName + '|' + $AcceptBody.username + '|' + $AcceptBody.initialPassword
        if ($script:AcceptHttpKeys.ContainsKey($AcceptKey)) {
            if ($script:AcceptHttpKeys[$AcceptKey].hash -ceq $AcceptHash) {
                $AcceptTenant = $script:AcceptHttpKeys[$AcceptKey].tenantId; $AcceptAdmin = $script:AcceptHttpKeys[$AcceptKey].adminId
                return @{Status = 200; Text = (@{code = 'OK'; message = 'hospital opened'; data = @{tenantId = $AcceptTenant; administratorId = $AcceptAdmin; hospitalName = $AcceptBody.hospitalName; username = $AcceptBody.username}} | ConvertTo-Json -Depth 4)}
            }
            return @{Status = 409; Text = (@{code = 'OPERATION_KEY_CONFLICT'; message = 'operation key already used'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
        }
        $AcceptTenant = [guid]::NewGuid().ToString(); $AcceptAdmin = [guid]::NewGuid().ToString()
        $script:AcceptHttpKeys[$AcceptKey] = @{hash = $AcceptHash; tenantId = $AcceptTenant; adminId = $AcceptAdmin}
        $AcceptData = @{tenantId = $AcceptTenant; administratorId = $AcceptAdmin; hospitalName = $AcceptBody.hospitalName; username = $AcceptBody.username}
        if ($script:AcceptLeakPassword) { $AcceptData.initialPassword = $AcceptBody.initialPassword }
        if ($script:AcceptLeakHash) { $AcceptData.hospitalName = '$2b$12$' + ('a' * 53) }
        $AcceptResponseText = @{code = 'OK'; message = 'hospital opened'; data = $AcceptData} | ConvertTo-Json -Depth 4
        if ($script:AcceptLeakPassword -ceq 'escaped') { $AcceptResponseText = $AcceptResponseText.Replace('Vfy-1234-Admin', '\u0056fy-1234-Admin') }
        return @{Status = 200; Text = $AcceptResponseText}
    }
    if ($AcceptPath -match '^platform/tenants/[0-9a-f-]{36}/administrators$' -and $AcceptMethod -eq 'GET') {
        return @{Status = 200; Text = (@{code = 'OK'; message = 'hospital administrators'; data = @{total = 1; page = 1; size = 20; activeAdministratorCount = 1; items = @(@{id = [guid]::NewGuid().ToString(); tenantId = '20000000-0000-0000-0000-000000000001'; username = 'verify_admin'; status = 'ACTIVE'})}} | ConvertTo-Json -Depth 5)}
    }
    if ($AcceptPath -match '^platform/tenants/[0-9a-f-]{36}/administrators$' -and $AcceptMethod -eq 'POST') {
        return @{Status = 200; Text = (@{code = 'OK'; message = 'hospital administrator created'; data = @{tenantId = '20000000-0000-0000-0000-000000000001'; administratorId = $script:AcceptAddAdminId; username = $AcceptBody.username}} | ConvertTo-Json -Depth 4)}
    }
    if ($AcceptPath -match '^platform/tenants/[0-9a-f-]{36}/administrators/[0-9a-f-]{36}/status$' -and $AcceptMethod -eq 'PUT') {
        if ($AcceptPath -match ([regex]::Escape($script:AcceptAddAdminId) + '/status$')) {
            $script:AcceptDisabled['verify_second'] = $true
            return @{Status = 200; Text = (@{code = 'OK'; message = 'hospital administrator status'; data = @{id = $script:AcceptAddAdminId; tenantId = '20000000-0000-0000-0000-000000000001'; username = 'verify_second'; status = 'DISABLED'}} | ConvertTo-Json -Depth 4)}
        }
        return @{Status = 409; Text = (@{code = 'LAST_ACTIVE_HOSPITAL_ADMIN'; message = 'cannot disable last active administrator'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
    }
    return @{Status = 404; Text = (@{code = 'NOT_FOUND'; message = 'unknown route'; requestId = ([guid]::NewGuid().ToString())} | ConvertTo-Json)}
}

# Exercise the real driver with only the external boundary doubles above.
try {
    # 1. Default entry (no -Execute) exits 4 and lists unverified gates.
    Test-AcceptBehavior 'default entry exits 4 with unverified gates' {
        $AcceptBefore = @(Get-ChildItem -LiteralPath $AcceptEvidence -File -ErrorAction SilentlyContinue).Count
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $AcceptEntry 2>$null | Out-Null
        Assert-AcceptTest ($LASTEXITCODE -eq 4) ('Expected native exit 4, actual ' + $LASTEXITCODE)
        Assert-AcceptTest (@(Get-ChildItem -LiteralPath $AcceptEvidence -File -ErrorAction SilentlyContinue).Count -eq $AcceptBefore) 'Default run created evidence'
    }
    # 2. Exact parameter import is preserved when dot-sourced (captured before the
    #    fixture module's parameter block shadows the public names in this scope).
    Test-AcceptBehavior 'dot source preserves exact parameters' {
        Assert-AcceptTest ($FixturePath -ceq (Join-Path $AcceptRuntime 'fixture.json')) 'FixturePath clobbered'
        Assert-AcceptTest ($EvidenceRoot -ceq $AcceptEvidence) 'EvidenceRoot clobbered'
        Assert-AcceptTest ($verifyNewRunId -ceq $AcceptOwner) 'NewRunId clobbered'
        Assert-AcceptTest ([bool]$verifyExecuteRequested -eq $true) 'Execute clobbered'
        Assert-AcceptTest ($verifyFixturePath -ceq (Join-Path $AcceptRuntime 'fixture.json')) 'verifyFixturePath clobbered'
        Assert-AcceptTest ($verifyEvidenceRoot -ceq $AcceptEvidence) 'verifyEvidenceRoot clobbered'
    }
    # 3. Wrong owner identity refuses the run.
    Test-AcceptBehavior 'wrong owner identity refuses' {
        Reset-AcceptState
        [IO.File]::WriteAllText((Join-Path $AcceptRuntime 'owner.id'), ('b' * 32))
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Wrong owner was accepted'
        Assert-AcceptTest ($script:verifyGates['fixture-identity-and-ownership'] -eq 'failed') 'Identity gate not failed'
    }
    # 4. Duplicate consumption (CreateNew collision) refuses.
    Test-AcceptBehavior 'duplicate consumption refuses with CreateNew' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        $AcceptConsumption = Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json')
        [IO.File]::WriteAllText($AcceptConsumption, '{"state":"verified"}')
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -eq 1) 'Duplicate consumption was accepted'
        Assert-AcceptTest ($script:verifyGates['consumption-create-new'] -eq 'failed') 'Consumption gate not failed'
        Assert-AcceptTest ([IO.File]::ReadAllText($AcceptConsumption) -ceq '{"state":"verified"}') 'Pre-existing consumption was overwritten'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 5. A short (skip) 32-case manifest refuses pass.
    Test-AcceptBehavior 'short 32-case manifest refuses pass' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = 'ONBOARDING_REQUIRED_CASE=schemaJpaValidate' + "`n" + 'ONBOARDING_PASSED_CASE=schemaJpaValidate' + "`n" + 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 31'}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Short manifest accepted'
        Assert-AcceptTest ($script:verifyGates['mysql-32-case-manifest'] -eq 'failed') 'MySQL gate not failed'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 6. Intent-before-write: the consumption record is created before any SQL.
    Test-AcceptBehavior 'consumption intent precedes any SQL' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -eq 4) 'Missing mandatory JTA observations did not remain incomplete'
        Assert-AcceptTest ($script:AcceptSqlCalls.Count -gt 0) 'No SQL was issued'
        Assert-AcceptTest $script:AcceptIntentBeforeSql 'Consumption record was not written before SQL'
        $AcceptConsumption = Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json')
        Assert-AcceptTest (Test-Path -LiteralPath $AcceptConsumption) 'Consumption record missing'
        Assert-AcceptTest ([IO.File]::ReadAllText($AcceptConsumption) -match '"state":\s*"incomplete"') 'Consumption record did not retain incomplete status'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 7. Process identity lost after Maven blocks every subsequent HTTP request.
    Test-AcceptBehavior 'identity change after Maven blocks all HTTP and preserves intent' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $script:AcceptLoseAfterMaven = $true
        $AcceptBeforeTemp = $env:TEMP
        $AcceptBeforeJava = $env:JAVA_HOME
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Identity change was accepted'
        Assert-AcceptTest ($script:AcceptHttpCalls.Count -eq 0) 'HTTP occurred after identity loss'
        Assert-AcceptTest ($env:TEMP -ceq $AcceptBeforeTemp -and $env:JAVA_HOME -ceq $AcceptBeforeJava) 'Maven failure did not restore environment'
        Assert-AcceptTest ([IO.File]::ReadAllText((Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json'))) -match '"state":\s*"intent"') 'Identity loss rewrote intent'
        $script:AcceptIdentityThrows = $false
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 8. Safe error: a secret leaked into Maven output is refused.
    Test-AcceptBehavior 'secret in Maven output is refused' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = ((New-AcceptMavenPassOutput) + "`n" + 'db-secret')}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Secret leak was accepted'
        Assert-AcceptTest ($script:verifyGates['mysql-32-case-manifest'] -eq 'failed') 'MySQL gate did not catch secret'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 9. Ordinary observations leave all advanced mandatory gates unverified.
    Test-AcceptBehavior 'ordinary observations cannot claim full Task7 acceptance' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        Write-AcceptFixture
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -eq 4) ('Expected incomplete exit 4, got ' + $script:verifyExitCode)
        Assert-AcceptTest ($script:verifySummaryWritten) 'Summary not written'
        $AcceptSummary = Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-summary.json')
        Assert-AcceptTest (Test-Path -LiteralPath $AcceptSummary) 'Summary file missing'
        $AcceptSummaryJson = [IO.File]::ReadAllText($AcceptSummary) | ConvertFrom-Json
        Assert-AcceptTest ($AcceptSummaryJson.accepted -eq $false -and $AcceptSummaryJson.completeDriverRun -eq $false) 'Incomplete observations claimed acceptance'
        foreach ($AcceptGate in @('jta-success-audit-fault-rollback', 'application-12-table-null-hex-rollback', 'jta-independent-failed-audit', 'jta-lock-release', 'exact-trigger-recovery', 'migration-refusal-cases', 'four-api-full-role-matrix')) { Assert-AcceptTest ($AcceptGate -cin $AcceptSummaryJson.unverified) ('Missing mandatory unverified gate ' + $AcceptGate) }
        Assert-AcceptTest ($AcceptSummaryJson.counts.executedCases -eq 32 -and $AcceptSummaryJson.counts.observedLocks -eq 26) 'Summary counts wrong'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 10. Strict summary: an incomplete run must never be reported accepted.
    Test-AcceptBehavior 'strict summary refuses an incomplete run' {
        Reset-AcceptState
        $script:verifyContext = $AcceptContext
        $script:verifyGates['fixture-identity-and-ownership'] = 'passed'
        $script:verifyGates['consumption-create-new'] = 'passed'
        # Remaining gates stay unverified; the summary must report accepted=false.
        Write-VerifySummary
        $AcceptSummary = Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-summary.json')
        Assert-AcceptTest (Test-Path -LiteralPath $AcceptSummary) 'Summary file missing'
        $AcceptSummaryJson = [IO.File]::ReadAllText($AcceptSummary) | ConvertFrom-Json
        Assert-AcceptTest ($AcceptSummaryJson.accepted -eq $false) 'Incomplete run reported accepted'
        Assert-AcceptTest ($AcceptSummaryJson.counts.unverified -gt 0) 'Unverified gates not listed'
        Assert-AcceptTest ($AcceptSummaryJson.scope -ceq 'hospital-onboarding') 'Summary scope wrong'
        Remove-Item -LiteralPath $AcceptSummary -Force
    }
    Test-AcceptBehavior 'changing evidence directory cannot consume owner twice' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance | Out-Null
        $AcceptBefore = $script:AcceptSqlCalls.Count
        $AcceptConsumption = Join-Path $AcceptRuntime ($AcceptOwner + '-hospital-onboarding-consumption.json')
        $AcceptOriginal = [IO.File]::ReadAllText($AcceptConsumption)
        $script:verifyEvidenceRoot = Join-Path $AcceptEvidence 'another-output'
        [void][IO.Directory]::CreateDirectory($script:verifyEvidenceRoot)
        Set-OnboardingPrivateAcl $script:verifyEvidenceRoot
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:AcceptSqlCalls.Count -eq $AcceptBefore) 'Repeated owner reached SQL with alternate evidence root'
        Assert-AcceptTest ([IO.File]::ReadAllText($AcceptConsumption) -ceq $AcceptOriginal) 'Previous claim overwritten'
    }
    Test-AcceptBehavior 'schema DDL is bound and never drops an existing database' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance | Out-Null
        $AcceptDdl = @($script:AcceptSqlText | Where-Object { $_ -match 'CREATE TABLE' })
        Assert-AcceptTest ($AcceptDdl.Count -eq 1 -and $AcceptDdl[0] -match ('(?m)^USE course_onboarding_test_' + $AcceptOwner + ';')) 'Unbound table DDL emitted'
        Assert-AcceptTest (@($script:AcceptSqlText | Where-Object { $_ -match '(?i)DROP\s+DATABASE' }).Count -eq 0) 'Driver issued DROP DATABASE'
    }
    Test-AcceptBehavior 'existing schema refuses before CREATE or any destructive SQL' {
        Reset-AcceptState
        $script:AcceptSchemaExists = $true
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Existing schema accepted'
        Assert-AcceptTest (@($script:AcceptSqlText | Where-Object { $_ -match '(?i)CREATE|DROP|GRANT' }).Count -eq 0) 'Existing schema mutated'
    }
    foreach ($AcceptVariant in @('duplicate-case', 'extra-case', 'wrong-wait', 'duplicate-wait', 'failed-total')) {
        Test-AcceptBehavior ('manifest rejects ' + $AcceptVariant) {
            Reset-AcceptState
            $AcceptOutput = New-AcceptMavenPassOutput
            switch ($AcceptVariant) {
                'duplicate-case' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_PASSED_CASE=zeroActiveRecovery', 'ONBOARDING_PASSED_CASE=schemaJpaValidate') }
                'extra-case' { $AcceptOutput += "`nONBOARDING_PASSED_CASE=notMandatory" }
                'wrong-wait' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_OBSERVED_LOCK=sameKeyBothDirections:open-first:', 'ONBOARDING_OBSERVED_LOCK=notMandatory:') }
                'duplicate-wait' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_OBSERVED_LOCK=sameKeyBothDirections:add-first:', 'ONBOARDING_OBSERVED_LOCK=sameKeyBothDirections:open-first:') }
                'failed-total' { $AcceptOutput = $AcceptOutput.Replace('Failures: 0', 'Failures: 1') }
            }
            $script:AcceptMavenResult = @{ExitCode = 0; Output = $AcceptOutput}
            Invoke-VerifyAcceptance | Out-Null
            Assert-AcceptTest ($script:verifyGates['mysql-32-case-manifest'] -eq 'failed') 'Malformed named observations passed'
            Assert-AcceptTest ($script:AcceptHttpCalls.Count -eq 0) 'API proceeded after malformed observations'
        }
    }
    Test-AcceptBehavior 'source mismatch refuses before SQL and Maven uses isolated pinned export' {
        Reset-AcceptState
        $script:AcceptSourceMismatch = $true
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:AcceptSqlCalls.Count -eq 0 -and $script:verifyExitCode -ne 0) 'Source mismatch reached SQL'
    }
    Test-AcceptBehavior 'new API password echo stops immediately' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $script:AcceptLeakPassword = $true
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyFacts.secretViolations -eq 1) 'New initial password was not registered before request'
        Assert-AcceptTest ($script:AcceptHttpCalls.Count -eq 6) 'Requests continued after leaked response'
    }
    Test-AcceptBehavior 'Maven environment pins Java8 temp and restores inherited flags' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptSavedOption = $env:JAVA_TOOL_OPTIONS
        $env:JAVA_TOOL_OPTIONS = 'fictional-inherited-option'
        try {
            Invoke-VerifyAcceptance | Out-Null
            Assert-AcceptTest ($script:AcceptMavenEnvironment.JAVA_HOME -ceq $AcceptContext.Options.JavaHome) 'Java home not pinned'
            Assert-AcceptTest (-not $script:AcceptMavenEnvironment.JAVA_TOOL_OPTIONS) 'Inherited JVM flag reached Maven'
            Assert-AcceptTest ($script:AcceptMavenEnvironment.TEMP.StartsWith((Join-Path $AcceptProject '.local\builds\'))) 'Maven temp not isolated'
            Assert-AcceptTest (@($script:AcceptMavenArguments | Where-Object { $_ -ceq ('-Djava.io.tmpdir=' + $script:AcceptMavenEnvironment.TEMP) }).Count -eq 1) 'Forked tests did not receive explicit project-local Java temp'
            Assert-AcceptTest (@($script:AcceptMavenArguments | Where-Object { $_ -ceq ('-Dcourse.test.wildfly.home=' + $AcceptContext.Options.WildFlyHome) }).Count -eq 1) 'WildFly property split across native arguments'
            Assert-AcceptTest ($env:JAVA_TOOL_OPTIONS -ceq 'fictional-inherited-option') 'Inherited flag not restored'
            $AcceptPom = $script:AcceptMavenArguments[([array]::IndexOf($script:AcceptMavenArguments, '-f') + 1)]
            Assert-AcceptTest ($AcceptPom.StartsWith((Join-Path $AcceptProject '.local\builds\')) -and $AcceptPom -cne (Join-Path $AcceptSource 'course/pom.xml')) 'Mutable source passed to Maven'
        } finally { $env:JAVA_TOOL_OPTIONS = $AcceptSavedOption }
    }
    Test-AcceptBehavior 'required application log leak fails the safety gate' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptLog = Join-Path $AcceptRuntime 'paas-base/log/server.log'
        [IO.File]::WriteAllText($AcceptLog, 'jwt-fixture-secret')
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyGates['safe-error-no-secret-leak'] -ceq 'failed' -and $script:verifyFacts.secretViolations -eq 1) 'Application log secret escaped detection'
    }
    Test-AcceptBehavior 'failed consumption finalization forbids accepted summary' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $script:AcceptLoseBeforeFinalize = $true
        try {
            Invoke-VerifyAcceptance | Out-Null
            Assert-AcceptTest ($script:verifyGates['consumption-finalization'] -ceq 'failed') 'Failed finalization was swallowed'
            Assert-AcceptTest ($script:verifySummaryWritten -and $script:verifyExitCode -ne 0) 'Failed finalization has no safe nonzero summary'
            $AcceptSummary = [IO.File]::ReadAllText((Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-summary.json'))) | ConvertFrom-Json
            Assert-AcceptTest ($AcceptSummary.accepted -eq $false -and $AcceptSummary.completeDriverRun -eq $false) 'Unknown persistence claimed accepted'
            Assert-AcceptTest ($AcceptSummary.facts.consumptionFinalized -eq $false) 'Failed finalization must explicitly report persistence=false'
        } finally { if ($script:AcceptFinalizeLock) { $script:AcceptFinalizeLock.Dispose(); $script:AcceptFinalizeLock = $null } }
    }
    Test-AcceptBehavior 'successful response cannot hide a password hash in an allowed field' {
        Reset-AcceptState
        $script:verifyContext = Read-OnboardingFixtureContext (Join-Path $AcceptRuntime 'fixture.json') $AcceptOwner
        $script:verifyTokens = @{platform_admin = 'offline-token'}
        $script:AcceptHttpTokens['offline-token'] = 'platform_admin'
        $script:AcceptLeakHash = $true
        Assert-AcceptTestThrows { Invoke-VerifyApi 'POST' 'platform/tenants' 'platform_admin' @{hospitalName = 'Fictional'; username = 'verify_admin'; initialPassword = 'Vfy-1234-Admin'; operationKey = [guid]::NewGuid().ToString()} } '*REDACTION_FAILED*'
        Assert-AcceptTest ($script:AcceptHttpCalls.Count -eq 1) 'Hash test did not reach response boundary'
    }
    Test-AcceptBehavior 'native SQL output cannot echo a secret to console' {
        Reset-AcceptState
        $script:AcceptSqlLeak = $true
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptConsole = Invoke-VerifyAcceptance | Out-String
        Assert-AcceptTest (-not $AcceptConsole.Contains('db-secret')) 'Native SQL echoed secret to console'
        Assert-AcceptTest ($script:AcceptSqlCalls.Count -eq 2) 'SQL continued after secret output'
    }
    Test-AcceptBehavior 'escaped successful response password is detected after decoding' {
        Reset-AcceptState
        $script:verifyContext = Read-OnboardingFixtureContext (Join-Path $AcceptRuntime 'fixture.json') $AcceptOwner
        $script:verifyTokens = @{platform_admin = 'offline-token'}
        $script:AcceptHttpTokens['offline-token'] = 'platform_admin'
        $script:AcceptLeakPassword = 'escaped'
        Assert-AcceptTestThrows { Invoke-VerifyApi 'POST' 'platform/tenants' 'platform_admin' @{hospitalName = 'Fictional'; username = 'verify_admin'; initialPassword = 'Vfy-1234-Admin'; operationKey = [guid]::NewGuid().ToString()} } '*REDACTION_FAILED*'
        Assert-AcceptTest ($script:verifyFacts.secretViolations -eq 1) 'Escaped secret was refused for unrelated reason'
    }
    foreach ($AcceptExactVariant in @('duplicate-required', 'extra-required', 'missing-required', 'case-mismatch', 'extra-wait', 'invalid-wait-suffix', 'missing-total', 'error-total', 'skip-total')) {
        Test-AcceptBehavior ('exact manifest rejects ' + $AcceptExactVariant) {
            $AcceptOutput = New-AcceptMavenPassOutput
            switch ($AcceptExactVariant) {
                'duplicate-required' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_REQUIRED_CASE=zeroActiveRecovery', 'ONBOARDING_REQUIRED_CASE=schemaJpaValidate') }
                'extra-required' { $AcceptOutput += "`nONBOARDING_REQUIRED_CASE=extra" }
                'missing-required' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_REQUIRED_CASE=zeroActiveRecovery', '') }
                'case-mismatch' { $AcceptOutput = $AcceptOutput.Replace('ONBOARDING_REQUIRED_CASE=schemaJpaValidate', 'ONBOARDING_REQUIRED_CASE=SchemaJpaValidate') }
                'extra-wait' { $AcceptOutput += "`nONBOARDING_OBSERVED_LOCK=extra:innodb-wait" }
                'invalid-wait-suffix' { $AcceptOutput = $AcceptOutput.Replace(':innodb-wait', ':sleep-only') }
                'missing-total' { $AcceptOutput = $AcceptOutput.Replace('Tests run: 32, Failures: 0, Errors: 0, Skipped: 0', '') }
                'error-total' { $AcceptOutput = $AcceptOutput.Replace('Errors: 0', 'Errors: 1') }
                'skip-total' { $AcceptOutput = $AcceptOutput.Replace('Skipped: 0', 'Skipped: 1') }
            }
            Assert-AcceptTestThrows { Assert-VerifyManifest $AcceptOutput } '*CHECK_FAILED*'
        }
    }
    Test-AcceptBehavior 'documented duplicate-key wait suffix retains the exact mandatory identity' {
        Assert-VerifyManifest ((New-AcceptMavenPassOutput).Replace(':innodb-wait', ':innodb-wait-duplicate-key'))
        Assert-AcceptTest ($script:verifyFacts.executedCases -eq 32 -and $script:verifyFacts.observedLocks -eq 26) 'Legitimate exact waits not counted'
    }
    Test-AcceptBehavior 'round2 benign compiled class is byte-safe and remains ordinary incomplete' {
        Reset-AcceptState
        [IO.File]::WriteAllBytes((Join-Path $AcceptRuntime 'portal-base/work/Offline.class'), [byte[]]@(0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 52))
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyGates['safe-error-no-secret-leak'] -ceq 'passed') 'Normal compiled class was decoded as text'
        Assert-AcceptTest ($script:verifyExitCode -eq 4 -and $script:verifyCompleteDriverRun -eq $false) 'Benign binary changed incomplete semantics'
    }
    foreach ($AcceptBinaryVariant in @('utf8-secret', 'utf16-secret', 'ascii-hash')) {
        Test-AcceptBehavior ('round2 binary session detects ' + $AcceptBinaryVariant) {
            Reset-AcceptState
            $AcceptPayload = switch ($AcceptBinaryVariant) {
                'utf8-secret' { [Text.Encoding]::UTF8.GetBytes('jwt-fixture-secret') }
                'utf16-secret' { [Text.Encoding]::Unicode.GetBytes('jwt-fixture-secret') }
                'ascii-hash' { [Text.Encoding]::ASCII.GetBytes(('$2b$12$' + ('a' * 53))) }
            }
            [IO.File]::WriteAllBytes((Join-Path $AcceptRuntime 'portal-base/temp/offline.session'), ([byte[]]@(0xAC, 0xED, 0, 5) + [byte[]]$AcceptPayload))
            $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
            Invoke-VerifyAcceptance | Out-Null
            Assert-AcceptTest ($script:verifyGates['safe-error-no-secret-leak'] -ceq 'failed' -and $script:verifyFacts.secretViolations -eq 1) 'Binary rejected for decoding rather than detecting disclosure'
        }
    }
    Test-AcceptBehavior 'round2 declared log retains strict UTF8 refusal' {
        Reset-AcceptState
        [IO.File]::WriteAllBytes((Join-Path $AcceptRuntime 'paas-base/log/server.log'), [byte[]]@(0xCA, 0xFE, 0xBA, 0xBE))
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyGates['safe-error-no-secret-leak'] -ceq 'failed' -and $script:verifyFacts.secretViolations -eq 0) 'Invalid declared-text log accepted or misclassified'
    }
    Test-AcceptBehavior 'round2 Maven INFO totals accept exact 32 cases and 26 waits' {
        Assert-VerifyManifest ((New-AcceptMavenPassOutput).Replace('Tests run:', '[INFO] Tests run:'))
        Assert-AcceptTest ($script:verifyFacts.executedCases -eq 32 -and $script:verifyFacts.observedLocks -eq 26) 'Prefixed evidence lost exact counts'
    }
    Test-AcceptBehavior 'round2 actual prefixed Maven output preserves ordinary incomplete orchestration' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = ((New-AcceptMavenPassOutput).Replace('Tests run:', '[INFO] Tests run:'))}
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyGates['mysql-32-case-manifest'] -ceq 'passed' -and $script:verifyExitCode -eq 4) 'Maven prefix broke ordinary orchestration'
    }
    Test-AcceptBehavior 'round2 known ANSI Maven INFO totals retain exact evidence' {
        $AcceptAnsiOutput = (New-AcceptMavenPassOutput).Replace('Tests run:', (([char]27) + '[1;34m[INFO]' + ([char]27) + '[m Tests run:'))
        Assert-VerifyManifest $AcceptAnsiOutput
    }
    foreach ($AcceptTotalVariant in @('failure', 'error', 'skip', 'unknown-prefix')) {
        Test-AcceptBehavior ('round2 prefixed totals refuse ' + $AcceptTotalVariant) {
            $AcceptOutput = New-AcceptMavenPassOutput
            $AcceptBadTotal = switch ($AcceptTotalVariant) {
                'failure' { '[ERROR] Tests run: 32, Failures: 1, Errors: 0, Skipped: 0' }
                'error' { '[INFO] Tests run: 32, Failures: 0, Errors: 1, Skipped: 0' }
                'skip' { '[WARNING] Tests run: 32, Failures: 0, Errors: 0, Skipped: 1' }
                'unknown-prefix' { '[NOTICE] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0' }
            }
            Assert-AcceptTestThrows { Assert-VerifyManifest ($AcceptOutput + "`n" + $AcceptBadTotal) } '*CHECK_FAILED*'
        }
    }
    foreach ($AcceptLoginVariant in @('password', 'escaped-password', 'hash')) {
        Test-AcceptBehavior ('round2 login rejects token disclosure ' + $AcceptLoginVariant) {
            Reset-AcceptState
            $script:verifyContext = Read-OnboardingFixtureContext (Join-Path $AcceptRuntime 'fixture.json') $AcceptOwner
            $script:AcceptTokenVariant = $AcceptLoginVariant
            Assert-AcceptTestThrows { Invoke-VerifyApi 'POST' 'auth/login' '' @{username = 'platform_admin'; password = 'platform-secret-1'} } '*REDACTION_FAILED*'
            Assert-AcceptTest ($script:verifyFacts.secretViolations -eq 1 -and $script:AcceptHttpCalls.Count -eq 1) 'Login disclosure was not caught at response boundary'
        }
    }
    Test-AcceptBehavior 'round2 login rejects existing other credential before business requests' {
        Reset-AcceptState
        $script:AcceptTokenVariant = 'other-credential'
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        Invoke-VerifyAcceptance | Out-Null
        Assert-AcceptTest ($script:verifyFacts.secretViolations -eq 1 -and $script:AcceptHttpCalls.Count -eq 1) 'Known credential was accepted as login token'
    }
    Test-AcceptBehavior 'round2 legitimate repeated JWT is accepted on reauthentication' {
        Reset-AcceptState
        $script:verifyContext = Read-OnboardingFixtureContext (Join-Path $AcceptRuntime 'fixture.json') $AcceptOwner
        $AcceptLogin1 = Invoke-VerifyApi 'POST' 'auth/login' '' @{username = 'platform_admin'; password = 'platform-secret-1'}
        $AcceptLogin2 = Invoke-VerifyApi 'POST' 'auth/login' '' @{username = 'platform_admin'; password = 'platform-secret-1'}
        Assert-AcceptTest ($AcceptLogin1.accessToken -ceq 'tok-platform_admin' -and $AcceptLogin2.accessToken -ceq $AcceptLogin1.accessToken) 'Legitimate repeated JWT refused'
        Assert-AcceptTest ($script:verifyFacts.secretViolations -eq 0 -and $script:AcceptHttpCalls.Count -eq 2) 'Token replay was misclassified as credential disclosure'
    }
} finally {
    $env:TEMP = $AcceptSavedTemp
    $env:TMP = $AcceptSavedTmp
}
Write-Output ('HOSPITAL_ONBOARDING_ACCEPTANCE_TESTS=' + $AcceptTests + ' FAILURES=' + $AcceptFailures.Count + ' PS=' + $PSVersionTable.PSVersion.ToString())
Write-Output ('TEST_ARTIFACTS=' + $AcceptTestRoot)
if ($AcceptFailures.Count) { exit 1 }
exit 0
