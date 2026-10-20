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
    'source-commit-export',
    'mysql-32-case-manifest',
    'four-api-smoke',
    'four-api-full-role-matrix',
    'jta-success-audit-fault-rollback',
    'application-12-table-null-hex-rollback',
    'jta-independent-failed-audit',
    'jta-lock-release',
    'exact-trigger-recovery',
    'migration-refusal-cases',
    'post-acceptance-health',
    'safe-error-no-secret-leak',
    'consumption-finalization',
    'strict-summary'
)
$verifyGates = [ordered]@{}
foreach ($verifyName in $verifyRequired) { $verifyGates[$verifyName] = 'unverified' }
$verifyFacts = [ordered]@{executedCases = 0; observedLocks = 0; apiRequests = 0; secretViolations = 0}
$verifySecrets = New-Object 'Collections.Generic.List[string]'
$verifyCredentialSecrets = New-Object 'Collections.Generic.List[string]'
$verifyActiveGate = $null
$verifyCompleteDriverRun = $false
$verifySummaryWritten = $false
$verifyContext = $null
$verifyExitCode = 0
$verifyIdentityLost = $false
$verifyClaimOwned = $false
$verifySourceRoot = $null
$verifyBuildRoot = $null
$verifyFailureCode = $null

# All state-changing boundaries call this immediately before use. A lost owner,
# path, ACL, process or listener is terminal: preserve the last intent/evidence.
function Assert-VerifyOwned {
    if ($script:verifyIdentityLost) { throw 'OWNED_IDENTITY_LOST' }
    try {
        Require-Verify ($null -ne $script:verifyContext)
        Require-Verify ([IO.File]::ReadAllText((Join-Path $verifyContext.Root 'owner.id')) -ceq $verifyNewRunId)
        Assert-OnboardingPathChain $verifyContext.Root
        Assert-OnboardingPrivateParent $verifyContext.Root
        Assert-OnboardingAllProcesses $verifyContext
    } catch { $script:verifyIdentityLost = $true; throw 'OWNED_IDENTITY_LOST' }
}
function Assert-VerifyWritePath([string]$VerifyTarget) {
    Assert-VerifyOwned
    try {
        $VerifyFull = [IO.Path]::GetFullPath($VerifyTarget)
        $VerifyAllowed = @($verifyContext.Root, [IO.Path]::GetFullPath($verifyEvidenceRoot))
        if ($script:verifyBuildRoot) { $VerifyAllowed += $script:verifyBuildRoot }
        Require-Verify (@($VerifyAllowed | Where-Object { $VerifyFull -ieq $_ -or $VerifyFull.StartsWith($_.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0)
        Assert-OnboardingPathChain $VerifyFull
        $VerifyParent = $VerifyFull
        while (-not (Test-Path -LiteralPath $VerifyParent)) { $VerifyParent = Split-Path $VerifyParent -Parent }
        Assert-OnboardingPrivateParent $VerifyParent
    } catch { $script:verifyIdentityLost = $true; throw 'EVIDENCE_PRECONDITION_LOST' }
}
function Invoke-VerifyNative($VerifyExecutable, $VerifyArguments, $VerifyDirectory, [string]$VerifyLabel) {
    Assert-VerifyOwned
    $VerifyNative = Invoke-OnboardingNative $VerifyExecutable $VerifyArguments $VerifyDirectory $null $VerifyLabel
    Assert-VerifyOwned
    Assert-VerifyNoSecret ([string]$VerifyNative.Output + [string]$VerifyNative.Error)
    return $VerifyNative
}
function Initialize-VerifySource {
    $VerifyCommit = [string]$verifyContext.Manifest.sourceCommit
    Require-Verify ($VerifyCommit -cmatch '^[0-9a-f]{40}$' -and $VerifyCommit -ceq $verifyContext.Journal.sourceCommit)
    $VerifyCheckout = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $VerifyIdentity = Invoke-VerifyNative 'git.exe' @('-C', $VerifyCheckout, 'rev-parse', '--verify', ($VerifyCommit + '^{commit}')) $verifyContext.Root 'verify-source-identity'
    Require-Verify ($VerifyIdentity.ExitCode -eq 0 -and $VerifyIdentity.Output.Trim() -ceq $VerifyCommit)
    $script:verifyBuildRoot = Join-Path (Get-OnboardingProjectRoot) ('.local/builds/hv-' + [guid]::NewGuid().ToString('N').Substring(0, 12))
    # The runtime is private; create the new build directory with the same ACL
    # before exporting, never allowing inherited broad write access.
    Assert-VerifyOwned
    Assert-OnboardingPathChain $script:verifyBuildRoot
    Require-Verify (-not (Test-Path -LiteralPath $script:verifyBuildRoot))
    [void][IO.Directory]::CreateDirectory($script:verifyBuildRoot)
    Set-OnboardingPrivateAcl $script:verifyBuildRoot
    $VerifyArchive = Join-Path $script:verifyBuildRoot 'source.zip'
    Assert-VerifyWritePath $VerifyArchive
    $VerifyExport = Invoke-VerifyNative 'git.exe' @('-C', $VerifyCheckout, 'archive', '--format=zip', ('--output=' + $VerifyArchive), $VerifyCommit, 'course') $script:verifyBuildRoot 'verify-source-export'
    Require-Verify ($VerifyExport.ExitCode -eq 0)
    $script:verifySourceRoot = Join-Path $script:verifyBuildRoot 'src'
    Assert-VerifyWritePath $script:verifySourceRoot
    Expand-OnboardingSource $VerifyArchive $script:verifySourceRoot
    Require-Verify (Test-Path -LiteralPath (Join-Path $script:verifySourceRoot 'course/pom.xml') -PathType Leaf)
    $script:verifyFacts.sourceExportVerified = $true
}

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
function Add-VerifyCredential([string]$VerifySecret) {
    if ($VerifySecret) { $script:verifySecrets.Add($VerifySecret); $script:verifyCredentialSecrets.Add($VerifySecret) }
}
function Assert-VerifyNoSecret([string]$VerifyText, [switch]$VerifyCredentialsOnly) {
    if ($VerifyText -match '\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}') { $script:verifyFacts.secretViolations++; throw 'HOSPITAL_ONBOARDING_REDACTION_FAILED' }
    $VerifyRegistry = if ($VerifyCredentialsOnly) { $script:verifyCredentialSecrets } else { $script:verifySecrets }
    foreach ($VerifySecret in $VerifyRegistry) {
        if ($VerifySecret -and $VerifyText -and $VerifyText.Contains($VerifySecret)) {
            $script:verifyFacts.secretViolations++
            throw 'HOSPITAL_ONBOARDING_REDACTION_FAILED'
        }
    }
}
function Assert-VerifyNoSecretBytes([byte[]]$VerifyBytes) {
    # Latin-1 preserves each byte one-to-one; binary/class/session files need
    # no whole-file text decoding to locate an exact encoded secret sequence.
    $VerifyByteEncoding = [Text.Encoding]::GetEncoding(28591)
    $VerifyByteText = $VerifyByteEncoding.GetString($VerifyBytes)
    if ($VerifyByteText -match '\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}') { $script:verifyFacts.secretViolations++; throw 'HOSPITAL_ONBOARDING_REDACTION_FAILED' }
    foreach ($VerifySecret in $script:verifySecrets) {
        if (-not $VerifySecret) { continue }
        foreach ($VerifyEncoding in @([Text.Encoding]::UTF8, [Text.Encoding]::Unicode, [Text.Encoding]::BigEndianUnicode)) {
            $VerifyNeedle = $VerifyByteEncoding.GetString($VerifyEncoding.GetBytes($VerifySecret))
            if ($VerifyByteText.Contains($VerifyNeedle)) { $script:verifyFacts.secretViolations++; throw 'HOSPITAL_ONBOARDING_REDACTION_FAILED' }
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
    if ($VerifyBody) {
        foreach ($VerifySensitiveField in @('password', 'initialPassword')) {
            if ($VerifyBody.ContainsKey($VerifySensitiveField)) { Add-VerifyCredential ([string]$VerifyBody[$VerifySensitiveField]) }
        }
    }
    Assert-VerifyOwned
    $VerifyResult = Invoke-VerifyTransport ('http://127.0.0.1:' + $script:verifyContext.Journal.components.paas.port + '/paas/api/' + $VerifyPath) $VerifyMethod $VerifyToken $VerifyJson
    $script:verifyFacts.apiRequests++
    Require-Verify ($VerifyResult.Text -is [string] -and [Text.Encoding]::UTF8.GetByteCount($VerifyResult.Text) -le 1048576)
    $VerifyParsed = $VerifyResult.Text | ConvertFrom-Json
    # Login is the sole response allowed to introduce a token. Remove only that
    # field for leak scanning; all remaining login and business fields are scanned.
    if ($VerifyPath -ceq 'auth/login' -and $VerifyExpected -eq 200) {
        Require-Verify ($VerifyParsed.data.accessToken -is [string] -and $VerifyParsed.data.accessToken.Length -gt 0 -and $VerifyParsed.data.accessToken.Length -le 16384)
        Assert-VerifyNoSecret ([string]$VerifyParsed.data.accessToken) -VerifyCredentialsOnly
        $VerifyLoginScan = $VerifyResult.Text | ConvertFrom-Json
        $VerifyLoginScan.data.PSObject.Properties.Remove('accessToken')
        Assert-VerifyNoSecretValue $VerifyLoginScan
        $script:verifySecrets.Add([string]$VerifyParsed.data.accessToken)
    } else { Assert-VerifyNoSecret $VerifyResult.Text; Assert-VerifyNoSecretValue $VerifyParsed }
    Require-Verify ([int]$VerifyResult.Status -eq $VerifyExpected)
    if ($VerifyExpected -ne 200) {
        Require-Verify ($VerifyParsed.code -cmatch '^[A-Z][A-Z0-9_]{0,79}$' -and (Is-VerifyUuid $VerifyParsed.requestId))
        Require-Verify ($VerifyParsed.message -is [string] -and $VerifyParsed.message.Length -le 160)
        Require-Verify ($VerifyResult.Text -notmatch '(?i)java\.|exception|select\s|insert\s|jdbc:|password|bearer ')
        Require-Verify (@($VerifyParsed.PSObject.Properties.Name | Where-Object { $_ -cnotin @('code', 'message', 'requestId') }).Count -eq 0)
        return $null
    }
    Require-Verify ($VerifyParsed.code -ceq 'OK')
    Require-Verify (@($VerifyParsed.PSObject.Properties.Name | Where-Object { $_ -cnotin @('code', 'message', 'data') }).Count -eq 0)
    $VerifyAllowedFields = if ($VerifyPath -ceq 'auth/login') { @('accessToken', 'username', 'role') }
        elseif ($VerifyMethod -ceq 'GET') { @('tenant', 'items', 'total', 'page', 'size', 'activeAdministratorCount') }
        elseif ($VerifyMethod -ceq 'PUT') { @('id', 'tenantId', 'username', 'status') }
        else { @('tenantId', 'hospitalName', 'administratorId', 'username') }
    Require-Verify (@($VerifyParsed.data.PSObject.Properties.Name | Where-Object { $_ -cnotin $VerifyAllowedFields }).Count -eq 0)
    if ($VerifyMethod -ceq 'GET') {
        foreach ($VerifyItem in @($VerifyParsed.data.items)) { Require-Verify (@($VerifyItem.PSObject.Properties.Name | Where-Object { $_ -cnotin @('id', 'tenantId', 'username', 'status') }).Count -eq 0) }
        if ($VerifyParsed.data.tenant) { Require-Verify (@($VerifyParsed.data.tenant.PSObject.Properties.Name | Where-Object { $_ -cnotin @('id', 'name', 'status') }).Count -eq 0) }
    }
    return $VerifyParsed.data
}
# External boundaries below are isolated so the offline test can replace only
# SQL, HTTP and the Maven/native process without touching real services.
function Invoke-VerifySql([string]$VerifyName, [string]$VerifySql, [string]$VerifyLabel) {
    Assert-VerifyOwned
    $VerifySqlOutput = Invoke-OnboardingSql $script:verifyContext $VerifyName $VerifySql $VerifyLabel
    Assert-VerifyNoSecret ([string]$VerifySqlOutput)
    if ($VerifyLabel -ceq 'verify-schema-absent') { return [string]$VerifySqlOutput }
}
function Invoke-VerifyMaven([string]$VerifyTestSchema, [hashtable]$VerifyDb) {
    $VerifyMysqlPart = $script:verifyContext.Journal.components.mysql
    $VerifyMaven = $script:verifyContext.Options.MavenExecutable
    if (-not $VerifyMaven -or -not (Test-Path -LiteralPath $VerifyMaven -PathType Leaf)) { throw 'MAVEN_EXECUTABLE_MISSING' }
    Require-Verify ($script:verifyFacts.sourceExportVerified -eq $true)
    $VerifyPom = Join-Path $script:verifySourceRoot 'course/pom.xml'
    if (-not (Test-Path -LiteralPath $VerifyPom -PathType Leaf)) { throw 'SOURCE_POM_MISSING' }
    $VerifySaved = @{}
    $VerifyFixedEnvironment = @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_HOME', 'MYSQL_PWD', 'TEMP', 'TMP')
    foreach ($VerifyEnvName in @(@([Environment]::GetEnvironmentVariables('Process').Keys | Where-Object { $_ -match '^(COURSE_|MEDICAL_|ATTACHMENT_|MINIO_|AWS_)' }) + $VerifyFixedEnvironment | Select-Object -Unique)) {
        $VerifySaved[$VerifyEnvName] = [Environment]::GetEnvironmentVariable($VerifyEnvName, 'Process')
        [Environment]::SetEnvironmentVariable($VerifyEnvName, $null, 'Process')
    }
    try {
        $VerifyTemp = Join-Path $script:verifyBuildRoot 'tmp'
        Assert-VerifyWritePath $VerifyTemp
        [void][IO.Directory]::CreateDirectory($VerifyTemp)
        $env:TEMP = $VerifyTemp; $env:TMP = $VerifyTemp
        $env:JAVA_HOME = $script:verifyContext.Options.JavaHome
        Require-Verify ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe') -PathType Leaf))
        Assert-OnboardingPathChain $env:JAVA_HOME
        $VerifyJava = Invoke-VerifyNative (Join-Path $env:JAVA_HOME 'bin/javac.exe') @('-version') $script:verifyBuildRoot 'verify-java-version'
        Require-Verify ($VerifyJava.ExitCode -eq 0 -and ($VerifyJava.Output + $VerifyJava.Error) -match 'javac 1\.8\.')
        $env:MAVEN_OPTS = '-Djava.io.tmpdir="' + $VerifyTemp + '" -Dfile.encoding=UTF-8'
        $VerifyBase = 'jdbc:mysql://127.0.0.1:' + $VerifyMysqlPart.port + '/' + $VerifyTestSchema + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
        $env:COURSE_ONBOARDING_MYSQL_REQUIRED = 'true'
        $env:COURSE_ONBOARDING_MYSQL_URL = $VerifyBase
        $env:COURSE_ONBOARDING_MYSQL_USER = $VerifyDb.user
        $env:COURSE_ONBOARDING_MYSQL_PASSWORD = $VerifyDb.password
        $env:COURSE_ONBOARDING_MYSQL_UUID = $VerifyMysqlPart.serverUuid
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_URL = $VerifyBase
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_USER = $VerifyDb.observerUser
        $env:COURSE_ONBOARDING_MYSQL_OBSERVER_PASSWORD = $VerifyDb.observerPassword
        $VerifyArgs = @('-o', '-Pmysql-integration', '-f', $VerifyPom, '-pl', 'paas', '-am', ('-Dcourse.test.wildfly.home=' + $script:verifyContext.Options.WildFlyHome), '-Dtest=MySqlHospitalAdministrationTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
        $VerifyArgs = @(('-Djava.io.tmpdir=' + $VerifyTemp), '-Dfile.encoding=UTF-8') + $VerifyArgs
        $VerifyNative = Invoke-VerifyNative $VerifyMaven $VerifyArgs $script:verifyBuildRoot 'verify-maven'
        return @{ExitCode = $VerifyNative.ExitCode; Output = ([string]$VerifyNative.Output + "`n" + [string]$VerifyNative.Error)}
    } finally {
        foreach ($VerifyEnvName in @('COURSE_ONBOARDING_MYSQL_REQUIRED', 'COURSE_ONBOARDING_MYSQL_URL', 'COURSE_ONBOARDING_MYSQL_USER', 'COURSE_ONBOARDING_MYSQL_PASSWORD', 'COURSE_ONBOARDING_MYSQL_UUID', 'COURSE_ONBOARDING_MYSQL_OBSERVER_URL', 'COURSE_ONBOARDING_MYSQL_OBSERVER_USER', 'COURSE_ONBOARDING_MYSQL_OBSERVER_PASSWORD')) { [Environment]::SetEnvironmentVariable($VerifyEnvName, $null, 'Process') }
        foreach ($VerifyEnvName in $VerifySaved.Keys) { [Environment]::SetEnvironmentVariable($VerifyEnvName, $VerifySaved[$VerifyEnvName], 'Process') }
    }
}
function Get-VerifySchemaDdl([string]$VerifySchema) {
    Require-Verify ($VerifySchema -cmatch '^course_onboarding_test_[0-9a-f]{32}$')
    Require-Verify ($script:verifyFacts.sourceExportVerified -eq $true)
    $VerifySchemaPath = Join-Path $script:verifySourceRoot 'course/deploy/schema.sql'
    Require-Verify (Test-Path -LiteralPath $VerifySchemaPath -PathType Leaf)
    $VerifyDdl = [IO.File]::ReadAllText($VerifySchemaPath)
    Require-Verify ($VerifyDdl -match '(?m)^CREATE DATABASE IF NOT EXISTS medical_cloud_course')
    $VerifyDdl = $VerifyDdl -replace 'medical_cloud_course', $VerifySchema
    Require-Verify (-not ($VerifyDdl -match 'medical_cloud_course'))
    return $VerifyDdl
}
function Assert-VerifyManifest([string]$VerifyOutput) {
    $VerifyCases = @(
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
        'replayFrozenAndDisabled', 'zeroActiveRecovery')
    $VerifyWaits = @($VerifyCases | Where-Object { $_ -cmatch '^(sameKey|caseInsensitive|disable|freeze)' })
    foreach ($VerifyKind in @('REQUIRED_CASE', 'PASSED_CASE', 'OBSERVED_LOCK')) {
        $VerifyValues = @($VerifyOutput -split "`r?`n" | Where-Object { $_ -cmatch ('^ONBOARDING_' + $VerifyKind + '=') } | ForEach-Object { $_.Substring(('ONBOARDING_' + $VerifyKind + '=').Length) })
        $VerifyExpected = $VerifyCases
        if ($VerifyKind -ceq 'OBSERVED_LOCK') {
            $VerifyExpected = $VerifyWaits
            foreach ($VerifyValue in $VerifyValues) { Require-Verify ($VerifyValue -cmatch ':innodb-wait(?:-duplicate-key)?$') }
            # Both suffixes are emitted by the frozen MySQL observer. The wait's
            # mandatory case identity remains exact and unique after normalization.
            $VerifyValues = @($VerifyValues | ForEach-Object { $_ -creplace ':innodb-wait(?:-duplicate-key)?$', '' })
        }
        Require-Verify ($VerifyValues.Count -eq $VerifyExpected.Count)
        Require-Verify (@($VerifyValues | Sort-Object -Unique).Count -eq $VerifyExpected.Count)
        foreach ($VerifyExpectedValue in $VerifyExpected) { Require-Verify (@($VerifyValues | Where-Object { $_ -ceq $VerifyExpectedValue }).Count -eq 1) }
    }
    $VerifyTotalLines = @($VerifyOutput -split "`r?`n" | Where-Object { $_ -cmatch 'Tests run:' })
    Require-Verify ($VerifyTotalLines.Count -ge 1)
    foreach ($VerifyTotalLine in $VerifyTotalLines) {
        Require-Verify ($VerifyTotalLine.Length -le 2048)
        # Maven's known severity prefixes and bounded ANSI SGR colors are
        # formatting only. Unknown prefixes/escape sequences cannot hide an
        # extra bad summary beside a valid one.
        $VerifyTotalPlain = $VerifyTotalLine -creplace '\x1b\[[0-9;]{0,24}m', ''
        $VerifyTotal = [regex]::Match($VerifyTotalPlain, '^[ \t]*(?:\[(?:INFO|WARNING|ERROR)\][ \t]+)?Tests run: (\d{1,6}), Failures: (\d{1,6}), Errors: (\d{1,6}), Skipped: (\d{1,6})(?:, Time elapsed:[^\r\n]{0,512})?[ \t]*$')
        Require-Verify ($VerifyTotal.Success)
        Require-Verify ($VerifyTotal.Groups[1].Value -ceq '32' -and $VerifyTotal.Groups[2].Value -ceq '0' -and $VerifyTotal.Groups[3].Value -ceq '0' -and $VerifyTotal.Groups[4].Value -ceq '0')
    }
    Require-Verify ($VerifyOutput -notmatch 'FAILED|<<< ERROR')
    $script:verifyFacts.executedCases = 32; $script:verifyFacts.observedLocks = 26
}
function Complete-VerifyConsumption([string]$VerifyState) {
    Require-Verify ($script:verifyClaimOwned -and $VerifyState -cin @('failed', 'incomplete', 'verified'))
    Assert-VerifyWritePath $script:verifyConsumptionPath
    $VerifyStream = $null
    try {
        $VerifyStream = [IO.File]::Open($script:verifyConsumptionPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $VerifyReader = New-Object IO.StreamReader($VerifyStream, (New-Object Text.UTF8Encoding($false, $true)), $false, 1024, $true)
        try { $VerifyClaim = $VerifyReader.ReadToEnd() | ConvertFrom-Json } finally { $VerifyReader.Dispose() }
        Require-Verify ($VerifyClaim.newRunId -ceq $verifyNewRunId -and $VerifyClaim.scope -ceq $verifyScope -and $VerifyClaim.sourceCommit -ceq $verifyContext.Manifest.sourceCommit -and $VerifyClaim.state -ceq 'intent')
        $VerifyClaim.state = $VerifyState
        $VerifyClaim | Add-Member -NotePropertyName completedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
        $VerifyText = $VerifyClaim | ConvertTo-Json -Depth 6
        Assert-VerifyNoSecret $VerifyText
        Assert-VerifyWritePath $script:verifyConsumptionPath
        $VerifyBytes = (New-Object Text.UTF8Encoding($false, $true)).GetBytes($VerifyText)
        $VerifyStream.Position = 0; $VerifyStream.Write($VerifyBytes, 0, $VerifyBytes.Length); $VerifyStream.SetLength($VerifyBytes.Length); $VerifyStream.Flush($true)
        $VerifyStream.Position = 0
        $VerifyCheck = New-Object byte[] $VerifyBytes.Length
        Require-Verify ($VerifyStream.Read($VerifyCheck, 0, $VerifyCheck.Length) -eq $VerifyCheck.Length)
        Require-Verify ([Text.Encoding]::UTF8.GetString($VerifyCheck) -ceq $VerifyText)
        $script:verifyFacts.consumptionFinalized = $true
    } finally { if ($VerifyStream) { $VerifyStream.Dispose() } }
}
function Write-VerifySummary {
    Assert-VerifyWritePath $verifyEvidenceRoot
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
    Assert-VerifyWritePath $VerifySummaryPath
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
$script:verifyClaimOwned = $false
$script:verifyConsumptionPath = $null
$script:verifySummaryWritten = $false
$script:verifyCompleteDriverRun = $false
$script:verifyFacts.consumptionFinalized = $false
$script:verifyFacts.sourceExportVerified = $false

try {
    Invoke-VerifyGate 'fixture-identity-and-ownership' {
        Require-Verify ($verifyNewRunId -cmatch '^[0-9a-f]{32}$' -and $verifyNewRunId -cne ('0' * 32))
        Require-Verify ([IO.Path]::IsPathRooted($verifyFixturePath) -and [IO.Path]::IsPathRooted($verifyEvidenceRoot))
        $script:verifyContext = Read-OnboardingFixtureContext $verifyFixturePath $verifyNewRunId
        Require-Verify ($verifyContext.Manifest.state -ceq 'running')
        $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
        $VerifyEvidenceBase = Join-Path (Get-OnboardingProjectRoot) '.local/evidence'
        Require-Verify ($VerifyEvidenceFull.StartsWith($verifyContext.Root + '\', [StringComparison]::OrdinalIgnoreCase) -or $VerifyEvidenceFull.StartsWith($VerifyEvidenceBase + '\', [StringComparison]::OrdinalIgnoreCase))
        Assert-OnboardingPathChain $VerifyEvidenceFull
        Assert-OnboardingPrivateParent $VerifyEvidenceFull
        $VerifyAccountsPath = Join-Path $verifyContext.Root 'accounts.json'
        Assert-OnboardingPathChain $VerifyAccountsPath
        Assert-OnboardingPrivateParent $VerifyAccountsPath
        $VerifyAccountsRaw = [IO.File]::ReadAllText($VerifyAccountsPath) | ConvertFrom-Json
        $script:verifyAccounts = @($VerifyAccountsRaw)
        Require-Verify ($script:verifyAccounts.Count -ge 2)
        foreach ($VerifyAccount in $script:verifyAccounts) {
            Require-Verify ($VerifyAccount.username -and $VerifyAccount.password -and $VerifyAccount.role)
            Add-VerifyCredential ([string]$VerifyAccount.password)
        }
        Require-Verify (@($script:verifyAccounts | Where-Object { $_.role -ceq 'PLATFORM_ADMIN' }).Count -ge 1)
        $VerifyCredentialsPath = Join-Path $verifyContext.Root 'credentials.json'
        Assert-OnboardingPathChain $VerifyCredentialsPath; Assert-OnboardingPrivateParent $VerifyCredentialsPath
        $VerifyCredentials = [IO.File]::ReadAllText($VerifyCredentialsPath) | ConvertFrom-Json
        foreach ($VerifyProperty in $VerifyCredentials.PSObject.Properties) { if ($VerifyProperty.Value -is [string] -and $VerifyProperty.Value.Length -gt 0) { Add-VerifyCredential ([string]$VerifyProperty.Value) } }
    }
    Invoke-VerifyGate 'consumption-create-new' {
        $VerifyEvidenceFull = [IO.Path]::GetFullPath($verifyEvidenceRoot)
        Assert-VerifyWritePath $VerifyEvidenceFull
        [void][IO.Directory]::CreateDirectory($VerifyEvidenceFull)
        Assert-OnboardingPrivateParent $VerifyEvidenceFull
        # Intent-first: a fresh run must claim its evidence slot before any SQL or
        # login, so an interrupted run can never silently reuse a consumed fixture.
        $VerifyConsumptionPath = Join-Path $verifyContext.Root ($verifyNewRunId + '-hospital-onboarding-consumption.json')
        Assert-VerifyWritePath $VerifyConsumptionPath
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
        $script:verifyClaimOwned = $true
    }
    Invoke-VerifyGate 'source-commit-export' { Initialize-VerifySource }
    Invoke-VerifyGate 'mysql-32-case-manifest' {
        $VerifySchema = 'course_onboarding_test_' + $verifyNewRunId
        $VerifyDbUser = 'hobt_' + $verifyNewRunId.Substring(0, 12)
        $VerifyObserver = 'hobo_' + $verifyNewRunId.Substring(0, 12)
        $VerifyDbPassword = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
        $VerifyObserverPassword = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
        Add-VerifyCredential $VerifyDbPassword; Add-VerifyCredential $VerifyObserverPassword
        # Provision an isolated test schema (never touch the medical_cloud_course DB).
        $VerifyExisting = Invoke-VerifySql 'mysql' ("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + $VerifySchema + "';") 'verify-schema-absent'
        Require-Verify (([string]$VerifyExisting).Trim() -ceq '0')
        Invoke-VerifySql 'mysql' ('CREATE DATABASE ' + $VerifySchema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;') 'verify-schema-create'
        $VerifyDdl = Get-VerifySchemaDdl $VerifySchema
        # The shipped schema.sql carries its own CREATE DATABASE/USE; strip them and
        # feed only the table DDL into the already-created test schema.
        $VerifyDdl = $VerifyDdl -replace '(?m)^CREATE DATABASE[^;]*;', '' -replace '(?m)^USE[^;]*;', ''
        $VerifyDdl = 'USE ' + $VerifySchema + ';' + "`n" + $VerifyDdl
        Invoke-VerifySql 'mysql' $VerifyDdl 'verify-schema-apply'
        Invoke-VerifySql 'mysql' ('CREATE USER ''' + $VerifyDbUser + '''@''127.0.0.1'' IDENTIFIED BY ''' + $VerifyDbPassword + ''';') 'verify-user-create'
        Invoke-VerifySql 'mysql' ('CREATE USER ''' + $VerifyObserver + '''@''127.0.0.1'' IDENTIFIED BY ''' + $VerifyObserverPassword + ''';') 'verify-observer-create'
        Invoke-VerifySql 'mysql' ('GRANT SELECT,INSERT,UPDATE,DELETE ON ' + $VerifySchema + '.* TO ''' + $VerifyDbUser + '''@''127.0.0.1'';') 'verify-user-grant'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON ' + $VerifySchema + '.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-grant'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON information_schema.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-info'
        Invoke-VerifySql 'mysql' ('GRANT SELECT ON performance_schema.* TO ''' + $VerifyObserver + '''@''127.0.0.1'';') 'verify-observer-perf'
        $VerifyResult = Invoke-VerifyMaven $VerifySchema @{user = $VerifyDbUser; password = $VerifyDbPassword; observerUser = $VerifyObserver; observerPassword = $VerifyObserverPassword}
        Assert-VerifyNoSecret $VerifyResult.Output
        Require-Verify ($VerifyResult.ExitCode -eq 0)
        Assert-VerifyManifest $VerifyResult.Output
    }
    Invoke-VerifyGate 'four-api-smoke' {
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
        $null = Invoke-VerifyApi 'POST' 'platform/tenants' $VerifyNonPlatform.username @{hospitalName = 'Forbidden Verify Hospital'; username = 'forbidden_verify'; initialPassword = 'Vfy-1234-Forbidden'; operationKey = ([guid]::NewGuid().ToString())} 403
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
        $VerifyScanRoots = @($VerifyEvidenceFull, (Join-Path $verifyContext.Root 'paas-base/log'), (Join-Path $verifyContext.Root 'portal-base/logs'), (Join-Path $verifyContext.Root 'portal-base/work'), (Join-Path $verifyContext.Root 'portal-base/temp'), (Join-Path $verifyContext.Root 'paas-base/tmp'))
        foreach ($VerifyScanRoot in $VerifyScanRoots) {
            Assert-VerifyOwned
            Assert-OnboardingPathChain $VerifyScanRoot
            Require-Verify (Test-Path -LiteralPath $VerifyScanRoot -PathType Container)
            $VerifyScanFiles = @(Get-ChildItem -LiteralPath $VerifyScanRoot -Recurse -File)
            $VerifyTextRoot = $VerifyScanRoot -in @($VerifyEvidenceFull, (Join-Path $verifyContext.Root 'paas-base/log'), (Join-Path $verifyContext.Root 'portal-base/logs'))
            Require-Verify ($VerifyScanFiles.Count -le 512)
            foreach ($VerifyFile in $VerifyScanFiles) {
            Assert-OnboardingPathChain $VerifyFile.FullName
            Assert-OnboardingPrivateParent $VerifyFile.FullName
            Require-Verify ($VerifyFile.Length -le 16777216)
            $VerifyArtifactBytes = [IO.File]::ReadAllBytes($VerifyFile.FullName)
            Require-Verify ($VerifyArtifactBytes.Length -le 16777216)
            Assert-VerifyNoSecretBytes $VerifyArtifactBytes
            if ($VerifyTextRoot -and $VerifyFile.Name -match '\.(?:log(?:\.\d+)?|txt|jsonl?|xml|csv|html?)$') {
                $VerifyArtifactText = (New-Object Text.UTF8Encoding($false, $true)).GetString($VerifyArtifactBytes)
                Assert-VerifyNoSecret $VerifyArtifactText
            }
            }
        }
    }
} catch {
    if ($verifyActiveGate -in $verifyRequired -and $verifyGates[$verifyActiveGate] -ne 'failed') { Set-VerifyGate $verifyActiveGate 'failed' }
    $script:verifyFailureCode = 'REQUIRED_CHECK_FAILED'
}

# Strict summary: any required gate that is missing/skip/unverified forbids exit 0.
Invoke-VerifyGate 'strict-summary' {
    foreach ($VerifyName in $verifyRequired) {
        if ($VerifyName -ceq 'strict-summary') { continue }
        Require-Verify ($verifyGates.Contains($VerifyName) -and $verifyGates[$VerifyName] -cin @('passed', 'failed', 'unverified'))
    }
}
# Finalize consumption: only a fully resolved run marks the fixture as verified.
if ($script:verifyClaimOwned -and -not $script:verifyIdentityLost) {
    try {
        $VerifyFailed = @($verifyGates.Values | Where-Object { $_ -ceq 'failed' }).Count -gt 0
        $VerifyUnresolved = @($verifyRequired | Where-Object { $_ -cne 'consumption-finalization' -and $verifyGates[$_] -cne 'passed' }).Count -gt 0
        $VerifyState = if ($VerifyFailed) { 'failed' } elseif ($VerifyUnresolved) { 'incomplete' } else { 'verified' }
        Invoke-VerifyGate 'consumption-finalization' { Complete-VerifyConsumption $VerifyState }
        $script:verifyCompleteDriverRun = ($VerifyState -ceq 'verified')
    } catch { Set-VerifyGate 'consumption-finalization' 'failed'; $script:verifyFailureCode = 'CONSUMPTION_FINALIZATION_FAILED' }
}
if ($script:verifyIdentityLost) { Write-Output 'HOSPITAL ONBOARDING VERIFICATION ABORTED: owned identity lost; previous artifacts preserved'; $script:verifyExitCode = 3; return }
if (-not $script:verifyContext) { Write-Output 'HOSPITAL ONBOARDING VERIFICATION FAILED: fixture precondition'; $script:verifyExitCode = 1; return }
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
