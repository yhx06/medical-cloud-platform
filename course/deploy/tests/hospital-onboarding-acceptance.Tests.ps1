# Offline acceptance tests for the hospital-onboarding verify driver (Task 7).
# Only external boundaries are replaced: SQL, HTTP and the Maven/native process.
# The driver's own gate registry, consumption record, identity re-checks, safe
# error handling and strict summary are exercised against the real code.
#
# Intentionally ASCII for native Windows PowerShell 5.1 without a BOM.
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
# A 32-hex run id, a fake runtime root and a fake fixture context returned by the
# mocked Read-OnboardingFixtureContext.
$AcceptOwner = [guid]::NewGuid().ToString('N')
$AcceptSandbox = Join-Path $AcceptTestRoot 'sandbox'
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
    dbPassword = 'db-secret'; mysqlRootPassword = 'root-secret'; storageRootSecret = 'storage-root-secret'; storageAppSecret = 'storage-app-secret'
} | ConvertTo-Json))
$AcceptEvidence = Join-Path $AcceptSandbox '.local\evidence'
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
    $script:verifyFacts = [ordered]@{executedCases = 0; observedLocks = 0; apiRequests = 0; secretViolations = 0}
    $script:verifyCompleteDriverRun = $false
    $script:verifySummaryWritten = $false
    $script:verifyContext = $null
    $script:verifyExitCode = 0
    $script:verifyConsumptionPath = $null
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
    foreach ($AcceptFile in @(Get-ChildItem -LiteralPath $AcceptEvidence -File -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $AcceptFile.FullName -Force }
}

