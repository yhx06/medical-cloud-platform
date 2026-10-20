param(
    [string]$FixturePath,
    [string]$EvidenceRoot,
    [string]$NewRunId,
    [switch]$Execute
)
# Real hospital-onboarding acceptance driver (Task 7). It consumes exactly one
# Task 6 owned fixture and refuses re-consumption, so a run can never silently
# verify a previously mutated environment.
#
# Default (no -Execute) performs no SQL, login, claim or mutation: it lists the
# required gates as unverified and exits 4.
#
# Intentionally ASCII for native Windows PowerShell 5.1 without a BOM.
$ErrorActionPreference = 'Stop'

# Capture the caller's intent before importing the fixture module: the fixture's
# own parameter block would otherwise shadow these names in this scope.
$verifyExecuteRequested = [bool]$Execute
$verifyFixturePath = $FixturePath
$verifyEvidenceRoot = $EvidenceRoot
$verifyNewRunId = $NewRunId

# Import the creator's frozen helper functions (no execution entry is run when
# dot-sourced). Only functions are used; its parameters are intentionally unused
# from this point on.
. (Join-Path $PSScriptRoot 'new-hospital-onboarding-fixture.ps1')

$verifyScope = 'hospital-onboarding'
$verifyRequired = @(
    'fixture-identity-and-ownership',
    'consumption-create-new',
    'mysql-32-case-manifest',
    'four-api-full-role-matrix',
    'post-acceptance-health',
    'safe-error-no-secret-leak',
    'strict-summary'
)
$verifyGates = [ordered]@{}
foreach ($verifyName in $verifyRequired) { $verifyGates[$verifyName] = 'unverified' }
$verifyFacts = [ordered]@{executedCases = 0; observedLocks = 0; apiRequests = 0; secretViolations = 0}
$verifySecrets = New-Object 'Collections.Generic.List[string]'
$verifyActiveGate = $null
$verifyCompleteDriverRun = $false
$verifySummaryWritten = $false
$verifyContext = $null
$verifyExitCode = 0

