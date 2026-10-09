$ErrorActionPreference = 'Stop'
$deploy = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$harness = Join-Path $deploy 'scripts/verify-audit.ps1'
$expected = @('committedSuccessVisible','rolledBackMutationHasNoSuccess','forcedSuccessInsertFailureRollsBackBusiness',
    'duplicateRequestEventIsRejected','failureStoresNoUntrustedObject','shanghaiMidnightRangeCorrect',
    'repeatPaymentKeepsTimestampAndNoChange','countAndRowsShareRepeatableReadSnapshot')
Describe 'real MySQL audit acceptance: missing or skipped tests never pass' {
    It 'executes all eight specified real database cases without skips' {
        (Test-Path -LiteralPath $harness) | Should Be $true
        & $harness -DatabaseOnly
        $reports = Join-Path $deploy '../paas/target/surefire-reports'
        [xml]$report = Get-Content -LiteralPath (Join-Path $reports 'TEST-com.medcloud.course.paas.MySqlAuditTransactionTest.xml') -Raw
        [int]$report.testsuite.tests | Should BeGreaterThan 7
        [int]$report.testsuite.skipped | Should Be 0
        [int]$report.testsuite.failures | Should Be 0
        [int]$report.testsuite.errors | Should Be 0
        foreach ($name in $expected) { @($report.testsuite.testcase | Where-Object name -eq $name).Count | Should Be 1 }
    }
}

Describe 'deployed HTTP/EJB audit acceptance' {
    It 'reconciles all thirteen real API events and isolated failures' -Skip:(-not $env:COURSE_AUDIT_FIXTURE) {
        $result = & $harness -PaasBaseUrl $env:COURSE_AUDIT_PAAS_URL -PortalBaseUrl $env:COURSE_AUDIT_PORTAL_URL -FixturePath $env:COURSE_AUDIT_FIXTURE
        [int]$result.events | Should BeGreaterThan 12
        [int]$result.failures | Should Be 0
        [int]$result.skipped | Should Be 0
        foreach ($event in @('LOGIN','TENANT_STATUS_CHANGE','DEPARTMENT_CREATE','DEPARTMENT_UPDATE','DOCTOR_CREATE','DOCTOR_UPDATE','SLOT_CREATE','SLOT_UPDATE','APPOINTMENT_BOOK','APPOINTMENT_CANCEL','CONSULTATION_START','CONSULTATION_COMPLETE','PAYMENT_PAY')) {
            @($result.eventNames | Where-Object { $_ -eq $event }).Count | Should Be 1
        }
    }
}

Describe 'deployed clinical audit rollback acceptance' {
    It 'rolls back book cancel start complete pay when success audit insertion fails' -Skip:(-not $env:COURSE_AUDIT_FIXTURE) {
        $result = & $harness -PaasBaseUrl $env:COURSE_AUDIT_PAAS_URL -PortalBaseUrl $env:COURSE_AUDIT_PORTAL_URL -FixturePath $env:COURSE_AUDIT_FIXTURE -FaultsOnly
        [int]$result.rollbackChecks | Should Be 5
        [int]$result.failures | Should Be 0
        [int]$result.skipped | Should Be 0
        foreach ($event in @('APPOINTMENT_BOOK','APPOINTMENT_CANCEL','CONSULTATION_START','CONSULTATION_COMPLETE','PAYMENT_PAY')) {
            @($result.faultEventNames | Where-Object { $_ -eq $event }).Count | Should Be 1
        }
    }
}