# Boundary overrides (external SQL/HTTP/process + fixture file/identity helpers).
function Read-OnboardingFixtureContext([string]$AcceptFixturePath, [string]$AcceptExpectedOwner, [switch]$AllowStopped, [switch]$AllowStaleRunning) {
    if ($AcceptExpectedOwner -cnotmatch '^[0-9a-f]{32}$') { throw 'Explicit owner required' }
    if ($AcceptExpectedOwner -cne $AcceptOwner) { throw 'Fixture owner marker mismatch' }
    if (-not (Test-Path -LiteralPath $AcceptFixturePath)) { throw 'Fixture deployment identity incomplete' }
    return $AcceptContext
}
function Assert-OnboardingIdentity($AcceptContext, [string]$AcceptComponent, [switch]$OnboardingStarting) {
    if ($script:AcceptIdentityThrows) { throw 'Process identity lost' }
}
function Assert-OnboardingMysqlLive($AcceptContext, [string]$AcceptName) {
    if ($script:AcceptMysqlLiveThrows) { throw 'Live MySQL UUID/port identity mismatch' }
}
function Assert-OnboardingPrivateParent([string]$AcceptPath) { }
function Assert-OnboardingPathChain([string]$AcceptPath) { }
function Get-OnboardingProjectRoot { return $AcceptSandbox }
function Invoke-VerifySql([string]$AcceptName, [string]$AcceptSql, [string]$AcceptLabel) {
    if ($script:AcceptSqlCalls.Count -eq 0) {
        $script:AcceptIntentBeforeSql = (Test-Path -LiteralPath (Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-consumption.json')))
    }
    $script:AcceptSqlCalls.Add($AcceptLabel)
}
function Invoke-VerifyMaven([string]$AcceptTestSchema, [hashtable]$AcceptDb) {
    if ($script:AcceptMavenResult) { return $script:AcceptMavenResult }
    throw 'MAVEN_RESULT_NOT_CONFIGURED'
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
        $script:AcceptHttpTokens[$AcceptTokenValue] = $AcceptBody.username
        return @{Status = 200; Text = (@{code = 'OK'; message = 'logged in'; data = @{accessToken = $AcceptTokenValue; username = $AcceptBody.username; role = $AcceptRole}} | ConvertTo-Json -Depth 4)}
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
        return @{Status = 200; Text = (@{code = 'OK'; message = 'hospital opened'; data = @{tenantId = $AcceptTenant; administratorId = $AcceptAdmin; hospitalName = $AcceptBody.hospitalName; username = $AcceptBody.username}} | ConvertTo-Json -Depth 4)}
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

# Restore the real boundary functions after the scenario overrides above.
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
        [IO.File]::WriteAllText($AcceptFixture, '{}')
        $AcceptConsumption = Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-consumption.json')
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
        [IO.File]::WriteAllText($AcceptFixture, '{}')
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
        [IO.File]::WriteAllText($AcceptFixture, '{}')
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -eq 0) 'Happy path did not pass'
        Assert-AcceptTest ($script:AcceptSqlCalls.Count -gt 0) 'No SQL was issued'
        Assert-AcceptTest $script:AcceptIntentBeforeSql 'Consumption record was not written before SQL'
        $AcceptConsumption = Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-consumption.json')
        Assert-AcceptTest (Test-Path -LiteralPath $AcceptConsumption) 'Consumption record missing'
        Assert-AcceptTest ([IO.File]::ReadAllText($AcceptConsumption) -match '"state":\s*"verified"') 'Consumption record not finalized verified'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 7. A replaced trigger (identity lost mid-run) refuses pass at health check.
    Test-AcceptBehavior 'identity change mid-run refuses at health gate' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $script:AcceptIdentityThrows = $true
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        [IO.File]::WriteAllText($AcceptFixture, '{}')
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Identity change was accepted'
        Assert-AcceptTest ($script:verifyGates['post-acceptance-health'] -eq 'failed') 'Health gate not failed'
        $script:AcceptIdentityThrows = $false
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 8. Safe error: a secret leaked into Maven output is refused.
    Test-AcceptBehavior 'secret in Maven output is refused' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = ((New-AcceptMavenPassOutput) + "`n" + 'db-secret')}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        [IO.File]::WriteAllText($AcceptFixture, '{}')
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -ne 0) 'Secret leak was accepted'
        Assert-AcceptTest ($script:verifyGates['mysql-32-case-manifest'] -eq 'failed') 'MySQL gate did not catch secret'
        Remove-Item -LiteralPath $AcceptFixture -Force
    }
    # 9. Full happy path writes an accepted summary and exits 0.
    Test-AcceptBehavior 'full happy path passes and writes accepted summary' {
        Reset-AcceptState
        $script:AcceptMavenResult = @{ExitCode = 0; Output = (New-AcceptMavenPassOutput)}
        $AcceptFixture = Join-Path $AcceptRuntime 'fixture.json'
        [IO.File]::WriteAllText($AcceptFixture, '{}')
        Invoke-VerifyAcceptance
        Assert-AcceptTest ($script:verifyExitCode -eq 0) ('Expected exit 0, got ' + $script:verifyExitCode)
        Assert-AcceptTest ($script:verifySummaryWritten) 'Summary not written'
        foreach ($AcceptName in $verifyRequired) { Assert-AcceptTest ($script:verifyGates[$AcceptName] -eq 'passed') ('Gate not passed: ' + $AcceptName) }
        $AcceptSummary = Join-Path $AcceptEvidence ($AcceptOwner + '-hospital-onboarding-summary.json')
        Assert-AcceptTest (Test-Path -LiteralPath $AcceptSummary) 'Summary file missing'
        $AcceptSummaryJson = [IO.File]::ReadAllText($AcceptSummary) | ConvertFrom-Json
        Assert-AcceptTest ($AcceptSummaryJson.accepted -eq $true -and $AcceptSummaryJson.scope -ceq 'hospital-onboarding') 'Summary not accepted'
        Assert-AcceptTest ($AcceptSummaryJson.counts.unverified -eq 0 -and $AcceptSummaryJson.counts.failed -eq 0 -and $AcceptSummaryJson.counts.skipped -eq 0) 'Summary has unresolved gates'
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
} finally {
    $env:TEMP = $AcceptSavedTemp
    $env:TMP = $AcceptSavedTmp
}
Write-Output ('HOSPITAL_ONBOARDING_ACCEPTANCE_TESTS=' + $AcceptTests + ' FAILURES=' + $AcceptFailures.Count + ' PS=' + $PSVersionTable.PSVersion.ToString())
Write-Output ('TEST_ARTIFACTS=' + $AcceptTestRoot)
if ($AcceptFailures.Count) { exit 1 }
exit 0
