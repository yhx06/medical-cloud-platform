$ErrorActionPreference = 'Stop'
$OnboardingSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$OnboardingProject = Split-Path (Split-Path $OnboardingSource -Parent) -Parent
$OnboardingTestRoot = Join-Path $OnboardingProject ('.local/tests/hob-fixture-' + [guid]::NewGuid().ToString('N'))
$OnboardingSavedTemp = $env:TEMP
$OnboardingSavedTmp = $env:TMP
$OnboardingTests = 0
$OnboardingFailures = New-Object 'Collections.Generic.List[string]'
[IO.Directory]::CreateDirectory($OnboardingTestRoot) | Out-Null
$env:TEMP = $OnboardingTestRoot
$env:TMP = $OnboardingTestRoot
function Test-OnboardingBehavior([string]$Name,[scriptblock]$Action) {
    $script:OnboardingTests++
    try { & $Action; Write-Output ('PASS ' + $Name) }
    catch { $script:OnboardingFailures.Add($Name + ': ' + $_.Exception.Message); Write-Output ('FAIL ' + $Name + ': ' + $_.Exception.Message) }
}
function Assert-OnboardingTest([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-OnboardingTestThrows([scriptblock]$Action,[string]$Pattern='*') {
    $OnboardingCaught = $false
    try { & $Action | Out-Null } catch {
        $OnboardingCaught = $true
        if ($_.Exception.Message -notlike $Pattern) { throw ('Unexpected refusal: ' + $_.Exception.Message) }
    }
    if (-not $OnboardingCaught) { throw 'Unsafe operation was accepted' }
}
function New-OnboardingTestOptions {
    $OnboardingId = [guid]::NewGuid().ToString('N')
    $OnboardingSandbox = Join-Path $OnboardingTestRoot $OnboardingId.Substring(0,12)
    foreach ($OnboardingDir in @('.local/runtime','.local/builds','source','mysql','java/bin','tomcat/conf','tomcat/bin','wildfly/standalone/configuration','wildfly/bin/client','wildfly/modules/system/layers/base/com/mysql/main')) {
        [IO.Directory]::CreateDirectory((Join-Path $OnboardingSandbox $OnboardingDir)) | Out-Null
    }
    foreach ($OnboardingLeaf in @('mysql/mysqld.exe','mysql/mysql.exe','mysql/mysqladmin.exe','mysql/mysqldump.exe','java/bin/java.exe','java/bin/javac.exe','tomcat/bin/bootstrap.jar','tomcat/bin/tomcat-juli.jar','wildfly/jboss-modules.jar','wildfly/bin/jboss-cli.bat','wildfly/bin/client/jboss-cli-client.jar','minio.exe','mc.exe','mvn.cmd','mysql-connector-j.jar')) {
        [IO.File]::WriteAllText((Join-Path $OnboardingSandbox $OnboardingLeaf),'external-boundary-placeholder')
    }
    [IO.File]::WriteAllText((Join-Path $OnboardingSandbox 'tomcat/conf/server.xml'),'<Server port="8005"><Service><Connector port="8080" protocol="HTTP/1.1"/><Connector port="8009" protocol="AJP/1.3"/></Service></Server>')
    [IO.File]::WriteAllText((Join-Path $OnboardingSandbox 'wildfly/standalone/configuration/standalone.xml'),'<server xmlns="urn:jboss:domain:4.0"><profile><subsystem xmlns="urn:jboss:domain:datasources:4.0"><datasources><datasource jndi-name="java:/OldDS" pool-name="OldDS"/><drivers/></datasources></subsystem></profile><interfaces><interface name="management"><inet-address value="${jboss.bind.address.management:127.0.0.1}"/></interface><interface name="public"><inet-address value="${jboss.bind.address:127.0.0.1}"/></interface></interfaces><socket-binding-group name="standard-sockets" default-interface="public"><socket-binding name="http" port="${jboss.http.port:8080}"/><socket-binding name="management-http" interface="management" port="${jboss.management.http.port:9990}"/></socket-binding-group></server>')
    New-OnboardingTestZip (Join-Path $OnboardingSandbox 'wildfly/modules/system/layers/base/com/mysql/main/mysql-connector-j.jar') @{'META-INF/services/java.sql.Driver'='com.mysql.cj.jdbc.Driver';'com/mysql/cj/jdbc/Driver.class'='external-class-boundary'}
    return @{
        ProjectRoot=$OnboardingSandbox; SourceRoot=(Join-Path $OnboardingSandbox 'source');
        RuntimeRoot=(Join-Path $OnboardingSandbox ('.local/runtime/attachments-' + $OnboardingId));
        NewRunId=$OnboardingId; SourceCommit=('a' * 40); MySqlBin=(Join-Path $OnboardingSandbox 'mysql');
        JavaHome=(Join-Path $OnboardingSandbox 'java'); TomcatHome=(Join-Path $OnboardingSandbox 'tomcat'); WildFlyHome=(Join-Path $OnboardingSandbox 'wildfly');
        MinioExecutable=(Join-Path $OnboardingSandbox 'minio.exe'); MinioClientExecutable=(Join-Path $OnboardingSandbox 'mc.exe');
        MavenExecutable=(Join-Path $OnboardingSandbox 'mvn.cmd'); ConnectorJar=(Join-Path $OnboardingSandbox 'mysql-connector-j.jar')
    }
}
function New-OnboardingTestZip([string]$Path,[hashtable]$Entries) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $OnboardingZip = [IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($OnboardingName in $Entries.Keys) {
            $OnboardingStream = $OnboardingZip.CreateEntry($OnboardingName.Replace("`n",'~')).Open()
            try { $OnboardingBytes=[Text.Encoding]::UTF8.GetBytes($Entries[$OnboardingName]); $OnboardingStream.Write($OnboardingBytes,0,$OnboardingBytes.Length) } finally { $OnboardingStream.Dispose() }
        }
    } finally { $OnboardingZip.Dispose() }
    if (@($Entries.Keys|Where-Object{$_ -match "`n"}).Count) {
        $OnboardingZipBytes=[IO.File]::ReadAllBytes($Path)
        foreach ($OnboardingUnsafeName in @($Entries.Keys|Where-Object{$_ -match "`n"})) {
            $OnboardingNeedle=[Text.Encoding]::UTF8.GetBytes($OnboardingUnsafeName.Replace("`n",'~'))
            for ($OnboardingIndex=0;$OnboardingIndex -le $OnboardingZipBytes.Length-$OnboardingNeedle.Length;$OnboardingIndex++) {
                $OnboardingMatches=$true
                for ($OnboardingOffset=0;$OnboardingOffset -lt $OnboardingNeedle.Length;$OnboardingOffset++) { if ($OnboardingZipBytes[$OnboardingIndex+$OnboardingOffset] -ne $OnboardingNeedle[$OnboardingOffset]) { $OnboardingMatches=$false; break } }
                if ($OnboardingMatches) { for ($OnboardingOffset=0;$OnboardingOffset -lt $OnboardingNeedle.Length;$OnboardingOffset++) { if ($OnboardingNeedle[$OnboardingOffset] -eq 126) { $OnboardingZipBytes[$OnboardingIndex+$OnboardingOffset]=10 } } }
            }
        }
        [IO.File]::WriteAllBytes($Path,$OnboardingZipBytes)
    }
}
function Invoke-OnboardingLifecycleRegressions($OnboardingOptions) {
    $OnboardingFixturePath=Join-Path $OnboardingOptions.RuntimeRoot 'fixture.json'
    $OnboardingOriginalManifest=[IO.File]::ReadAllText($OnboardingFixturePath)
    $OnboardingOriginalJournal=[IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'journal.json'))
    function Reset-OnboardingLifecycle([string]$State='stopped') {
        $script:OnboardingProcesses=@{}
        $OnboardingM=ConvertTo-OnboardingMap ($OnboardingOriginalManifest|ConvertFrom-Json)
        $OnboardingJ=ConvertTo-OnboardingMap ($OnboardingOriginalJournal|ConvertFrom-Json)
        $OnboardingM.state=$State; $OnboardingJ.state=$State
        foreach($OnboardingN in @('mysql','mysqlMigration','storage','paas','portal')) {
            $OnboardingM[$OnboardingN].status='stopped'; $OnboardingJ.components[$OnboardingN].status='stopped'
        }
        Write-OnboardingPrivate $OnboardingFixturePath ($OnboardingM|ConvertTo-Json -Depth 24)
        Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingJ|ConvertTo-Json -Depth 24)
        return (Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped)
    }
    function Get-Process { param($Id,$ErrorAction) $script:OnboardingProcesses[[int]$Id] }
    function Start-Sleep { param($Milliseconds,$Seconds) }
    $OnboardingStartBoundary=${function:Invoke-OnboardingNative}
    function Invoke-OnboardingNative {
        param($OnboardingExecutable,$OnboardingArguments,$OnboardingWorkingDirectory,$OnboardingInput,$OnboardingLabel,[switch]$OnboardingStart)
        if ($OnboardingStart) {
            Assert-OnboardingTest ($env:TEMP -ceq (Join-Path $OnboardingWorkingDirectory 'temp') -and $env:TMP -ceq $env:TEMP -and $env:JAVA_HOME -ceq $OnboardingOptions.JavaHome) 'Restart child inherited foreign temp or Java home'
            foreach($OnboardingN in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_ARGS','MYSQL_PWD','COURSE_FOREIGN','MINIO_FOREIGN')) {
                Assert-OnboardingTest ($null -eq [Environment]::GetEnvironmentVariable($OnboardingN,'Process')) 'Restart child inherited unsafe configuration'
            }
            if ($OnboardingLabel -eq 'storage-start') { Assert-OnboardingTest ($env:MINIO_BROWSER -eq 'on' -and $env:MINIO_UPDATE -eq 'off') 'Restart lost owned storage runtime options' }
            if ($OnboardingLabel -eq 'storage-start' -and $script:OnboardingFailStorage) { throw 'injected storage start failure' }
        }
        if ($OnboardingLabel -like '*-stop') {
            $OnboardingJ=[IO.File]::ReadAllText((Join-Path $OnboardingWorkingDirectory 'journal.json'))|ConvertFrom-Json
            $OnboardingN=$OnboardingLabel.Replace('-stop','')
            [void]$script:OnboardingProcesses.Remove([int]$OnboardingJ.components.$OnboardingN.pid)
            return @{ExitCode=0;Output=''}
        }
        & $OnboardingStartBoundary @PSBoundParameters
    }
    Test-OnboardingBehavior 'stop refuses live starting child without listener and preserves its identity' {
        $OnboardingC=Reset-OnboardingLifecycle 'failed'
        $OnboardingP=$OnboardingC.Journal.components.portal
        $OnboardingP.status='starting'
        $script:OnboardingProcesses[[int]$OnboardingP.pid]=[pscustomobject]@{ProcessId=$OnboardingP.pid;CreationDate=$OnboardingP.created;ExecutablePath=$OnboardingP.executable;CommandLine=($OnboardingP.arguments -join ' ');Ports=@()}
        $OnboardingC.Manifest.portal.status='starting'
        Write-OnboardingPrivate $OnboardingFixturePath ($OnboardingC.Manifest|ConvertTo-Json -Depth 24)
        Save-OnboardingJournal $OnboardingC
        Assert-OnboardingTestThrows { Stop-OnboardingFixture $OnboardingOptions }
        $OnboardingAfter=[IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json
        Assert-OnboardingTest ($OnboardingAfter.state -ne 'stopped' -and $OnboardingAfter.portal.pid -eq $OnboardingP.pid -and $script:OnboardingProcesses.ContainsKey([int]$OnboardingP.pid)) 'Live startup child was lost or certified stopped'
        Assert-OnboardingTestThrows { Start-OnboardingFixture $OnboardingOptions }
    }
    Test-OnboardingBehavior 'stop reconciles disappeared starting child without replacement' {
        $OnboardingC=Reset-OnboardingLifecycle 'failed'
        $OnboardingC.Journal.components.portal.status='starting'; $OnboardingC.Manifest.portal.status='starting'
        Write-OnboardingPrivate $OnboardingFixturePath ($OnboardingC.Manifest|ConvertTo-Json -Depth 24)
        Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingC.Journal|ConvertTo-Json -Depth 24)
        Stop-OnboardingFixture $OnboardingOptions
        $OnboardingAfter=[IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json
        Assert-OnboardingTest ($OnboardingAfter.state -eq 'stopped' -and $OnboardingAfter.portal.status -eq 'stopped') 'Disappeared startup child was not reconciled'
    }
    Test-OnboardingBehavior 'unknown intent launch outcome is retained and cannot become stopped' {
        $OnboardingC=Reset-OnboardingLifecycle 'failed'
        foreach($OnboardingRecord in @($OnboardingC.Manifest.portal,$OnboardingC.Journal.components.portal)) { $OnboardingRecord.status='intent'; $OnboardingRecord.pid=0; $OnboardingRecord.created=$null }
        Write-OnboardingPrivate $OnboardingFixturePath ($OnboardingC.Manifest|ConvertTo-Json -Depth 24)
        Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingC.Journal|ConvertTo-Json -Depth 24)
        Assert-OnboardingTestThrows { Stop-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest (([IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json).state -ne 'stopped') 'Unknown launch outcome certified stopped'
    }
    foreach($OnboardingMode in @('success','storage-failure','success-existing','storage-failure-existing')) {
        Test-OnboardingBehavior ('restart isolates children and exactly restores absent and existing variables: '+$OnboardingMode) {
            [void](Reset-OnboardingLifecycle)
            $OnboardingNames=@('TEMP','TMP','JAVA_HOME','MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','MYSQL_PWD','COURSE_FOREIGN','MINIO_FOREIGN','HTTP_PROXY','HTTPS_PROXY','NO_PROXY','ALL_PROXY')
            $OnboardingSaved=@{}
            foreach($OnboardingN in $OnboardingNames) { $OnboardingSaved[$OnboardingN]=[Environment]::GetEnvironmentVariable($OnboardingN,'Process') }
            try {
                foreach($OnboardingN in $OnboardingNames) { [Environment]::SetEnvironmentVariable($OnboardingN,'sentinel','Process') }
                if ($OnboardingMode -notlike '*-existing') { foreach($OnboardingN in @('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')) { [Environment]::SetEnvironmentVariable($OnboardingN,$null,'Process') } }
                $script:OnboardingFailStorage=($OnboardingMode -like 'storage-failure*')
                $OnboardingCaught=$null
                try { Start-OnboardingFixture $OnboardingOptions } catch { $OnboardingCaught=$_ }
                foreach($OnboardingN in $OnboardingNames) {
                    $OnboardingExpected=if($OnboardingMode -notlike '*-existing' -and $OnboardingN -in @('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')){$null}else{'sentinel'}
                    Assert-OnboardingTest ([Environment]::GetEnvironmentVariable($OnboardingN,'Process') -ceq $OnboardingExpected) ('Restart did not restore '+$OnboardingN)
                }
                if ($script:OnboardingFailStorage) { Assert-OnboardingTest ($OnboardingCaught -and $OnboardingCaught.Exception.Message -eq 'injected storage start failure') 'Wrong restart failure boundary' }
                elseif ($OnboardingCaught) { throw $OnboardingCaught }
                if ($script:OnboardingFailStorage) {
                    Assert-OnboardingTestThrows { Stop-OnboardingFixture $OnboardingOptions } '*Unknown launch outcome*'
                    $OnboardingFailed=[IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json
                    Assert-OnboardingTest ($OnboardingFailed.storage.status -eq 'intent' -and $OnboardingFailed.mysql.status -eq 'running' -and $script:OnboardingProcesses.ContainsKey([int]$OnboardingFailed.mysql.pid)) 'Restart failure lost known or unknown child identity'
                } else {
                    Stop-OnboardingFixture $OnboardingOptions
                    Assert-OnboardingTest (([IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json).state -eq 'stopped') 'Restart could not be gracefully stopped'
                }
            } finally { foreach($OnboardingN in $OnboardingNames) { [Environment]::SetEnvironmentVariable($OnboardingN,$OnboardingSaved[$OnboardingN],'Process') }; $script:OnboardingFailStorage=$false }
        }
    }
    foreach($OnboardingOperation in @('start','stop')) {
        Test-OnboardingBehavior ('real '+$OnboardingOperation+' journal survives interruption before terminal manifest') {
            [void](Reset-OnboardingLifecycle)
            if ($OnboardingOperation -eq 'stop') { Start-OnboardingFixture $OnboardingOptions }
            $OnboardingRealWriter=${function:Write-OnboardingPrivate}
            $script:OnboardingInterrupted=$false
            function Write-OnboardingPrivate($OnboardingPath,$OnboardingText) {
                if ($script:OnboardingInterrupted) { throw 'injected persistence interruption' }
                & $OnboardingRealWriter $OnboardingPath $OnboardingText
                if ($OnboardingPath -eq (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json')) {
                    $OnboardingSaved=$OnboardingText|ConvertFrom-Json
                    if (($OnboardingOperation -eq 'start' -and $OnboardingSaved.components.mysql.status -eq 'running') -or
                        ($OnboardingOperation -eq 'stop' -and $OnboardingSaved.components.portal.status -eq 'stopped')) {
                        $script:OnboardingInterrupted=$true
                        throw 'injected persistence interruption'
                    }
                }
            }
            try {
                if ($OnboardingOperation -eq 'start') { Assert-OnboardingTestThrows { Start-OnboardingFixture $OnboardingOptions } '*injected persistence interruption*' }
                else { Assert-OnboardingTestThrows { Stop-OnboardingFixture $OnboardingOptions } '*injected persistence interruption*' }
            } finally { ${function:Write-OnboardingPrivate}=$OnboardingRealWriter }
            Assert-OnboardingTest $script:OnboardingInterrupted 'Interruption did not reach real lifecycle persistence'
            Assert-OnboardingTestThrows { Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped -AllowStaleRunning }
            Stop-OnboardingFixture $OnboardingOptions
            Assert-OnboardingTest (([IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json).state -eq 'stopped') 'Persisted interrupted transition was not recovered'
        }
    }
    foreach($OnboardingOperation in @('start','stop')) {
        Test-OnboardingBehavior ('explicit recovery accepts interrupted '+$OnboardingOperation+' and rejects unrecorded identity drift') {
            $OnboardingC=Reset-OnboardingLifecycle
            $OnboardingPrior=ConvertTo-OnboardingMap (($OnboardingC.Manifest|ConvertTo-Json -Depth 24)|ConvertFrom-Json)
            $OnboardingRecords=[ordered]@{}
            foreach($OnboardingN in @('mysql','mysqlMigration','storage','paas','portal')) {
                $OnboardingP=$OnboardingC.Journal.components[$OnboardingN]
                $OnboardingRecords[$OnboardingN]=@(@{status=$OnboardingP.status;pid=$OnboardingP.pid;created=$OnboardingP.created})
            }
            if ($OnboardingOperation -eq 'stop') {
                $OnboardingC.Manifest.state='running'; $OnboardingPrior.state='running'
                foreach($OnboardingN in @('mysql','mysqlMigration','storage','paas','portal')) {
                    $OnboardingC.Manifest[$OnboardingN].status='running'; $OnboardingPrior[$OnboardingN].status='running'
                    $OnboardingRecords[$OnboardingN][0].status='running'
                    $OnboardingRecords[$OnboardingN]+=@{status='stopped';pid=$OnboardingC.Journal.components[$OnboardingN].pid;created=$OnboardingC.Journal.components[$OnboardingN].created}
                }
                $OnboardingC.Journal.state='stopping'
            } else {
                $OnboardingC.Journal.state='starting'
                $OnboardingP=$OnboardingC.Journal.components.portal
                $OnboardingRecords.portal+=@{status='intent';pid=0;created=$null}
                $OnboardingRecords.portal+=@{status='starting';pid=59999;created='2026-01-01T00:00:00.0000000Z'}
                $OnboardingP.status='starting'; $OnboardingP.pid=59999; $OnboardingP.created='2026-01-01T00:00:00.0000000Z'
            }
            $OnboardingC.Journal.transition=@{version=1;operation=$OnboardingOperation;prior=$OnboardingPrior;records=$OnboardingRecords}
            Write-OnboardingPrivate $OnboardingFixturePath ($OnboardingC.Manifest|ConvertTo-Json -Depth 24)
            Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingC.Journal|ConvertTo-Json -Depth 24)
            Assert-OnboardingTestThrows { Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped -AllowStaleRunning }
            $OnboardingRecovered=Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped -AllowStaleRunning -RecoverLifecycle
            Assert-OnboardingTest ($OnboardingRecovered.Journal.state -eq ($OnboardingOperation+'ing').Replace('stoping','stopping')) 'Recovery lost journal authority'
            foreach($OnboardingField in @('pid','created','executable','arguments','extraPorts','clientExecutable','credentialFile','dataDirectory','url','schemaVersion','serverUuid')) {
                $OnboardingTampered=ConvertTo-OnboardingMap (($OnboardingC.Journal|ConvertTo-Json -Depth 24)|ConvertFrom-Json)
                $OnboardingTampered.components.mysql[$OnboardingField]=if($OnboardingField -eq 'pid'){12345}elseif($OnboardingField -eq 'extraPorts'){@(49199)}else{'tampered'}
                Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingTampered|ConvertTo-Json -Depth 24)
                Assert-OnboardingTestThrows { Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped -AllowStaleRunning -RecoverLifecycle }
            }
            foreach($OnboardingField in @('ownerId','runtimeRoot','sourceCommit','schemaVersion','tools','stage')) {
                $OnboardingTampered=ConvertTo-OnboardingMap (($OnboardingC.Journal|ConvertTo-Json -Depth 24)|ConvertFrom-Json)
                $OnboardingTampered[$OnboardingField]='tampered'
                Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingTampered|ConvertTo-Json -Depth 24)
                Assert-OnboardingTestThrows { Read-OnboardingFixtureContext $OnboardingFixturePath $OnboardingOptions.NewRunId -AllowStopped -AllowStaleRunning -RecoverLifecycle }
            }
            Write-OnboardingPrivate (Join-Path $OnboardingOptions.RuntimeRoot 'journal.json') ($OnboardingC.Journal|ConvertTo-Json -Depth 24)
            Stop-OnboardingFixture $OnboardingOptions
            Assert-OnboardingTest (([IO.File]::ReadAllText($OnboardingFixturePath)|ConvertFrom-Json).state -eq 'stopped') 'Interrupted transition could not be stopped'
        }
    }
    ${function:Invoke-OnboardingNative}=$OnboardingStartBoundary
}
try {
    $OnboardingEntry = Join-Path $PSScriptRoot '../scripts/new-hospital-onboarding-fixture.ps1'
    Test-OnboardingBehavior 'default entry exits 4 and creates no runtime' {
        $OnboardingBefore = @(Get-ChildItem -LiteralPath (Join-Path $OnboardingProject '.local/runtime') -ErrorAction SilentlyContinue).Count
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $OnboardingEntry 2>$null | Out-Null
        Assert-OnboardingTest ($LASTEXITCODE -eq 4) ('Expected native exit 4, actual ' + $LASTEXITCODE)
        Assert-OnboardingTest (@(Get-ChildItem -LiteralPath (Join-Path $OnboardingProject '.local/runtime') -ErrorAction SilentlyContinue).Count -eq $OnboardingBefore) 'Default created a runtime'
    }
    if (Test-Path -LiteralPath $OnboardingEntry) { . $OnboardingEntry -RuntimeRoot 'parameter-preserved' -SourceCommit ('b'*40) -NewRunId ('c'*32) }
    # Offline port allocation: never bind real listeners in this suite.
    $script:OnboardingTestPort=49000
    function Get-OnboardingPort($OnboardingUsed) { do { $script:OnboardingTestPort++ } while(-not $OnboardingUsed.Add($script:OnboardingTestPort)); $script:OnboardingTestPort }
    Test-OnboardingBehavior 'dot source preserves caller parameters and returns without execution' {
        Assert-OnboardingTest ($RuntimeRoot -eq 'parameter-preserved' -and $SourceCommit -ceq ('b'*40) -and $NewRunId -ceq ('c'*32)) 'Import clobbered public parameters'
    }
    Test-OnboardingBehavior 'array import preserves every account entry' {
        $OnboardingMapped=ConvertTo-OnboardingMap ('[{"username":"first","password":"one"},{"username":"second","password":"two"}]'|ConvertFrom-Json)
        $OnboardingItems=@($OnboardingMapped)
        Assert-OnboardingTest ($OnboardingItems.Count -eq 2 -and $OnboardingItems[0].username -ceq 'first' -and $OnboardingItems[1].username -ceq 'second') 'Array import lost entries'
    }
    Test-OnboardingBehavior 'new path accepts exact owner bound directory without creating it' {
        $OnboardingOptions=New-OnboardingTestOptions
        $OnboardingActual=Assert-OnboardingNewRuntime $OnboardingOptions
        Assert-OnboardingTest ($OnboardingActual -ceq $OnboardingOptions.RuntimeRoot) 'Returned a different runtime'
        Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Validation wrote runtime'
    }
    foreach ($OnboardingBad in @('',('0'*32),('A'*32),'not-an-id')) {
        Test-OnboardingBehavior ('invalid run ID refuses first write: ' + $OnboardingBad) {
            $OnboardingOptions=New-OnboardingTestOptions; $OnboardingOptions.NewRunId=$OnboardingBad
            Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
            Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Invalid ID wrote runtime'
        }
    }
    foreach ($OnboardingBad in @(('a'*39),('A'*40),'main',(('a'*40)+"`n"))) {
        Test-OnboardingBehavior ('nonfixed source refuses first write: ' + $OnboardingBad.Trim()) {
            $OnboardingOptions=New-OnboardingTestOptions; $OnboardingOptions.SourceCommit=$OnboardingBad
            Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
            Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Invalid commit wrote runtime'
        }
    }
    Test-OnboardingBehavior 'owner mismatch refuses first write' {
        $OnboardingOptions=New-OnboardingTestOptions; $OnboardingOptions.NewRunId=[guid]::NewGuid().ToString('N')
        Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Wrong owner wrote runtime'
    }
    Test-OnboardingBehavior 'existing runtime is preserved and refused' {
        $OnboardingOptions=New-OnboardingTestOptions; [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        $OnboardingSentinel=Join-Path $OnboardingOptions.RuntimeRoot 'sentinel'; [IO.File]::WriteAllText($OnboardingSentinel,'keep')
        Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest ([IO.File]::ReadAllText($OnboardingSentinel) -ceq 'keep') 'Existing runtime changed'
        Assert-OnboardingTest (@(Get-ChildItem -LiteralPath $OnboardingOptions.RuntimeRoot).Count -eq 1) 'Existing runtime received writes'
    }
    Test-OnboardingBehavior 'path escape refuses first write' {
        $OnboardingOptions=New-OnboardingTestOptions
        $OnboardingOptions.RuntimeRoot=Join-Path $OnboardingOptions.ProjectRoot ('attachments-'+$OnboardingOptions.NewRunId)
        Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Escaped path created'
    }
    Test-OnboardingBehavior 'unsafe parent ACL refuses first write' {
        $OnboardingOptions=New-OnboardingTestOptions; $OnboardingParent=Split-Path $OnboardingOptions.RuntimeRoot -Parent
        $OnboardingAcl=Get-Acl -LiteralPath $OnboardingParent
        $OnboardingAcl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier('S-1-1-0')),'Modify','ContainerInherit,ObjectInherit','None','Allow')))
        Set-Acl -LiteralPath $OnboardingParent -AclObject $OnboardingAcl
        Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Unsafe ACL allowed runtime write'
    }
    Test-OnboardingBehavior 'reparse parent refuses first write' {
        $OnboardingOptions=New-OnboardingTestOptions
        $OnboardingTarget=Join-Path $OnboardingOptions.ProjectRoot 'junction-target'; [IO.Directory]::CreateDirectory($OnboardingTarget)|Out-Null
        $OnboardingJunction=Join-Path $OnboardingOptions.ProjectRoot 'junction'
        # Double the OS attribute boundary: junction creation requires privileges
        # unavailable to offline runners. All path validation remains production.
        [IO.Directory]::CreateDirectory($OnboardingJunction) | Out-Null
        function Get-Item { param($LiteralPath,[switch]$Force)
            $OnboardingItem=Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force
            if ($LiteralPath -eq $OnboardingJunction) { return [pscustomobject]@{Attributes=[IO.FileAttributes]::ReparsePoint} }
            return $OnboardingItem
        }
        $OnboardingOptions.RuntimeRoot=Join-Path $OnboardingJunction ('attachments-'+$OnboardingOptions.NewRunId)
        Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions }
        Assert-OnboardingTest (@(Get-ChildItem -LiteralPath $OnboardingTarget).Count -eq 0) 'Reparse target received write'
        Remove-Item Function:\Get-Item
    }
    Test-OnboardingBehavior 'archive preserves UTF8 content and CRLF bytes' {
        $OnboardingOptions=New-OnboardingTestOptions; $OnboardingZipPath=Join-Path $OnboardingOptions.ProjectRoot 'safe.zip'; $OnboardingDestination=Join-Path $OnboardingOptions.ProjectRoot 'unpacked'
        $OnboardingText='line1'+"`r`n"+[char]0x533B+[char]0x9662+"`n"
        New-OnboardingTestZip $OnboardingZipPath @{'course/sample.sql'=$OnboardingText}
        Expand-OnboardingSource $OnboardingZipPath $OnboardingDestination
        Assert-OnboardingTest ([IO.File]::ReadAllText((Join-Path $OnboardingDestination 'course/sample.sql')) -ceq $OnboardingText) 'Archive changed encoding/newlines'
    }
    foreach ($OnboardingUnsafeEntry in @('../escape.sql','course/../../escape.sql',('course/bad'+"`n"+'.sql'),'course/CON.sql',('course/'+('long'*60)+'.java'))) {
        Test-OnboardingBehavior ('unsafe ZIP refuses all extraction: '+$OnboardingUnsafeEntry.Replace("`n",'<LF>')) {
            $OnboardingOptions=New-OnboardingTestOptions; $OnboardingZipPath=Join-Path $OnboardingOptions.ProjectRoot 'unsafe.zip'; $OnboardingDestination=Join-Path $OnboardingOptions.ProjectRoot 'unpacked'
            New-OnboardingTestZip $OnboardingZipPath @{'course/first.sql'='safe';$OnboardingUnsafeEntry='unsafe'}
            Assert-OnboardingTestThrows { Expand-OnboardingSource $OnboardingZipPath $OnboardingDestination }
            Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingDestination)) 'ZIP wrote before full entry validation'
        }
    }
    Test-OnboardingBehavior 'native result ignores stale LASTEXITCODE and transports UTF8 stdin' {
        $global:LASTEXITCODE=97
        $OnboardingNative=Invoke-OnboardingNative 'powershell.exe' @('-NoProfile','-NonInteractive','-Command','[Console]::InputEncoding=New-Object Text.UTF8Encoding($false); $s=[Console]::In.ReadToEnd(); [Console]::Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))); exit 7') $OnboardingTestRoot ([string][char]0x533B) 'native-test'
        Assert-OnboardingTest ($OnboardingNative.ExitCode -eq 7) 'Native exit was taken from stale global state'
        Assert-OnboardingTest ($OnboardingNative.Output -ceq '5Yy7') 'Native stdin was not UTF8'
    }
    # External boundaries below are replaced; filesystem/configuration/journal code remains real.
    $OnboardingRealNative=${function:Invoke-OnboardingNative}
    $OnboardingRealHash=${function:Get-OnboardingToolHash}
    function Get-OnboardingToolHash($OnboardingPath) { if($OnboardingPath -like '*mysql-connector*'){'e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9'}elseif($OnboardingPath -like '*mc.exe'){'c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157'}else{'1e534498e2468a4b82c2e98300a883492af3107e666af35aba9875ef7853162f'} }
    function Invoke-OnboardingNative {
        param($OnboardingExecutable,$OnboardingArguments,$OnboardingWorkingDirectory,$OnboardingInput,$OnboardingLabel,[switch]$OnboardingStart)
        $script:OnboardingCalls.Add([pscustomobject]@{Label=$OnboardingLabel;Arguments=@($OnboardingArguments);Working=$OnboardingWorkingDirectory;Input=$OnboardingInput})
        if ($script:OnboardingFailLabel -eq $OnboardingLabel) { return [pscustomobject]@{ExitCode=9;Output='private-native-error-must-not-escape'} }
        if ($OnboardingLabel -eq 'source-identity') { return [pscustomobject]@{ExitCode=0;Output=('a'*40)+"`n"} }
        if ($OnboardingLabel -eq 'mysql-version') { return [pscustomobject]@{ExitCode=0;Output='mysqld  Ver 8.0.45 for Win64'} }
        if ($OnboardingLabel -eq 'java-version') { return [pscustomobject]@{ExitCode=0;Output='javac 1.8.0_341'} }
        if ($OnboardingLabel -eq 'source-export') {
            $OnboardingOut=(@($OnboardingArguments|Where-Object{$_ -like '--output=*'})[0]).Substring(9)
            New-OnboardingTestZip $OnboardingOut @{'course/pom.xml'='<project/>'; 'course/deploy/schema.sql'='fresh007'; 'course/deploy/seed-demo.sql'='seed'; 'course/deploy/scripts/upgrade-hospital-onboarding-database.ps1'='# boundary'; 'course/deploy/scripts/upgrade-clinical-database.ps1'='# metadata'; 'course/deploy/scripts/upgrade-payment-reference-database.ps1'='# boundary'; 'course/deploy/scripts/upgrade-doctor-profile-database.ps1'='# boundary'; 'course/deploy/migrations/005_hospital_onboarding.sql'='migration005'; 'course/deploy/migrations/006_payment_reference.sql'='migration006'; 'course/deploy/migrations/007_doctor_profile.sql'='migration007'; 'course/deploy/mysql-module.xml'='<module/>'}
        }
        if ($OnboardingLabel -eq 'baseline-export') { return [pscustomobject]@{ExitCode=0;Output='exact004'} }
        if ($OnboardingLabel -eq 'compile') {
            foreach ($OnboardingModule in @('paas','portal')) {
                $OnboardingTarget=Join-Path $OnboardingWorkingDirectory ('course/'+$OnboardingModule+'/target'); [IO.Directory]::CreateDirectory($OnboardingTarget)|Out-Null
                $OnboardingEntries=if($OnboardingModule -eq 'paas'){@{'WEB-INF/lib/jbcrypt-0.4.jar'='jar';'WEB-INF/lib/mysql-connector-j-8.0.33.jar'='jar';'WEB-INF/classes/META-INF/persistence.xml'='<persistence/>';'WEB-INF/classes/com/medcloud/course/paas/api/CourseApplication.class'='class'}}else{@{'WEB-INF/web.xml'='<web-app/>';'WEB-INF/views/login.jsp'='jsp'}}
                New-OnboardingTestZip (Join-Path $OnboardingTarget ($OnboardingModule+'.war')) $OnboardingEntries
            }
        }
        return [pscustomobject]@{ExitCode=0;Output=''}
    }
    foreach ($OnboardingLabel in @('source-identity','source-export','baseline-export','java-version','compile')) {
        Test-OnboardingBehavior ('preflight '+$OnboardingLabel+' failure prevents all service starts and SQL') {
            $OnboardingOptions=New-OnboardingTestOptions; $script:OnboardingCalls=New-Object 'Collections.Generic.List[object]'; $script:OnboardingFailLabel=$OnboardingLabel
            Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions } '*stopped*'
            Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -match 'start|sql|init|migration' -and $_.Label -notmatch 'version'}).Count -eq 0) 'Preflight failure reached service/SQL'
            if ($OnboardingLabel -eq 'java-version') {
                Assert-OnboardingTest (-not (Test-Path -LiteralPath $OnboardingOptions.RuntimeRoot)) 'Toolchain failure wrote the runtime before preflight'
            } else {
                Assert-OnboardingTest (([IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'journal.json'))) -notlike '*private-native-error*') 'Native error leaked to journal'
                $OnboardingJournal=[IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'journal.json'))|ConvertFrom-Json
                Assert-OnboardingTest ($OnboardingJournal.ownerId -ceq $OnboardingOptions.NewRunId -and $OnboardingJournal.state -ceq 'failed') 'Failure identity/journal missing'
            }
            Assert-OnboardingTest ($env:TEMP -ceq $OnboardingTestRoot -and $env:TMP -ceq $OnboardingTestRoot) 'Environment not restored'
        }
    }
    ${function:Invoke-OnboardingNative}=$OnboardingRealNative
    ${function:Get-OnboardingToolHash}=$OnboardingRealHash
    Test-OnboardingBehavior 'owned identity refuses PID creation listener UUID and marker substitutions before mutation' {
        $OnboardingOptions=New-OnboardingTestOptions
        [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        [IO.File]::WriteAllText((Join-Path $OnboardingOptions.RuntimeRoot 'owner.id'),$OnboardingOptions.NewRunId)
        $OnboardingData=Join-Path $OnboardingOptions.RuntimeRoot 'mysql-data'; [IO.Directory]::CreateDirectory($OnboardingData)|Out-Null
        [IO.File]::WriteAllText((Join-Path $OnboardingData 'auto.cnf'),"[auto]`nserver-uuid=12345678-1234-1234-1234-123456789012`n")
        $OnboardingPart=@{pid=43210;created='2026-01-01T00:00:00.0000000Z';executable=$OnboardingOptions.MinioExecutable;port=49100;dataDirectory=$OnboardingData;serverUuid='12345678-1234-1234-1234-123456789012';status='running';arguments=@('--port=49100',('--datadir='+$OnboardingData))}
        $OnboardingContext=@{Root=$OnboardingOptions.RuntimeRoot;Options=$OnboardingOptions;Journal=@{components=@{mysql=$OnboardingPart}}}
        $script:OnboardingObserved=@{ProcessId=43210;CreationDate=[datetime]'2026-01-01T00:00:00Z';ExecutablePath=$OnboardingOptions.MinioExecutable;CommandLine='--port=49100 --datadir='+$OnboardingData}
        $script:OnboardingListener=43210
        function Get-OnboardingProcess($OnboardingProcessId) { [pscustomobject]$script:OnboardingObserved }
        function Get-OnboardingListeners($OnboardingPort) { [pscustomobject]@{OwningProcess=$script:OnboardingListener;LocalAddress='127.0.0.1';LocalPort=$OnboardingPort} }
        Assert-OnboardingIdentity $OnboardingContext 'mysql'
        foreach ($OnboardingField in @('ProcessId','CreationDate','ExecutablePath','CommandLine')) {
            $OnboardingOld=$script:OnboardingObserved[$OnboardingField]
            $script:OnboardingObserved[$OnboardingField]=if($OnboardingField -eq 'CreationDate'){[datetime]'2025-01-01Z'}elseif($OnboardingField -eq 'ProcessId'){99999}else{'wrong'}
            Assert-OnboardingTestThrows { Assert-OnboardingIdentity $OnboardingContext 'mysql' }
            $script:OnboardingObserved[$OnboardingField]=$OnboardingOld
        }
        $script:OnboardingListener=99999; Assert-OnboardingTestThrows { Assert-OnboardingIdentity $OnboardingContext 'mysql' }; $script:OnboardingListener=43210
        [IO.File]::WriteAllText((Join-Path $OnboardingData 'auto.cnf'),'server-uuid=00000000-0000-0000-0000-000000000000')
        Assert-OnboardingTestThrows { Assert-OnboardingIdentity $OnboardingContext 'mysql' }
        [IO.File]::WriteAllText((Join-Path $OnboardingOptions.RuntimeRoot 'owner.id'),'wrong')
        Assert-OnboardingTestThrows { Assert-OnboardingIdentity $OnboardingContext 'mysql' }
    }
    Test-OnboardingBehavior 'lost owner preserves last journal bytes' {
        $OnboardingOptions=New-OnboardingTestOptions
        [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        [IO.File]::WriteAllText((Join-Path $OnboardingOptions.RuntimeRoot 'owner.id'),'lost-owner')
        $OnboardingLast=Join-Path $OnboardingOptions.RuntimeRoot 'journal.json'; [IO.File]::WriteAllText($OnboardingLast,'last-owned-journal')
        Assert-OnboardingTestThrows { Save-OnboardingJournal @{Root=$OnboardingOptions.RuntimeRoot;Options=$OnboardingOptions;Journal=@{state='failed'}} }
        Assert-OnboardingTest ([IO.File]::ReadAllText($OnboardingLast) -ceq 'last-owned-journal') 'Last journal overwritten after ownership loss'
    }
    Test-OnboardingBehavior 'lost process preserves last journal bytes' {
        $OnboardingOptions=New-OnboardingTestOptions; [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        [IO.File]::WriteAllText((Join-Path $OnboardingOptions.RuntimeRoot 'owner.id'),$OnboardingOptions.NewRunId)
        $OnboardingLast=Join-Path $OnboardingOptions.RuntimeRoot 'journal.json'; [IO.File]::WriteAllText($OnboardingLast,'last-identity-journal')
        $OnboardingContext=@{Root=$OnboardingOptions.RuntimeRoot;Options=$OnboardingOptions;Journal=@{state='failed';components=@{storage=@{status='running';pid=999;port=49101;created='2026-01-01T00:00:00Z';executable=$OnboardingOptions.MinioExecutable;dataDirectory=(Join-Path $OnboardingOptions.RuntimeRoot 'storage-data');arguments=@('server')}}}}
        function Get-OnboardingProcess($OnboardingProcessId) { return $null }
        Assert-OnboardingTestThrows { Save-OnboardingJournal $OnboardingContext }
        Assert-OnboardingTest ([IO.File]::ReadAllText($OnboardingLast) -ceq 'last-identity-journal') 'Journal overwritten after PID identity loss'
    }
    Test-OnboardingBehavior 'partial IAM result refuses completion even when native exit is zero' {
        Assert-OnboardingTestThrows { Assert-OnboardingIamResult '{"added":{},"failed":{"users":[{"name":"fictional","error":"denied"}]}}' 'fictional' 'scoped' }
        Assert-OnboardingTestThrows { Assert-OnboardingIamResult '' 'fictional' 'scoped' }
        Assert-OnboardingTestThrows { Assert-OnboardingIamResult '{"added":{"users":["fictional"],"policies":["scoped"]},"failed":{}}' 'fictional' 'scoped' }
        Assert-OnboardingTestThrows { Assert-OnboardingIamResult '{"added":{"users":["fictional"],"policies":["scoped"],"userPolicies":[{"fictional":["other"]}]}}' 'fictional' 'scoped' }
        Assert-OnboardingIamResult "{`n`"skipped`":{},`n`"removed`":{},`n`"added`":{`"users`":[`"fictional`"],`"policies`":[`"scoped`"],`"userPolicies`":[{`"fictional`":[`"scoped`"]}]},`n`"failed`":{}}" 'fictional' 'scoped'
    }
    Test-OnboardingBehavior 'container configuration binds private ports and uses private DB credentials' {
        $OnboardingOptions=New-OnboardingTestOptions
        [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        Set-OnboardingPrivateAcl $OnboardingOptions.RuntimeRoot
        $OnboardingContext=@{Root=$OnboardingOptions.RuntimeRoot;Options=$OnboardingOptions;Ports=@{mysql=49101;portal=49102;paas=49103;management=49104;shutdown=49105};Secrets=@{dbUser='fixtureuser';dbPassword='fake-password';tomcatShutdown='fictional-stop-token'};Driver='mysql'}
        New-OnboardingContainerConfiguration $OnboardingContext
        [xml]$OnboardingTomcat=[IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'portal-base/conf/server.xml'))
        Assert-OnboardingTest ($OnboardingTomcat.Server.port -eq '49105' -and $OnboardingTomcat.Server.address -eq '127.0.0.1' -and $OnboardingTomcat.Server.shutdown -eq 'fictional-stop-token' -and @($OnboardingTomcat.Server.Service.Connector).Count -eq 1 -and $OnboardingTomcat.Server.Service.Connector.address -eq '127.0.0.1' -and $OnboardingTomcat.Server.Service.Connector.port -eq '49102') 'Tomcat exposed old/default listeners or lost graceful stop'
        [xml]$OnboardingWildfly=[IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'paas-base/configuration/standalone.xml'))
        $OnboardingDatasource=$OnboardingWildfly.SelectSingleNode("//*[local-name()='datasource']")
        Assert-OnboardingTest ($OnboardingDatasource.GetAttribute('jndi-name') -ceq 'java:/jdbc/MedicalCloudCourseDS') 'Wrong application datasource'
        Assert-OnboardingTest ($OnboardingDatasource.InnerText -like '*127.0.0.1:49101/medical_cloud_course*') 'Wrong database endpoint'
        Assert-OnboardingTest ($OnboardingDatasource.InnerText -like '*allowPublicKeyRetrieval=true*') 'MySQL 8 public key retrieval missing'
        $OnboardingDriver=$OnboardingWildfly.SelectSingleNode("//*[local-name()='driver' and @name='mysql']")
        Assert-OnboardingTest ($OnboardingDriver -and $OnboardingDriver.GetAttribute('module') -eq 'com.mysql') 'MySQL module driver not registered'
        $OnboardingContext.Journal=@{components=@{storage=@{internalEndpoint='http://127.0.0.1:49106';publicEndpoint='http://127.0.0.1:49106';bucket='fictional-bucket';prefix='course-attachments/'}}}
        $OnboardingContext.Secrets.storageAppKey='fictional-app'; $OnboardingContext.Secrets.storageAppSecret='fictional-secret'
        Set-OnboardingRuntimeEnvironment $OnboardingContext
        Assert-OnboardingTest ($env:COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS -ceq 'http://127.0.0.1:49102' -and $env:COURSE_ATTACHMENTS_TEMP_DIR -like '*attachments-temp' -and $env:COURSE_S3_BUCKET -ceq 'fictional-bucket' -and $env:MEDICAL_COURSE_PAAS_URL -ceq 'http://127.0.0.1:49103/paas/api') 'Portal/attachment runtime environment incomplete'
        foreach($OnboardingName in @('COURSE_ATTACHMENTS_ENABLED','COURSE_ATTACHMENTS_TEMP_DIR','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_INTERNAL_ENDPOINT','COURSE_S3_PUBLIC_ENDPOINT','COURSE_S3_REGION','COURSE_S3_BUCKET','COURSE_S3_PREFIX','COURSE_S3_ACCESS_KEY','COURSE_S3_SECRET_KEY','MEDICAL_COURSE_PAAS_URL')) { [Environment]::SetEnvironmentVariable($OnboardingName,$null,'Process') }
        Assert-OnboardingTest ([IO.File]::ReadAllText((Join-Path $OnboardingOptions.TomcatHome 'conf/server.xml')) -like '*8009*') 'Teacher configuration mutated'
    }
    Test-OnboardingBehavior 'IAM archive contains scoped policy and verified enabled user mapping' {
        $OnboardingOptions=New-OnboardingTestOptions; [IO.Directory]::CreateDirectory($OnboardingOptions.RuntimeRoot)|Out-Null
        Set-OnboardingPrivateAcl $OnboardingOptions.RuntimeRoot
        $OnboardingContext=@{Root=$OnboardingOptions.RuntimeRoot;Options=$OnboardingOptions;Secrets=@{storageAppKey='fictional-app';storageAppSecret='fictional-secret'};Bucket='medical-fixture-test';Prefix='course-attachments/'}
        $OnboardingArchive=New-OnboardingIamArchive $OnboardingContext
        $OnboardingZip=[IO.Compression.ZipFile]::OpenRead($OnboardingArchive)
        try {
            Assert-OnboardingTest ($OnboardingZip.Entries.Count -eq 3) 'Unexpected IAM records'
            $OnboardingReader=New-Object IO.StreamReader($OnboardingZip.GetEntry('iam-assets/users.json').Open()); try{$OnboardingUsers=$OnboardingReader.ReadToEnd()|ConvertFrom-Json}finally{$OnboardingReader.Dispose()}
            Assert-OnboardingTest ($OnboardingUsers.'fictional-app'.secretKey -ceq 'fictional-secret' -and $OnboardingUsers.'fictional-app'.status -ceq 'enabled') 'Wrong official user schema'
            $OnboardingReader=New-Object IO.StreamReader($OnboardingZip.GetEntry('iam-assets/user_mappings.json').Open()); try{$OnboardingMap=$OnboardingReader.ReadToEnd()|ConvertFrom-Json}finally{$OnboardingReader.Dispose()}
            $OnboardingReader=New-Object IO.StreamReader($OnboardingZip.GetEntry('iam-assets/policies.json').Open()); try{$OnboardingPolicies=$OnboardingReader.ReadToEnd()|ConvertFrom-Json}finally{$OnboardingReader.Dispose()}
            $OnboardingPolicy=$OnboardingPolicies.($OnboardingMap.'fictional-app'.policy)
            Assert-OnboardingTest ($OnboardingMap.'fictional-app'.version -eq 1 -and $OnboardingPolicy.Statement[2].Resource[0] -ceq 'arn:aws:s3:::medical-fixture-test/course-attachments/*') 'Application privilege escaped prefix'
        } finally { $OnboardingZip.Dispose() }
    }
    Test-OnboardingBehavior 'full creator sequences preflight five isolated processes migration and final evidence' {
        $OnboardingOptions=New-OnboardingTestOptions
        $script:OnboardingCalls=New-Object 'Collections.Generic.List[object]'
        $script:OnboardingProcesses=@{}; $script:OnboardingNextPid=50000
        function Get-OnboardingProcess($OnboardingProcessId) { $script:OnboardingProcesses[[int]$OnboardingProcessId] }
        function Get-OnboardingListeners($OnboardingPort) {
            foreach($OnboardingProcess in $script:OnboardingProcesses.Values) {
                if ($OnboardingProcess.Ports -contains [int]$OnboardingPort) { [pscustomobject]@{LocalAddress='127.0.0.1';LocalPort=$OnboardingPort;OwningProcess=$OnboardingProcess.ProcessId} }
            }
        }
        function Invoke-OnboardingHttp($OnboardingUri) { [pscustomobject]@{StatusCode=200} }
        function Get-OnboardingToolHash($OnboardingPath) { if($OnboardingPath -like '*mysql-connector*'){'e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9'}elseif($OnboardingPath -like '*mc.exe'){'c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157'}else{'1e534498e2468a4b82c2e98300a883492af3107e666af35aba9875ef7853162f'} }
        function Invoke-OnboardingNative {
            param($OnboardingExecutable,$OnboardingArguments,$OnboardingWorkingDirectory,$OnboardingInput,$OnboardingLabel,[switch]$OnboardingStart)
            Assert-OnboardingTest ($env:JAVA_HOME -ceq $OnboardingOptions.JavaHome -and $env:TEMP.StartsWith($OnboardingOptions.ProjectRoot+'\') -and $env:TMP -ceq $env:TEMP) 'Creation native boundary inherited foreign Java or temp'
            $script:OnboardingCalls.Add([pscustomobject]@{Label=$OnboardingLabel;Arguments=@($OnboardingArguments);Input=$OnboardingInput;Working=$OnboardingWorkingDirectory})
            if ($OnboardingLabel -eq 'source-identity') { return @{ExitCode=0;Output=('a'*40)+"`n"} }
            if ($OnboardingLabel -eq 'source-export') {
                $OnboardingOut=(@($OnboardingArguments|Where-Object{$_ -like '--output=*'})[0]).Substring(9)
                New-OnboardingTestZip $OnboardingOut @{'course/pom.xml'='<project/>'; 'course/deploy/schema.sql'='fresh007'; 'course/deploy/seed-demo.sql'='seed'; 'course/deploy/scripts/upgrade-hospital-onboarding-database.ps1'='# external upgrader'; 'course/deploy/scripts/upgrade-clinical-database.ps1'='# metadata'; 'course/deploy/scripts/upgrade-payment-reference-database.ps1'='# external upgrader'; 'course/deploy/scripts/upgrade-doctor-profile-database.ps1'='# external upgrader'; 'course/deploy/migrations/005_hospital_onboarding.sql'='migration005'; 'course/deploy/migrations/006_payment_reference.sql'='migration006'; 'course/deploy/migrations/007_doctor_profile.sql'='migration007'; 'course/deploy/mysql-module.xml'='<module/>'}
            }
            if ($OnboardingLabel -eq 'baseline-export') { return @{ExitCode=0;Output='exact004'} }
            if ($OnboardingLabel -eq 'mysql-version') { return @{ExitCode=0;Output='D:/mysql/mysqld.exe  Ver 8.0.45 for Win64 on x86_64 (MySQL Community Server - GPL)'} }
            if ($OnboardingLabel -eq 'java-version') { return @{ExitCode=0;Output='javac 1.8.0_341'} }
            if ($OnboardingLabel -eq 'compile') {
                foreach ($OnboardingModule in @('paas','portal')) {
                    $OnboardingTarget=Join-Path $OnboardingWorkingDirectory ('course/'+$OnboardingModule+'/target'); [IO.Directory]::CreateDirectory($OnboardingTarget)|Out-Null
                    $OnboardingEntries=if($OnboardingModule -eq 'paas'){@{'WEB-INF/lib/jbcrypt-0.4.jar'='bcrypt';'WEB-INF/classes/META-INF/persistence.xml'='<persistence><property name="hibernate.hbm2ddl.auto" value="validate"/></persistence>';'WEB-INF/classes/com/medcloud/course/paas/api/CourseApplication.class'='class'}}else{@{'WEB-INF/web.xml'='<web-app/>';'WEB-INF/views/login.jsp'='login'}}
                    New-OnboardingTestZip (Join-Path $OnboardingTarget ($OnboardingModule+'.war')) $OnboardingEntries
                }
                # Real builds emit probe WARs next to the deployable one; they must be ignored by name.
                New-OnboardingTestZip (Join-Path $OnboardingWorkingDirectory 'course/paas/target/attachment-sdk-structure.war') @{'probe'='not-deployable'}
            }
            if ($OnboardingLabel -like '*-init') {
                $OnboardingData=(@($OnboardingArguments|Where-Object{$_ -like '--datadir=*'})[0]).Substring(10)
                [IO.File]::WriteAllText((Join-Path $OnboardingData 'auto.cnf'),('server-uuid='+[guid]::NewGuid().ToString('D')))
            }
            if ($OnboardingStart) {
                if ($script:OnboardingCreationFailure -eq $OnboardingLabel) { throw 'injected creation service failure' }
                $script:OnboardingNextPid++
                $OnboardingJournal=[IO.File]::ReadAllText((Join-Path $OnboardingWorkingDirectory 'journal.json'))|ConvertFrom-Json
                Assert-OnboardingTest ($OnboardingJournal.stage -eq $OnboardingLabel) 'Start occurred before intent journal'
                $OnboardingPorts=@()
                foreach ($OnboardingArgument in $OnboardingArguments) {
                    if ($OnboardingArgument -match '^--port=(\d+)$|^127\.0\.0\.1:(\d+)$|^-Djboss\.(?:http|management.http)\.port=(\d+)$|^-Donboarding.portal.(?:shutdown.)?port=(\d+)$') { $OnboardingPorts+= [int](@($Matches.Values|Where-Object{$_ -match '^\d+$'})[0]) }
                }
                $script:OnboardingProcesses[$script:OnboardingNextPid]=[pscustomobject]@{ProcessId=$script:OnboardingNextPid;CreationDate=[datetime]'2026-01-01T00:00:00Z';ExecutablePath=$OnboardingExecutable;CommandLine=($OnboardingArguments -join ' ');Ports=$OnboardingPorts}
                if ($OnboardingLabel -eq 'paas-start') { [IO.File]::WriteAllText((Join-Path $OnboardingWorkingDirectory 'paas-base/deployments/paas.war.deployed'),'deployed') }
                return @{ExitCode=0;ProcessId=$script:OnboardingNextPid;Created='2026-01-01T00:00:00.0000000Z';Output=''}
            }
            if ($OnboardingLabel -like '*-identity') {
                $OnboardingComponent=$OnboardingLabel.Replace('-identity','')
                $OnboardingData=Join-Path $OnboardingWorkingDirectory ($OnboardingComponent+'-data')
                $OnboardingUuid=([IO.File]::ReadAllText((Join-Path $OnboardingData 'auto.cnf')) -split '=')[1]
                $OnboardingPort=(@($OnboardingArguments|Where-Object{$_ -like '--port=*'})[0]).Substring(7)
                $OnboardingHex=[BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($OnboardingData)).Replace('-','')
                return @{ExitCode=0;Output=$OnboardingUuid+"`t"+$OnboardingPort+"`t"+$OnboardingHex+"`tWin64`n"}
            }
            if ($OnboardingLabel -eq 'password-hash') { return @{ExitCode=0;Output='$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2'} }
            if ($OnboardingLabel -like '*-tables') { return @{ExitCode=0;Output="13`t1`n"} }
            if ($OnboardingLabel -like '*-reference') { return @{ExitCode=0;Output="1`n"} }
            if ($OnboardingLabel -like '*-doctorprofile') { return @{ExitCode=0;Output="2`n"} }
            if ($OnboardingLabel -eq 'iam-import') {
                $OnboardingOwner=[IO.File]::ReadAllText((Join-Path $OnboardingWorkingDirectory 'owner.id'))
                $OnboardingUser='app'+$OnboardingOwner.Substring(0,20); $OnboardingPolicy='onboarding-'+$OnboardingOwner.Substring(0,12)
                return @{ExitCode=0;Output=(@{skipped=@{};removed=@{};failed=@{};added=@{users=@($OnboardingUser);policies=@($OnboardingPolicy);userPolicies=@(@{$OnboardingUser=@($OnboardingPolicy)})}}|ConvertTo-Json -Depth 8)}
            }
            return @{ExitCode=0;Output=''}
        }
        $OnboardingCreationEnvironment=@{}
        foreach($OnboardingN in @('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS')) { $OnboardingCreationEnvironment[$OnboardingN]=[Environment]::GetEnvironmentVariable($OnboardingN,'Process'); [Environment]::SetEnvironmentVariable($OnboardingN,$null,'Process') }
        try { $OnboardingResult=New-OnboardingFixture $OnboardingOptions }
        finally {
            Test-OnboardingBehavior 'creation restores initially absent JWT and download origin variables' {
                Assert-OnboardingTest ($null -eq $env:MEDICAL_COURSE_JWT_SECRET -and $null -eq $env:COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS) 'Creation leaked initially absent runtime variables'
            }
            foreach($OnboardingN in $OnboardingCreationEnvironment.Keys) { [Environment]::SetEnvironmentVariable($OnboardingN,$OnboardingCreationEnvironment[$OnboardingN],'Process') }
        }
        $OnboardingFixture=[IO.File]::ReadAllText($OnboardingResult)|ConvertFrom-Json
        Assert-OnboardingTest ($OnboardingFixture.sourceCommit -ceq ('a'*40) -and $OnboardingFixture.schemaVersion -ceq '007' -and $OnboardingFixture.state -ceq 'running') 'Incomplete final manifest'
        $OnboardingAccounts=(Get-Content -LiteralPath (Join-Path (Split-Path $OnboardingResult -Parent) 'accounts.json') -Raw -Encoding UTF8|ConvertFrom-Json)
        Assert-OnboardingTest (@($OnboardingAccounts).Count -eq 7) 'Account file missing entries'
        foreach($OnboardingAccount in @($OnboardingAccounts)) { Assert-OnboardingTest ($OnboardingAccount.username -and $OnboardingAccount.role -and $OnboardingAccount.password) 'Account file lost credentials' }
        function Get-OnboardingProjectRoot { return $OnboardingOptions.ProjectRoot }
        $OnboardingRead=Read-OnboardingFixtureContext $OnboardingResult $OnboardingOptions.NewRunId
        Assert-OnboardingTest ($OnboardingRead.Options.MySqlBin -ceq $OnboardingOptions.MySqlBin -and $OnboardingRead.Journal.components.paas.port -eq $OnboardingFixture.paas.port) 'Read context changed fixed fixture endpoints'
        Assert-OnboardingTestThrows { Read-OnboardingFixtureContext $OnboardingResult ('1'*32) }
        Assert-OnboardingTest ($OnboardingFixture.mysql.port -ne $OnboardingFixture.mysqlMigration.port -and $OnboardingFixture.mysql.serverUuid -ne $OnboardingFixture.mysqlMigration.serverUuid) 'Migration did not use independent MySQL'
        Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -like '*-start'}).Count -eq 5) 'Did not start exactly five isolated processes'
        Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -eq 'mysqlMigration-schema' -and $_.Input -ceq 'exact004'}).Count -eq 1) 'Baseline was not exact exported 004'
        Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -eq 'migration-upgrade-005'}).Count -eq 1) 'Actual 005 updater boundary missing'
        Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -eq 'migration-upgrade-006'}).Count -eq 1) 'Actual 006 updater boundary missing'
        Assert-OnboardingTest (@($script:OnboardingCalls|Where-Object{$_.Label -eq 'migration-upgrade-007'}).Count -eq 1) 'Actual 007 updater boundary missing'
        $OnboardingSecrets=[IO.File]::ReadAllText((Join-Path $OnboardingOptions.RuntimeRoot 'credentials.json'))|ConvertFrom-Json
        foreach($OnboardingSecret in @($OnboardingSecrets.dbPassword,$OnboardingSecrets.mysqlRootPassword,$OnboardingSecrets.storageRootSecret,$OnboardingSecrets.storageAppSecret)) {
            Assert-OnboardingTest (($script:OnboardingCalls.Arguments -join ' ') -notlike ('*'+$OnboardingSecret+'*')) 'Secret escaped to argv'
        }
        Assert-OnboardingTest ($env:TEMP -ceq $OnboardingTestRoot) 'Full workflow did not restore TEMP'
        Invoke-OnboardingLifecycleRegressions $OnboardingOptions
        foreach($OnboardingFailure in @('storage-start','portal-start')) {
            Test-OnboardingBehavior ('creation restores absent and preexisting configuration after '+$OnboardingFailure+' failure') {
                $OnboardingOptions=New-OnboardingTestOptions
                $script:OnboardingProcesses=@{}; $script:OnboardingCreationFailure=$OnboardingFailure
                $OnboardingSaved=@{}
                $OnboardingNames=@('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD','JAVA_TOOL_OPTIONS','MAVEN_ARGS','MYSQL_PWD','TEMP','TMP','JAVA_HOME','HTTP_PROXY')
                foreach($OnboardingN in $OnboardingNames) { $OnboardingSaved[$OnboardingN]=[Environment]::GetEnvironmentVariable($OnboardingN,'Process') }
                try {
                    foreach($OnboardingN in $OnboardingNames) { [Environment]::SetEnvironmentVariable($OnboardingN,'sentinel','Process') }
                    if ($OnboardingFailure -eq 'portal-start') { foreach($OnboardingN in @('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')) { [Environment]::SetEnvironmentVariable($OnboardingN,$null,'Process') } }
                    Assert-OnboardingTestThrows { New-OnboardingFixture $OnboardingOptions } '*Onboarding setup stopped*'
                    foreach($OnboardingN in $OnboardingNames) {
                        $OnboardingExpected=if($OnboardingFailure -eq 'portal-start' -and $OnboardingN -in @('MEDICAL_COURSE_JWT_SECRET','COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS','COURSE_S3_SECRET_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')){$null}else{'sentinel'}
                        Assert-OnboardingTest ([Environment]::GetEnvironmentVariable($OnboardingN,'Process') -ceq $OnboardingExpected) ('Creation failure did not restore '+$OnboardingN)
                    }
                } finally { foreach($OnboardingN in $OnboardingNames) { [Environment]::SetEnvironmentVariable($OnboardingN,$OnboardingSaved[$OnboardingN],'Process') }; $script:OnboardingCreationFailure=$null }
            }
        }
        ${function:Invoke-OnboardingNative}=$OnboardingRealNative
    }
} finally {
    $env:TEMP=$OnboardingSavedTemp; $env:TMP=$OnboardingSavedTmp
}
Write-Output ('ONBOARDING_FIXTURE_TESTS='+$OnboardingTests+' FAILURES='+$OnboardingFailures.Count+' PS='+$PSVersionTable.PSVersion.ToString())
Write-Output ('TEST_ARTIFACTS='+$OnboardingTestRoot)
if ($OnboardingFailures.Count) { exit 1 }
exit 0