function Require-Verify([bool]$VerifyCondition) { if (-not $VerifyCondition) { throw 'HOSPITAL_ONBOARDING_CHECK_FAILED' } }
function Is-VerifyUuid($VerifyValue) { return ($VerifyValue -is [string] -and $VerifyValue -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') }
function Set-VerifyGate([string]$VerifyName, [string]$VerifyStatus) {
    if ($VerifyName -cnotin $verifyRequired -or $VerifyStatus -cnotin @('passed', 'failed', 'unverified')) { throw 'GATE_REGISTRY_INVALID' }
    $script:verifyGates[$VerifyName] = $VerifyStatus
}
function Invoke-VerifyGate([string]$VerifyName, [scriptblock]$VerifyAction) {
    $script:verifyActiveGate = $VerifyName
    try { & $VerifyAction; Set-VerifyGate $VerifyName 'passed' }
    catch { Set-VerifyGate $VerifyName 'failed'; throw }
}
function Assert-VerifyRootedPath([string]$VerifyPath, [string]$VerifyPrefix, [bool]$VerifyExisting = $true) {
    Require-Verify (-not [string]::IsNullOrWhiteSpace($VerifyPath) -and [IO.Path]::IsPathRooted($VerifyPath))
    $VerifyFull = [IO.Path]::GetFullPath($VerifyPath).TrimEnd('\', '/')
    Require-Verify ($VerifyFull.StartsWith($VerifyPrefix + '\', [StringComparison]::OrdinalIgnoreCase))
    if ($VerifyExisting) { Require-Verify (Test-Path -LiteralPath $VerifyFull) }
    Assert-OnboardingPathChain $VerifyFull
    return $VerifyFull
}
function Assert-VerifyNoSecret([string]$VerifyText) {
    foreach ($VerifySecret in $script:verifySecrets) {
        if ($VerifySecret -and $VerifyText -and $VerifyText.Contains($VerifySecret)) {
            $script:verifyFacts.secretViolations++
            throw 'HOSPITAL_ONBOARDING_REDACTION_FAILED'
        }
    }
}
function Assert-VerifyNoSecretValue($VerifyValue, [int]$VerifyDepth = 0) {
    Require-Verify ($VerifyDepth -le 32)
    if ($null -eq $VerifyValue) { return }
    if ($VerifyValue -is [string]) { Assert-VerifyNoSecret $VerifyValue; return }
    if ($VerifyValue -is [Collections.IDictionary]) {
        foreach ($VerifyKey in $VerifyValue.Keys) { Assert-VerifyNoSecret ([string]$VerifyKey); Assert-VerifyNoSecretValue $VerifyValue[$VerifyKey] ($VerifyDepth + 1) }
        return
    }
    if ($VerifyValue -is [Collections.IEnumerable]) {
        foreach ($VerifyItem in $VerifyValue) { Assert-VerifyNoSecretValue $VerifyItem ($VerifyDepth + 1) }
        return
    }
    if ($VerifyValue -is [ValueType]) { return }
    foreach ($VerifyProperty in $VerifyValue.PSObject.Properties) {
        Assert-VerifyNoSecret $VerifyProperty.Name; Assert-VerifyNoSecretValue $VerifyProperty.Value ($VerifyDepth + 1)
    }
}
function Invoke-VerifyTransport([string]$VerifyUri, [string]$VerifyMethod = 'GET', [string]$VerifyToken = $null, [string]$VerifyJson = $null) {
    $VerifyAddress = [uri]$VerifyUri
    if ($VerifyAddress.Host -ne '127.0.0.1' -or $VerifyAddress.Scheme -ne 'http') { throw 'NON_LOOPBACK_REFUSED' }
    $VerifyResponse = $null; $VerifyInput = $null; $VerifyOutput = $null; $VerifyBuffer = $null
    try {
        $VerifyRequest = [Net.HttpWebRequest]::Create($VerifyAddress)
        $VerifyRequest.Method = $VerifyMethod; $VerifyRequest.AllowAutoRedirect = $false; $VerifyRequest.Proxy = $null
        $VerifyRequest.Timeout = 15000; $VerifyRequest.ReadWriteTimeout = 15000; $VerifyRequest.Accept = 'application/json'
        $VerifyRequest.ContentType = 'application/json; charset=utf-8'
        if ($VerifyToken) { $VerifyRequest.Headers['Authorization'] = 'Bearer ' + $VerifyToken }
        if ($null -ne $VerifyJson) {
            $VerifyBytes = (New-Object Text.UTF8Encoding($false, $true)).GetBytes($VerifyJson)
            Require-Verify ($VerifyBytes.Length -le 1048576)
            $VerifyRequest.ContentLength = $VerifyBytes.Length; $VerifyOutput = $VerifyRequest.GetRequestStream()
            $VerifyOutput.Write($VerifyBytes, 0, $VerifyBytes.Length); $VerifyOutput.Dispose(); $VerifyOutput = $null
        }
        try { $VerifyResponse = $VerifyRequest.GetResponse() } catch [Net.WebException] {
            if (-not $_.Exception.Response) { throw 'HTTP_TRANSPORT_FAILED' }
            $VerifyResponse = $_.Exception.Response
        }
        Require-Verify ([int]$VerifyResponse.StatusCode -lt 300 -or [int]$VerifyResponse.StatusCode -ge 400)
        Require-Verify ($VerifyResponse.ContentLength -le 1048576)
        $VerifyInput = $VerifyResponse.GetResponseStream(); $VerifyBuffer = New-Object IO.MemoryStream
        $VerifyChunk = New-Object byte[] 8192
        while (($VerifyRead = $VerifyInput.Read($VerifyChunk, 0, $VerifyChunk.Length)) -gt 0) {
            Require-Verify ($VerifyBuffer.Length + $VerifyRead -le 1048576); $VerifyBuffer.Write($VerifyChunk, 0, $VerifyRead)
        }
        $VerifyText = (New-Object Text.UTF8Encoding($false, $true)).GetString($VerifyBuffer.ToArray())
        return @{Status = [int]$VerifyResponse.StatusCode; Text = $VerifyText}
    } catch { throw 'HTTP_TRANSPORT_FAILED' } finally {
        if ($VerifyOutput) { $VerifyOutput.Dispose() }; if ($VerifyInput) { $VerifyInput.Dispose() }
        if ($VerifyBuffer) { $VerifyBuffer.Dispose() }; if ($VerifyResponse) { $VerifyResponse.Dispose() }
    }
}
function Invoke-VerifyApi([string]$VerifyMethod, [string]$VerifyPath, [string]$VerifyAccount, $VerifyBody, [int]$VerifyExpected = 200) {
    Require-Verify ($VerifyPath -cmatch '^(auth/login|platform/tenants(?:/[0-9a-f-]{36}(?:/administrators(?:/[0-9a-f-]{36}/status)?)?)?(?:\?[A-Za-z0-9=&-]+)?)$')
    $VerifyJson = if ($null -ne $VerifyBody) { $VerifyBody | ConvertTo-Json -Depth 12 -Compress } else { $null }
    $VerifyToken = if ($VerifyAccount) { $script:verifyTokens[$VerifyAccount] } else { $null }
    $VerifyResult = Invoke-VerifyTransport ('http://127.0.0.1:' + $script:verifyContext.Journal.components.paas.port + '/paas/api/' + $VerifyPath) $VerifyMethod $VerifyToken $VerifyJson
    $script:verifyFacts.apiRequests++
    Require-Verify ($VerifyResult.Text -is [string] -and [Text.Encoding]::UTF8.GetByteCount($VerifyResult.Text) -le 1048576)
    Assert-VerifyNoSecret $VerifyResult.Text
    $VerifyParsed = $VerifyResult.Text | ConvertFrom-Json
    Assert-VerifyNoSecretValue $VerifyParsed
    Require-Verify ([int]$VerifyResult.Status -eq $VerifyExpected)
    if ($VerifyExpected -ne 200) {
        Require-Verify ($VerifyParsed.code -cmatch '^[A-Z][A-Z0-9_]{0,79}$' -and (Is-VerifyUuid $VerifyParsed.requestId))
        Require-Verify ($VerifyParsed.message -is [string] -and $VerifyParsed.message.Length -le 160)
        Require-Verify ($VerifyResult.Text -notmatch '(?i)java\.|exception|select\s|insert\s|jdbc:|password|bearer ')
        Require-Verify (@($VerifyParsed.PSObject.Properties.Name | Where-Object { $_ -cnotin @('code', 'message', 'requestId') }).Count -eq 0)
        return $null
    }
    Require-Verify ($VerifyParsed.code -ceq 'OK')
    return $VerifyParsed.data
}
# External boundaries below are isolated so the offline test can replace only
# SQL, HTTP and the Maven/native process without touching real services.
function Invoke-VerifySql([string]$VerifyName, [string]$VerifySql, [string]$VerifyLabel) {
    Invoke-OnboardingSql $script:verifyContext $VerifyName $VerifySql $VerifyLabel
}
function Invoke-VerifyMaven([string]$VerifyTestSchema, [hashtable]$VerifyDb) {
    $VerifyMysqlPart = $script:verifyContext.Journal.components.mysql
    $VerifyMaven = $script:verifyContext.Options.MavenExecutable
    if (-not $VerifyMaven -or -not (Test-Path -LiteralPath $VerifyMaven -PathType Leaf)) { throw 'MAVEN_EXECUTABLE_MISSING' }
    # Run against the shipped course source tree (same tree this script ships in).
    $VerifyPom = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\pom.xml'))
    if (-not (Test-Path -LiteralPath $VerifyPom -PathType Leaf)) { throw 'SOURCE_POM_MISSING' }
    $VerifySaved = @{}
    foreach ($VerifyEnvName in @([Environment]::GetEnvironmentVariables('Process').Keys | Where-Object { $_ -match '^(COURSE_|MEDICAL_|MINIO_|AWS_)' })) {
        $VerifySaved[$VerifyEnvName] = [Environment]::GetEnvironmentVariable($VerifyEnvName, 'Process')
        [Environment]::SetEnvironmentVariable($VerifyEnvName, $null, 'Process')
    }
    try {
        $VerifyBase = 'jdbc:mysql://127.0.0.1:' + $VerifyMysqlPart.port + '/' + $VerifyTestSchema + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
        $env:COURSE_ONBOARDING_MYSQL_REQUIRED = 'true'
        $env:COURSE_ONBOARDING_MYSQL_URL = $VerifyBase
        $env:COURSE_ONBOARDING_MYSQL_USER = $VerifyDb.user
        $env:COURSE_ONBOARDING_MYSQL_PASSWORD = $VerifyDb.password
        $env:COURSE_ONBOARDING_MYSQL_UUID = $VerifyMysqlPart.serverUuid
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_URL = $VerifyBase
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_USER = $VerifyDb.observerUser
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_PASSWORD = $VerifyDb.observerPassword
        $VerifyArgs = @('-o', '-Pmysql-integration', '-f', $VerifyPom, '-pl', 'paas', '-am', '-Dcourse.test.wildfly.home=' + $script:verifyContext.Options.WildFlyHome, '-Dtest=MySqlHospitalAdministrationTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
        $VerifyOutput = & $VerifyMaven @VerifyArgs 2>&1 | Out-String
        $VerifyExit = $LASTEXITCODE
        return @{ExitCode = $VerifyExit; Output = $VerifyOutput}
    } finally {
        foreach ($VerifyEnvName in @('COURSE_ONBOARDING_MYSQL_REQUIRED', 'COURSE_ONBOARDING_MYSQL_URL', 'COURSE_ONBOARDING_MYSQL_USER', 'COURSE_ONBOARDING_MYSQL_PASSWORD', 'COURSE_ONBOARDING_MYSQL_UUID', 'COURSE_ONBOARDING_MYSQL_OBSERVER_URL', 'COURSE_ONBOARDING_MYSQL_OBSERVER_USER', 'COURSE_ONBOARDING_MYSQL_OBSERVER_PASSWORD')) { [Environment]::SetEnvironmentVariable($VerifyEnvName, $null, 'Process') }
        foreach ($VerifyEnvName in $VerifySaved.Keys) { [Environment]::SetEnvironmentVariable($VerifyEnvName, $VerifySaved[$VerifyEnvName], 'Process') }
    }
}
function Get-VerifySchemaDdl([string]$VerifySchema) {
    Require-Verify ($VerifySchema -cmatch '^course_onboarding_test_[0-9a-f]{32}$')
    $VerifySchemaPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\schema.sql'))
    Require-Verify (Test-Path -LiteralPath $VerifySchemaPath -PathType Leaf)
    $VerifyDdl = [IO.File]::ReadAllText($VerifySchemaPath)
    Require-Verify ($VerifyDdl -match '(?m)^CREATE DATABASE IF NOT EXISTS medical_cloud_course')
    $VerifyDdl = $VerifyDdl -replace 'medical_cloud_course', $VerifySchema
    Require-Verify (-not ($VerifyDdl -match 'medical_cloud_course'))
    return $VerifyDdl
}
function Write-VerifySummary {
    foreach ($VerifyName in $verifyRequired) { if (-not $verifyGates.Contains($VerifyName)) { $verifyGates[$VerifyName] = 'unverified' } }
    $VerifyExecuted = @(); $VerifyPassed = @(); $VerifyFailed = @(); $VerifySkipped = @(); $VerifyUnverified = @()
    foreach ($VerifyName in $verifyRequired) {
        switch ($verifyGates[$VerifyName]) {
            'passed' { $VerifyExecuted += $VerifyName; $VerifyPassed += $VerifyName }
            'failed' { $VerifyExecuted += $VerifyName; $VerifyFailed += $VerifyName }
            default { $VerifyUnverified += $VerifyName }
        }
    }
    $VerifyAccepted = ($verifyCompleteDriverRun -and $VerifyPassed.Count -eq $verifyRequired.Count -and $VerifyFailed.Count -eq 0 -and $VerifySkipped.Count -eq 0 -and $VerifyUnverified.Count -eq 0)
    $VerifySummary = [ordered]@{
        schemaVersion = 1; scope = $verifyScope; completeDriverRun = [bool]$verifyCompleteDriverRun; accepted = [bool]$VerifyAccepted
        newRunId = $verifyNewRunId; sourceCommit = $verifyContext.Manifest.sourceCommit; schemaVersionVerified = $verifyContext.Manifest.schemaVersion
        required = $verifyRequired; executed = $VerifyExecuted; passed = $VerifyPassed; failed = $VerifyFailed; skipped = $VerifySkipped; unverified = $VerifyUnverified
        counts = [ordered]@{required = $verifyRequired.Count; executed = $VerifyExecuted.Count; passed = $VerifyPassed.Count; failed = $VerifyFailed.Count; skipped = 0; unverified = $VerifyUnverified.Count; apiRequests = $verifyFacts.apiRequests; executedCases = $verifyFacts.executedCases; observedLocks = $verifyFacts.observedLocks; secretViolations = $verifyFacts.secretViolations}
        facts = $verifyFacts
    }
    Assert-VerifyNoSecret ($VerifySummary | ConvertTo-Json -Depth 8)
    Assert-VerifyNoSecretValue $VerifySummary
    $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
    [void][IO.Directory]::CreateDirectory($VerifyEvidenceFull)
    $VerifySummaryPath = Join-Path $VerifyEvidenceFull ($verifyNewRunId + '-hospital-onboarding-summary.json')
    $VerifyStream = $null
    try {
        $VerifyStream = New-Object IO.FileStream($VerifySummaryPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $VerifySummaryBytes = (New-Object Text.UTF8Encoding($false)).GetBytes(($VerifySummary | ConvertTo-Json -Depth 8))
        $VerifyStream.Write($VerifySummaryBytes, 0, $VerifySummaryBytes.Length)
    } finally { if ($VerifyStream) { $VerifyStream.Dispose() } }
    $script:verifySummaryWritten = $true
}
function Write-VerifyUnverifiedSummary {
    # Default run: no SQL/login/claim; enumerate every required gate as unverified.
    foreach ($VerifyName in $verifyRequired) { $verifyGates[$VerifyName] = 'unverified' }
    $VerifySummary = [ordered]@{
        schemaVersion = 1; scope = $verifyScope; completeDriverRun = $false; accepted = $false
        newRunId = $verifyNewRunId
        required = $verifyRequired; executed = @(); passed = @(); failed = @(); skipped = @(); unverified = @($verifyRequired)
        counts = [ordered]@{required = $verifyRequired.Count; executed = 0; passed = 0; failed = 0; skipped = 0; unverified = $verifyRequired.Count; apiRequests = 0; executedCases = 0; observedLocks = 0; secretViolations = 0}
        facts = $verifyFacts
    }
    Write-Output ($VerifySummary | ConvertTo-Json -Depth 8)
}

# The acceptance entry runs only when invoked as a script (never when dot-sourced
# for the offline test), mirroring the creator module's import contract.
function Invoke-VerifyAcceptance {
# ---- Execute path ----
if (-not $verifyExecuteRequested) {
    Write-VerifyUnverifiedSummary
    Write-Output 'HOSPITAL ONBOARDING VERIFICATION INCOMPLETE: required gates remain unverified'
    $script:verifyExitCode = 4
    return
}

try {
    Invoke-VerifyGate 'fixture-identity-and-ownership' {
        Require-Verify ($verifyNewRunId -cmatch '^[0-9a-f]{32}$' -and $verifyNewRunId -cne ('0' * 32))
        Require-Verify ([IO.Path]::IsPathRooted($verifyFixturePath) -and [IO.Path]::IsPathRooted($verifyEvidenceRoot))
        $script:verifyContext = Read-OnboardingFixtureContext $verifyFixturePath $verifyNewRunId
        Require-Verify ($verifyContext.Manifest.state -ceq 'running')
        $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
        Require-Verify ($VerifyEvidenceFull.StartsWith($verifyContext.Root + '\', [StringComparison]::OrdinalIgnoreCase) -or $VerifyEvidenceFull.StartsWith((Get-OnboardingProjectRoot) + '\.local\evidence', [StringComparison]::OrdinalIgnoreCase))
        Assert-OnboardingPrivateParent $VerifyEvidenceFull
        $VerifyAccountsPath = Join-Path $verifyContext.Root 'accounts.json'
        Assert-OnboardingPathChain $VerifyAccountsPath
        Assert-OnboardingPrivateParent $VerifyAccountsPath
        $VerifyAccountsRaw = [IO.File]::ReadAllText($VerifyAccountsPath) | ConvertFrom-Json
        $script:verifyAccounts = @($VerifyAccountsRaw)
        Require-Verify ($script:verifyAccounts.Count -ge 2)
        foreach ($VerifyAccount in $script:verifyAccounts) {
            Require-Verify ($VerifyAccount.username -and $VerifyAccount.password -and $VerifyAccount.role)
            $script:verifySecrets.Add([string]$VerifyAccount.password)
        }
        Require-Verify (@($script:verifyAccounts | Where-Object { $_.role -ceq 'PLATFORM_ADMIN' }).Count -ge 1)
        $VerifyCredentials = [IO.File]::ReadAllText((Join-Path $verifyContext.Root 'credentials.json')) | ConvertFrom-Json
        foreach ($VerifySecret in @($VerifyCredentials.dbPassword, $VerifyCredentials.mysqlRootPassword, $VerifyCredentials.storageRootSecret, $VerifyCredentials.storageAppSecret)) { $script:verifySecrets.Add([string]$VerifySecret) }
    }
    Invoke-VerifyGate 'consumption-create-new' {
        $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
        [void][IO.Directory]::CreateDirectory($VerifyEvidenceFull)
        Assert-OnboardingPrivateParent $VerifyEvidenceFull
        # Intent-first: a fresh run must claim its evidence slot before any SQL or
        # login, so an interrupted run can never silently reuse a consumed fixture.
        $VerifyConsumptionPath = Join-Path $VerifyEvidenceFull ($verifyNewRunId + '-hospital-onboarding-consumption.json')
        Assert-OnboardingPathChain $VerifyConsumptionPath
        $VerifyConsumption = [ordered]@{schemaVersion = 1; scope = $verifyScope; newRunId = $verifyNewRunId; sourceCommit = $verifyContext.Manifest.sourceCommit; fixturePath = [IO.Path]::GetFullPath($verifyFixturePath); state = 'intent'; startedAt = (Get-Date).ToUniversalTime().ToString('o')}
        $VerifyStream = $null
        try {
            $VerifyStream = New-Object IO.FileStream($VerifyConsumptionPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            $VerifyBytes = (New-Object Text.UTF8Encoding($false)).GetBytes(($VerifyConsumption | ConvertTo-Json -Depth 6))
            $VerifyStream.Write($VerifyBytes, 0, $VerifyBytes.Length)
        } catch [IO.IOException] {
            throw 'FIXTURE_ALREADY_CONSUMED'
        } finally { if ($VerifyStream) { $VerifyStream.Dispose() } }
        $script:verifyConsumptionPath = $VerifyConsumptionPath
    }
    Invoke-VerifyGate 'mysql-32-case-manifest' {
        $VerifySchema = 'course_onboarding_test_' + $verifyNewRunId
        $VerifyDbUser = 'hobt_' + $verifyNewRunId.Substring(0, 12)
        $VerifyObserver = 'hobo_' + $verifyNewRunId.Substring(0, 12)
        $VerifyDbPassword = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
        $VerifyObserverPassword = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
        $script:verifySecrets.Add($VerifyDbPassword); $script:verifySecrets.Add($VerifyObserverPassword)
        # Provision an isolated test schema (never touch the medical_cloud_course DB).
        Invoke-VerifySql 'mysql' ('DROP DATABASE IF EXISTS ' + $VerifySchema + ';') 'verify-schema-drop'
        Invoke-VerifySql 'mysql' ('CREATE DATABASE ' + $VerifySchema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;') 'verify-schema-create'
        $VerifyDdl = Get-VerifySchemaDdl $VerifySchema
        # The shipped schema.sql carries its own CREATE DATABASE/USE; strip them and
        # feed only the table DDL into the already-created test schema.
        $VerifyDdl = $VerifyDdl -replace '(?m)^CREATE DATABASE[^;]*;', '' -replace '(?m)^USE[^;]*;', ''
        Invoke-VerifySql 'mysql' $VerifyDdl 'verify-schema-apply'
        Invoke-VerifySql 'mysql' ('CREATE USER IF NOT EXISTS ''' + $VerifyDbUser + '''@''127.0.0.1'' IDENTIFIED BY ''' + $VerifyDbPassword + ''';') 'verify-user-create'
        Invoke-VerifySql 'mysql' ('CREATE USER IF NOT EXISTS ''' + $VerifyObserver + '''@''127.0.0.1'' IDENTIFIED BY ''' + $VerifyObserverPassword + ''';') 'verify-observer-create'
        Invoke-VerifySql 'mysql' ('GRANT SELECT,INSERT,UPDATE,DELETE ON ' + $VerifySchema + '.* TO ''' + $VerifyDbUser + '''@''127.0.0.1'';') 'verify-user-grant'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON ' + $VerifySchema + '.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-grant'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON information_schema.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-info'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON performance_schema.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-perf'
        $VerifyResult = Invoke-VerifyMaven $VerifySchema @{user = $VerifyDbUser; password = $VerifyDbPassword; observerUser = $VerifyObserver; observerPassword = $VerifyObserverPassword}
        Assert-VerifyNoSecret $VerifyResult.Output
        Require-Verify ($VerifyResult.ExitCode -eq 0)
        $VerifyCaseLines = @($VerifyResult.Output -split "`r?`n" | Where-Object { $_ -match '^ONBOARDING_REQUIRED_CASE=' })
        $VerifyPassedLines = @($VerifyResult.Output -split "`r?`n" | Where-Object { $_ -match '^ONBOARDING_PASSED_CASE=' })
        $VerifyLockLines = @($VerifyResult.Output -split "`r?`n" | Where-Object { $_ -match '^ONBOARDING_OBSERVED_LOCK=' })
        $script:verifyFacts.executedCases = $VerifyPassedLines.Count
        $script:verifyFacts.observedLocks = $VerifyLockLines.Count
        Require-Verify ($VerifyCaseLines.Count -eq 32)
        Require-Verify ($VerifyPassedLines.Count -eq 32)
        Require-Verify ($VerifyLockLines.Count -eq 26)
        Require-Verify ($VerifyResult.Output -notmatch 'FAILED|<<< ERROR|Skipped: [1-9]')
        Require-Verify ($VerifyResult.Output -match 'Tests run: 32' -or $VerifyCaseLines.Count -eq 32)
    }
    Invoke-VerifyGate 'four-api-full-role-matrix' {
        $script:verifyTokens = @{}
        foreach ($VerifyAccount in $script:verifyAccounts) {
            $VerifyLogin = Invoke-VerifyApi 'POST' 'auth/login' '' @{username = $VerifyAccount.username; password = $VerifyAccount.password}
            Require-Verify ($VerifyLogin.username -ceq $VerifyAccount.username -and $VerifyLogin.accessToken -is [string] -and $VerifyLogin.accessToken.Length -gt 0 -and $VerifyLogin.accessToken.Length -le 16384)
            $script:verifyTokens[$VerifyAccount.username] = $VerifyLogin.accessToken
            $script:verifySecrets.Add([string]$VerifyLogin.accessToken)
        }
        $VerifyPlatformAccount = @($script:verifyAccounts | Where-Object { $_.role -ceq 'PLATFORM_ADMIN' })[0]
        Require-Verify ($null -ne $VerifyPlatformAccount)
        $VerifyPlatform = $VerifyPlatformAccount.username
        # 1. Atomic open: hospital + first administrator in one request.
        $VerifyKey = [guid]::NewGuid().ToString()
        $VerifyOpen = Invoke-VerifyApi 'POST' 'platform/tenants' $VerifyPlatform @{hospitalName = 'Fictional Verify Hospital'; username = 'verify_admin'; initialPassword = 'Vfy-1234-Admin'; operationKey = $VerifyKey}
        Require-Verify ((Is-VerifyUuid $VerifyOpen.tenantId) -and (Is-VerifyUuid $VerifyOpen.administratorId) -and $VerifyOpen.username -ceq 'verify_admin')
        $VerifyTenant = $VerifyOpen.tenantId
        # 2. Idempotent replay of the same operationKey returns the same identity.
        $VerifyReplay = Invoke-VerifyApi 'POST' 'platform/tenants' $VerifyPlatform @{hospitalName = 'Fictional Verify Hospital'; username = 'verify_admin'; initialPassword = 'Vfy-1234-Admin'; operationKey = $VerifyKey}
        Require-Verify ($VerifyReplay.tenantId -ceq $VerifyTenant -and $VerifyReplay.administratorId -ceq $VerifyOpen.administratorId)
        # 3. Conflict: a reused operationKey with a different actor intent is rejected.
        $null = Invoke-VerifyApi 'POST' 'platform/tenants' $VerifyPlatform @{hospitalName = 'Other'; username = 'other_admin'; initialPassword = 'Vfy-1234-Other'; operationKey = $VerifyKey} 409
        # 4. Administrator list with a safe projection (no password hash field).
        $VerifyList = Invoke-VerifyApi 'GET' ('platform/tenants/' + $VerifyTenant + '/administrators?page=1&size=20') $VerifyPlatform $null
        Require-Verify ($VerifyList.total -ge 1 -and @($VerifyList.items).Count -ge 1 -and $VerifyList.activeAdministratorCount -ge 1)
        foreach ($VerifyItem in @($VerifyList.items)) { Require-Verify ($VerifyItem.id -and $VerifyItem.username -and $VerifyItem.status -and -not ($VerifyItem.PSObject.Properties.Name -contains 'passwordHash' -or $VerifyItem.PSObject.Properties.Name -contains 'password_hash')) }
        # 5. Add a second administrator and authenticate it.
        $VerifyAdd = Invoke-VerifyApi 'POST' ('platform/tenants/' + $VerifyTenant + '/administrators') $VerifyPlatform @{username = 'verify_second'; initialPassword = 'Vfy-1234-Second'; operationKey = ([guid]::NewGuid().ToString())}
        Require-Verify (Is-VerifyUuid $VerifyAdd.administratorId)
        $VerifySecondLogin = Invoke-VerifyApi 'POST' 'auth/login' '' @{username = 'verify_second'; password = 'Vfy-1234-Second'}
        $script:verifySecrets.Add([string]$VerifySecondLogin.accessToken)
        # 6. Status change: disable the second administrator.
        $VerifyStatus = Invoke-VerifyApi 'PUT' ('platform/tenants/' + $VerifyTenant + '/administrators/' + $VerifyAdd.administratorId + '/status') $VerifyPlatform @{status = 'DISABLED'; expectedStatus = 'ACTIVE'}
        Require-Verify ($VerifyStatus.id -ceq $VerifyAdd.administratorId -and $VerifyStatus.status -ceq 'DISABLED')
        # 7. Disabled administrator's old token is rejected (401).
        $VerifyDisabledToken = $VerifySecondLogin.accessToken
        $script:verifyTokens['__disabled__'] = $VerifyDisabledToken
        $null = Invoke-VerifyApi 'GET' ('platform/tenants/' + $VerifyTenant + '/administrators') '__disabled__' $null 401
        # 8. Last-active administrator is protected from being disabled.
        $null = Invoke-VerifyApi 'PUT' ('platform/tenants/' + $VerifyTenant + '/administrators/' + $VerifyOpen.administratorId + '/status') $VerifyPlatform @{status = 'DISABLED'; expectedStatus = 'ACTIVE'} 409
        # 9. Unauthorized role is rejected (non-platform account cannot open hospitals).
        $VerifyNonPlatform = @($script:verifyAccounts | Where-Object { $_.role -cne 'PLATFORM_ADMIN' })[0]
        $null = Invoke-VerifyApi 'POST' 'platform/tenants' $VerifyNonPlatform.username @{hospitalName = 'x'; username = 'x'; initialPassword = 'x'; operationKey = ([guid]::NewGuid().ToString())} 403
    }
    Invoke-VerifyGate 'post-acceptance-health' {
        # Every component must still be the same owned identity after all mutations.
        foreach ($VerifyComponent in @($verifyContext.Journal.components.Keys)) {
            if ($verifyContext.Journal.components[$VerifyComponent].status -eq 'running') { Assert-OnboardingIdentity $verifyContext $VerifyComponent }
        }
        Assert-OnboardingMysqlLive $verifyContext 'mysql'
        Invoke-VerifySql 'mysql' 'SELECT 1;' 'verify-health-ping'
        Require-Verify ([IO.File]::ReadAllText((Join-Path $verifyContext.Root 'owner.id')) -ceq $verifyNewRunId)
    }
    Invoke-VerifyGate 'safe-error-no-secret-leak' {
        Require-Verify ($verifyFacts.secretViolations -eq 0)
        $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
        foreach ($VerifyFile in @(Get-ChildItem -LiteralPath $VerifyEvidenceFull -File)) {
            Require-Verify ($VerifyFile.Length -le 16777216)
            Assert-VerifyNoSecret ([IO.File]::ReadAllText($VerifyFile.FullName))
        }
    }
    $verifyCompleteDriverRun = $true
} catch {
    if ($verifyActiveGate -in $verifyRequired -and $verifyGates[$verifyActiveGate] -ne 'failed') { Set-VerifyGate $verifyActiveGate 'failed' }
    # Mark the consumption record failed so a later run never treats it as verified.
    if ($script:verifyConsumptionPath -and (Test-Path -LiteralPath $script:verifyConsumptionPath)) {
        try {
            $VerifyConsumption = [IO.File]::ReadAllText($script:verifyConsumptionPath) | ConvertFrom-Json
            $VerifyConsumption.state = 'failed'
            $VerifyConsumption | Add-Member -NotePropertyName failedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
            [IO.File]::WriteAllText($script:verifyConsumptionPath, ($VerifyConsumption | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
        } catch { }
    }
    Write-Output ('HOSPITAL ONBOARDING VERIFICATION FAILED: ' + $_.Exception.Message)
    $script:verifyExitCode = 1
    return
}

# Strict summary: any required gate that is missing/skip/unverified forbids exit 0.
Invoke-VerifyGate 'strict-summary' {
    foreach ($VerifyName in $verifyRequired) {
        if ($VerifyName -ceq 'strict-summary') { continue }
        if ($verifyGates[$VerifyName] -cnotin @('passed', 'failed')) { throw 'REQUIRED_GATE_UNRESOLVED' }
    }
}
# Finalize consumption: only a fully resolved run marks the fixture as verified.
if ($script:verifyConsumptionPath -and (Test-Path -LiteralPath $script:verifyConsumptionPath)) {
    try {
        $VerifyConsumption = [IO.File]::ReadAllText($script:verifyConsumptionPath) | ConvertFrom-Json
        $VerifyConsumption.state = 'verified'
        $VerifyConsumption | Add-Member -NotePropertyName completedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
        [IO.File]::WriteAllText($script:verifyConsumptionPath, ($VerifyConsumption | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    } catch { }
}
try { Write-VerifySummary } catch {
    Write-Output 'HOSPITAL ONBOARDING VERIFICATION ABORTED: ownership or evidence precondition failed'
    $script:verifyExitCode = 3
    return
}
if (-not $verifySummaryWritten) { Write-Output 'HOSPITAL ONBOARDING VERIFICATION ABORTED'; $script:verifyExitCode = 3; return }
if (@($verifyGates.Values | Where-Object { $_ -eq 'failed' }).Count) { Write-Output 'HOSPITAL ONBOARDING VERIFICATION FAILED'; $script:verifyExitCode = 1; return }
if (-not $verifyCompleteDriverRun -or @($verifyGates.Values | Where-Object { $_ -ne 'passed' }).Count) { Write-Output 'HOSPITAL ONBOARDING VERIFICATION INCOMPLETE: required gates remain unverified'; $script:verifyExitCode = 4; return }
Write-Output 'HOSPITAL ONBOARDING VERIFICATION PASSED'; $script:verifyExitCode = 0; return
}

# Run the acceptance entry only when invoked as a script (not dot-sourced).
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-VerifyAcceptance
    exit $script:verifyExitCode
}
