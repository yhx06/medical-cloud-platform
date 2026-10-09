$ErrorActionPreference = 'Stop'
$deploy = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$harness = Join-Path $deploy 'scripts/verify-audit.ps1'
$expected = @('committedSuccessVisible','rolledBackMutationHasNoSuccess','forcedSuccessInsertFailureRollsBackBusiness',
    'duplicateRequestEventIsRejected','failureStoresNoUntrustedObject','shanghaiMidnightRangeCorrect',
    'repeatPaymentKeepsTimestampAndNoChange','countAndRowsShareRepeatableReadSnapshot')

Describe 'HTTP fixture preflight rejects resources before mutations' {
    # Removing the common UUID/ownership gate must expose HTTP login or SQL writes here.
    # Only external SQL/HTTP transports are controlled; execute the entire real entrypoint.
    BeforeAll {
        if (-not ('AuditPreflightHttpProbe' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
public sealed class AuditPreflightHttpProbe : IDisposable {
    readonly TcpListener listener;
    readonly Task worker;
    int requests;
    public int Port { get { return ((IPEndPoint)listener.LocalEndpoint).Port; } }
    public int Requests { get { return Volatile.Read(ref requests); } }
    public AuditPreflightHttpProbe() {
        listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
        worker = Task.Run(() => {
            try { while (true) {
                using (var client = listener.AcceptTcpClient()) {
                    Interlocked.Increment(ref requests);
                    using (var stream = client.GetStream()) {
                        var buffer = new byte[8192]; stream.ReadTimeout = 2000;
                        stream.Read(buffer, 0, buffer.Length);
                        var bytes = Encoding.ASCII.GetBytes("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: 22\r\nConnection: close\r\n\r\n{\"code\":\"BAD_REQUEST\"}");
                        stream.Write(bytes, 0, bytes.Length);
                    }
                }
            }} catch (SocketException) {} catch (ObjectDisposedException) {}
        });
    }
    public void Dispose() { listener.Stop(); worker.Wait(3000); }
}
'@
        }
        $guardCli = Join-Path $TestDrive 'sql-boundary.ps1'
        @'
$sql = @($args | Where-Object { $_ -like '--execute=*' })[0].Substring(10)
$global:AuditPreflightBoundary.queries.Add($sql)
$global:LASTEXITCODE = 0
if ($sql -ceq 'SELECT @@server_uuid,@@port,@@datadir') {
    return ($global:AuditPreflightBoundary.identity -join [char]9)
}
if ($sql -like 'SELECT COUNT(*) FROM tenant WHERE *') { return '1' }
if ($sql -like 'SELECT COUNT(*) FROM user_account WHERE *') {
    if ($global:AuditPreflightBoundary.mismatch -eq 'account' -and $sql.Contains("role='HOSPITAL_ADMIN' AND tenant_id='00000000-0000-4000-8000-000000000001'")) { return '0' }
    return '1'
}
if ($sql -like 'SELECT COUNT(*) FROM department WHERE *') {
    if ($global:AuditPreflightBoundary.mismatch -eq 'department' -and $sql.Contains("AND tenant_id='00000000-0000-4000-8000-000000000001'")) { return '0' }; return '1'
}
if ($sql -like 'SELECT COUNT(*) FROM doctor_profile WHERE *') {
    switch ($global:AuditPreflightBoundary.mismatch) {
        'doctor' { if ($sql.Contains("AND tenant_id='00000000-0000-4000-8000-000000000001'")) { return '0' } }
        'doctorDepartment' { if ($sql.Contains("AND department_id='00000000-0000-4000-8000-000000000020'")) { return '0' } }
        'doctorAccount' { if ($sql.Contains("AND user_id='00000000-0000-4000-8000-000000000013'")) { return '0' } }
    }; return '1'
}
if ($sql -notmatch '^SELECT ') { return '0' }
throw 'Unexpected SQL at controlled preflight boundary'
'@ | Set-Content -LiteralPath $guardCli -Encoding UTF8
    }
    $cases = @(
        @{label='department quote';field='departmentId';value="'";mismatch='';fault=$false},
        @{label='department semicolon';field='departmentId';value='; DROP TABLE tenant';mismatch='';fault=$false},
        @{label='department null';field='departmentId';value=$null;mismatch='';fault=$false},
        @{label='doctor quote';field='doctorUserId';value="'";mismatch='';fault=$false},
        @{label='doctor semicolon';field='doctorUserId';value='; DROP TABLE tenant';mismatch='';fault=$false},
        @{label='doctor null';field='doctorUserId';value=$null;mismatch='';fault=$false},
        @{label='department cross-hospital';field='';value='';mismatch='department';fault=$false},
        @{label='doctor cross-hospital';field='';value='';mismatch='doctor';fault=$false},
        @{label='doctor other directory';field='';value='';mismatch='doctorDepartment';fault=$false},
        @{label='doctor differs from fixture account';field='';value='';mismatch='doctorAccount';fault=$false},
        @{label='account cross-hospital';field='';value='';mismatch='account';fault=$false},
        @{label='fault branch malformed';field='doctorUserId';value="'";mismatch='';fault=$true},
        @{label='fault branch cross-hospital';field='';value='';mismatch='doctor';fault=$true},
        @{label='valid fixture reaches login';field='';value='';mismatch='';fault=$false;allow=$true}
    )
    It 'enforces the preflight boundary for <label>' -TestCases $cases {
        param($label,$field,$value,$mismatch,$fault,$allow=$false)
        $probe = New-Object AuditPreflightHttpProbe
        $savedPassword = $env:MYSQL_PWD
        try {
            $salt = '0123456789abcdef0123456789abcdef'
            $tenant = '00000000-0000-4000-8000-000000000001'
            $accounts = @{}; $index = 10
            foreach ($role in @('platform','hospital','hospitalOther','doctor','patient','patientOther')) {
                $accounts[$role] = @{id=('00000000-0000-4000-8000-'+$index.ToString('D12'));username=('qa_'+$role+'_'+$salt);password='fake-test-only'}; $index++
            }
            $url = 'http://127.0.0.1:'+$probe.Port
            $dataPath = Join-Path $TestDrive ('course-audit-acceptance-'+$salt+'/data')
            $fixture = @{tenantId=$tenant;tenantName=('course_audit_acceptance_'+$salt);otherTenantId='00000000-0000-4000-8000-000000000002';emptyTenantId='00000000-0000-4000-8000-000000000003';accounts=$accounts;resources=@{departmentId='00000000-0000-4000-8000-000000000020';doctorUserId=$accounts.doctor.id};database=@{host='127.0.0.1';port=$probe.Port;uuid='00000000-0000-4000-8000-000000000030';datadir=$dataPath;user='test-only';password='fake-test-only'};paasBaseUrl=($url+'/paas/api');portalBaseUrl=($url+'/portal')}
            if ($field) { $fixture.resources[$field] = $value }
            if ($mismatch -eq 'doctorAccount') { $fixture.resources.doctorUserId = '00000000-0000-4000-8000-000000000099' }
            $guardFixture = Join-Path $TestDrive 'guard-fixture.json'
            $fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $guardFixture -Encoding UTF8
            $originalHash = (Get-FileHash -LiteralPath $guardFixture).Hash
            $global:AuditPreflightBoundary = @{queries=(New-Object 'Collections.Generic.List[string]');identity=@($fixture.database.uuid,$probe.Port,$dataPath);mismatch=$mismatch}
            $failure = $null
            try { & $harness -PaasBaseUrl $fixture.paasBaseUrl -PortalBaseUrl $fixture.portalBaseUrl -FixturePath $guardFixture -MySqlCli $guardCli -FaultsOnly:$fault | Out-Null } catch { $failure = $_.Exception.Message }
            if ($allow) {
                # Positive control: a fail-all gate must not count as safe fixture validation.
                $probe.Requests | Should Be 1
                ($failure -like 'Actual login failed for fixture role platform*') | Should Be $true
                @($global:AuditPreflightBoundary.queries | Where-Object { $_ -like 'DROP TRIGGER IF EXISTS qa_audit_*' }).Count | Should Be 3
                @($global:AuditPreflightBoundary.queries | Where-Object { $_ -notmatch '^(SELECT |DROP TRIGGER IF EXISTS qa_audit_)' }).Count | Should Be 0
            } else {
                $probe.Requests | Should Be 0
                @($global:AuditPreflightBoundary.queries | Where-Object { $_ -notmatch '^SELECT ' }).Count | Should Be 0
                ($failure -match 'Dedicated (clinical directory fixture|audit account ownership|clinical resource ownership)') | Should Be $true
            }
            if ($field) { $global:AuditPreflightBoundary.queries.Count | Should Be 0 }
            (Get-FileHash -LiteralPath $guardFixture).Hash | Should Be $originalHash
            $env:MYSQL_PWD | Should Be $savedPassword
        } finally {
            $probe.Dispose()
            $env:MYSQL_PWD = $savedPassword
            Remove-Variable AuditPreflightBoundary -Scope Global -ErrorAction SilentlyContinue
        }
    }
}
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
