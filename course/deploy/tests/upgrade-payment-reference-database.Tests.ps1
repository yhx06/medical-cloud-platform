# Offline native Windows PowerShell 5 boundary suite. No database is contacted.
# Removing exact metadata comparison, preservation checks, or dump/exit gates
# must fail these real entry-point executions. Only the external clients double.
$ErrorActionPreference = 'Stop'
function Assert-Equal($actual,$expected,[string]$message) {
    if (($actual -join "`n") -cne ($expected -join "`n")) { throw $message }
}
function Save-State($state) { $state | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $env:MIGRATION_BOUNDARY_STATE -Encoding UTF8 }
function Load-State { Get-Content -LiteralPath $env:MIGRATION_BOUNDARY_STATE -Raw -Encoding UTF8 | ConvertFrom-Json }
if ($args.Count -and $args[0] -eq '--run-upgrade') {
    $previous = $env:MYSQL_PWD; $code = 0
    try { & $args[1] -MySqlCli $PSCommandPath -MySqlDumpCli $PSCommandPath -HostName 127.0.0.1 -Port 34077 -Database medical_cloud_course -ExpectedServerUuid 11111111-2222-4333-8444-555555555555 -BackupPath $args[2] }
    catch { Write-Output $_.Exception.Message; $code = 1 }
    if ($env:MYSQL_PWD -cne $previous) { Write-Output 'Password environment was not restored'; exit 91 }
    exit $code
}
if ($args.Count -and $args[0] -eq '--no-defaults') {
    $s = Load-State; $global:LASTEXITCODE = 0
    $dump = @($args | Where-Object { $_ -like '--result-file=*' })
    if ($dump.Count) {
        $s.dumps++; $s.events += 'backup'; Save-State $s
        foreach ($flag in @('--single-transaction','--quick','--set-gtid-purged=OFF')) { Assert-Equal ($flag -cin $args) $true 'Incomplete dump options' }
        Assert-Equal $args[-1] 'medical_cloud_course' 'Full database dump required'
        [IO.File]::WriteAllText($dump[0].Substring(14),$(if ($s.case -eq 'EmptyDump') { '' } else { 'synthetic full backup' }))
        if ($s.case -eq 'DumpFailure') { $global:LASTEXITCODE = 7 }; return
    }
    $execute = @($args | Where-Object { $_ -like '--execute=*' })
    if ($execute.Count) {
        $s.reads++; $q = $execute[0].Substring(10)
        if ($q -ceq 'SELECT DATABASE(), @@port, @@server_uuid') {
            Save-State $s
            if ($s.case -eq 'IdentityReadFailure') { $global:LASTEXITCODE = 9 }
            if ($s.case -eq 'WrongIdentity') { "medical_cloud_course`t34077`t99999999-2222-4333-8444-555555555555"; return }
            "medical_cloud_course`t34077`t11111111-2222-4333-8444-555555555555"; return
        }
        if ($q -match 'information_schema.TABLES' -and $q -match 'information_schema.REFERENTIAL_CONSTRAINTS') {
            foreach ($source in @('COLUMNS','STATISTICS','TRIGGERS','TABLE_CONSTRAINTS')) { if ($q -notmatch "information_schema\.$source") { throw 'Incomplete metadata SQL' } }
            $s.events += 'shape'; Save-State $s
            if ($s.case -eq 'MetadataReadFailure' -or ($s.ddl -and $s.case -eq 'PostReadFailure')) { $global:LASTEXITCODE = 9 }
            $s.shape; return
        }
        # Legacy count probes remain supported so RED demonstrates their actual
        # false acceptance, rather than failing because this double rejects SQL.
        if ($q -match 'information_schema') {
            Save-State $s
            $ref = [int](@($s.shape | Where-Object { $_ -match '^C:payment_order:.*:payment_reference:' }).Count -gt 0)
            $idx = [int](@($s.shape | Where-Object { $_ -match '^I:payment_order:uk_course_order_payment_reference:' }).Count -gt 0)
            $title = [int](@($s.shape | Where-Object { $_ -match '^C:doctor_profile:.*:title:' }).Count -gt 0)
            $bio = [int](@($s.shape | Where-Object { $_ -match '^C:doctor_profile:.*:bio:' }).Count -gt 0)
            if ($q -match "table_type='BASE TABLE'") {
                if ($s.version -eq '006') { "13:1:${ref}:$idx" } else { "13:1:${ref}:${title}:$bio" }
            } elseif ($s.version -eq '006') { "${ref}:$idx" } else { "${title}:$bio" }; return
        }
        if ($q -like '*protected-row-snapshot*') {
            # Verify the emitted query covers every independently modelled old
            # column, including complete record/doctor/order fields and all13 tables.
            foreach ($c in $s.snapshotColumns) {
                $p = $c -split ':'
                if (-not $q.Contains('HEX(CAST(`' + $p[1] + '` AS BINARY))') -or -not $q.Contains('FROM `' + $p[0] + '`')) { throw 'Incomplete preservation query' }
            }
            $s.events += 'rows'; Save-State $s
            if ($s.case -eq 'SnapshotReadFailure' -or ($s.ddl -and $s.case -eq 'PostSnapshotReadFailure')) { $global:LASTEXITCODE = 9 }
            $s.rows; return
        }
        if ($q -like '*protected-payment-invariants*') {
            foreach ($fragment in @('payment_reference','PAID','UNPAID','COUNT(DISTINCT BINARY payment_reference)','paid_at','DATE_FORMAT')) { if (-not $q.Contains($fragment)) { throw 'Incomplete payment invariant query' } }
            Save-State $s
            # MySQL >=8.0.22 rejects binary regex arguments. Model that native
            # client failure instead of manufacturing a successful zero count.
            if ($q -match 'REGEXP\s+BINARY\b') { $global:LASTEXITCODE = 9; "0`t0`t0"; return }
            if ($s.case -like 'Compat*') {
                $regex = [regex]::Match($q,"REGEXP\s+'([^']+)'")
                if (-not $regex.Success) { throw 'Unsupported payment regex expression' }
                $options = if ($q -match 'payment_reference COLLATE utf8mb4_bin REGEXP') { [Text.RegularExpressions.RegexOptions]::None } else { [Text.RegularExpressions.RegexOptions]::IgnoreCase }
                $badPaid = 0; $badUnpaid = 0; $paidReferences = @()
                foreach ($row in $s.paymentRows) {
                    $reference = $row.reference
                    if ($row.status -eq 'PAID') {
                        $invalid = $null -eq $reference
                        if ($null -ne $reference) {
                            $paidReferences += $reference
                            if (-not [regex]::IsMatch($reference,$regex.Groups[1].Value,$options)) { $invalid = $true }
                            if ($q.Contains('CHAR_LENGTH(payment_reference) <> 25') -and $reference.Length -ne 25) { $invalid = $true }
                            if ($q.Contains('OCTET_LENGTH(payment_reference) <> 25') -and [Text.Encoding]::UTF8.GetByteCount($reference) -ne 25) { $invalid = $true }
                            if ($q.Contains('BINARY payment_reference <> BINARY UPPER(payment_reference)') -and $reference -cne $reference.ToUpperInvariant()) { $invalid = $true }
                            if ($q.Contains('BINARY LEFT(payment_reference,13)') -and -not $reference.StartsWith(('PAY-' + $row.date + '-'),[StringComparison]::Ordinal)) { $invalid = $true }
                        }
                        if ($invalid) { $badPaid++ }
                    } elseif ($row.status -eq 'UNPAID' -and $null -ne $reference) { $badUnpaid++ }
                }
                $unique = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
                foreach ($reference in $paidReferences) { [void]$unique.Add($reference) }
                "$badPaid`t$badUnpaid`t$($paidReferences.Count - $unique.Count)"; return
            }
            if ($s.case -eq 'CompleteInvalidPaid' -or $s.case -eq 'BaselineInvalidPaid') { "1`t0`t0"; return }
            if ($s.case -eq 'PaymentReadFailure' -and $s.ddl) { $global:LASTEXITCODE = 9 }
            if (-not $s.ddl) { "0`t0`t0"; return }
            switch ($s.case) { 'InvalidPaid' { "1`t0`t0" }; 'InvalidUnpaid' { "0`t1`t0" }; 'DuplicateReference' { "0`t0`t1" }; default { "0`t0`t0" } }; return
        }
        if ($q -like '*protected-new-profile-null*') { Save-State $s; if ($s.case -eq 'NonNullNewProfile') { '1' } else { '0' }; return }
        throw 'Unexpected database read'
    }
    $s.ddl++; $s.events += 'ddl'
    Assert-Equal $s.dumps 1 'DDL requires exactly one backup'
    $sql = @($input) -join "`n"
    if ($s.version -eq '006') {
        if ($sql -notmatch 'ADD COLUMN payment_reference VARCHAR\(25\) NULL AFTER paid_at' -or $sql -notmatch 'UPDATE payment_order' -or $sql -notmatch 'UNIQUE \(payment_reference\)') { throw 'Unexpected006 DDL' }
    } else {
        if ($sql -notmatch 'ADD COLUMN title VARCHAR\(100\) NULL AFTER display_name' -or $sql -notmatch 'ADD COLUMN bio VARCHAR\(1000\) NULL AFTER title') { throw 'Unexpected007 DDL' }
    }
    $s.shape = $s.target
    if ($s.case -in @('PartialDdl','PartialRetry')) { $s.shape = @($s.target | Where-Object { $_ -notmatch $(if ($s.version -eq '006') { '^I:payment_order:uk_course_order_payment_reference:' } else { '^C:doctor_profile:6:bio:' }) }) }
    if ($s.case -eq 'PostShapeFailure') { $s.shape = @($s.target | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\)', 'C:tenant:2:name:varchar(99)' }) }
    if ($s.case -like 'Changed*') { $s.rows = @($s.rows | ForEach-Object { $_ -replace ('^D:' + $s.case.Substring(7) + ':'),('D:' + $s.case.Substring(7) + ':changed:') }) }
    Save-State $s
    if ($s.case -in @('PartialDdl','PartialRetry','DdlFailureComplete')) { $global:LASTEXITCODE = 8 }; return
}

$version = if ($args.Count -and $args[0] -eq '--suite007') { '007' } else { '006' }
$compatOnly = '--compat-only' -cin $args
$deploy = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = [IO.Path]::GetFullPath((Join-Path $deploy '../..'))
if ((Split-Path (Split-Path $project -Parent) -Leaf) -eq '.worktrees') { $project = Split-Path (Split-Path $project -Parent) -Parent }
$scratch = Join-Path $project ('.local/tests/exact-' + $version + '-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
$prior = @{}
foreach ($n in @('TEMP','TMP','MIGRATION_BOUNDARY_STATE','MEDICAL_COURSE_DB_USER','MEDICAL_COURSE_DB_PASSWORD','MYSQL_PWD')) { $prior[$n] = [Environment]::GetEnvironmentVariable($n,'Process') }
try {
$env:TEMP = $scratch; $env:TMP = $scratch; $env:MIGRATION_BOUNDARY_STATE = Join-Path $scratch 'state.json'
$env:MEDICAL_COURSE_DB_USER = 'offline_boundary'; $env:MEDICAL_COURSE_DB_PASSWORD = 'synthetic-secret'; $env:MYSQL_PWD = 'prior-synthetic'
# Independent SQL model from older tests, never production expected metadata.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'upgrade-clinical-database.Tests.ps1'),[ref]$tokens,[ref]$errors)
foreach ($name in @('Add-ColumnShape','Invoke-ModelDdl')) {
    $nodes = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name })
    if ($nodes.Count -ne 1) { throw 'Independent model missing' }; . ([scriptblock]::Create($nodes[0].Extent.Text))
}
$shape007 = @(Invoke-ModelDdl @() (Get-Content -LiteralPath (Join-Path $deploy 'schema.sql') -Raw -Encoding UTF8))
$shape006 = @($shape007 | Where-Object { $_ -notmatch '^C:doctor_profile:(5:title|6:bio):' })
$shape005 = @($shape006 | Where-Object { $_ -notmatch '^C:payment_order:8:payment_reference:|^I:payment_order:uk_course_order_payment_reference:' })
$baseline = if ($version -eq '006') { $shape005 } else { $shape006 }
$target = if ($version -eq '006') { $shape006 } else { $shape007 }
$upgrade = Join-Path $deploy $(if ($version -eq '006') { 'scripts/upgrade-payment-reference-database.ps1' } else { 'scripts/upgrade-doctor-profile-database.ps1' })
function Run-Case([string]$name) {
    $shape = @($baseline)
    if ($name -like 'Compat*' -and $name -ne 'CompatValidMigration') { $shape = $target }
    switch ($name) {
        'Complete' { $shape = $target }
        'CompleteInvalidPaid' { $shape = $target }
        'Later007' { $shape = $shape007 }
        'WrongType' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\)', 'C:tenant:2:name:varchar(99)' }) }
        'WrongNull' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\):NO:', 'C:tenant:2:name:varchar(100):YES:' }) }
        'WrongOrder' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:name:', 'C:tenant:4:name:' }) }
        'WrongDefault' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\):NO:NULL:', 'C:tenant:2:name:varchar(100):NO:VALUE:x:' }) }
        'WrongCollation' { $shape = @($shape | ForEach-Object { $_ -replace '^T:tenant:BASE TABLE:InnoDB:utf8mb4_unicode_ci$', 'T:tenant:BASE TABLE:InnoDB:utf8mb4_bin' }) }
        'WrongCharset' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:(.*):utf8mb4:utf8mb4_unicode_ci$', 'C:tenant:2:$1:ascii:ascii_bin' }) }
        'WrongEngine' { $shape = @($shape | ForEach-Object { $_ -replace '^T:tenant:BASE TABLE:InnoDB:', 'T:tenant:BASE TABLE:MyISAM:' }) }
        'WrongExtra' { $shape = @($shape | ForEach-Object { $_ -replace '^C:tenant:2:name:varchar\(100\):NO:NULL::', 'C:tenant:2:name:varchar(100):NO:NULL:extra:' }) }
        'MissingFK' { $shape = @($shape | Where-Object { $_ -notmatch '^F:department:fk_department_tenant:' }) }
        'ForeignFK' { $shape = @($shape | ForEach-Object { $_ -replace ':medical_cloud_course:RESTRICT:RESTRICT$', ':foreign:RESTRICT:RESTRICT' }) }
        'WrongFKRule' { $shape = @($shape | ForEach-Object { $_ -replace ':RESTRICT:RESTRICT$', ':RESTRICT:CASCADE' }) }
        'MissingIndex' { $shape = @($target | Where-Object { $_ -notmatch '^I:payment_order:uk_course_order_payment_reference:' }) }
        'Incomplete006' { $shape = @($shape006 | Where-Object { $_ -notmatch '^I:payment_order:uk_course_order_payment_reference:' }) }
        'NonuniqueIndex' { $shape = @($target | ForEach-Object { $_ -replace ':uk_course_order_payment_reference:0:', ':uk_course_order_payment_reference:1:' }) }
        'WrongIndexColumn' { $shape = @($target | ForEach-Object { $_ -replace ':BTREE:payment_reference:~:A$', ':BTREE:status:~:A' }) }
        'PrefixIndex' { $shape = @($target | ForEach-Object { $_ -replace ':BTREE:payment_reference:~:A$', ':BTREE:payment_reference:5:A' }) }
        'WrongIndexType' { $shape = @($target | ForEach-Object { $_ -replace ':BTREE:payment_reference:~:A$', ':HASH:payment_reference:~:A' }) }
        'WrongIndexOrder' { $shape = @($shape | ForEach-Object { $_ -replace ':BTREE:tenant_id:~:A,name:~:A$', ':BTREE:name:~:A,tenant_id:~:A' }) }
        'WrongAddedType' { $shape = @($target | ForEach-Object { $_ -replace 'payment_reference:varchar\(25\)', 'payment_reference:varchar(24)' -replace 'bio:varchar\(1000\)', 'bio:varchar(999)' }) }
        'Partial' { $shape = @($target | Where-Object { $_ -notmatch $(if ($version -eq '006') { '^I:payment_order:uk_course_order_payment_reference:' } else { '^C:doctor_profile:6:bio:' }) }) }
        'ExtraTable' { $shape += 'T:foreign_table:BASE TABLE:InnoDB:utf8mb4_unicode_ci' }
        'MissingTable' { $shape = @($shape | Where-Object { $_ -notmatch '^[TCIF]:audit_event:' }) }
        'Trigger' { $shape += 'X:TRIGGER:foreign_trigger' }
        'Check' { $shape += 'X:CONSTRAINT:tenant:foreign_check' }
    }
    $rows = @($baseline | Where-Object { $_ -like 'T:*' } | ForEach-Object { 'D:' + ($_ -split ':')[1] + ':original-private-synthetic' })
    $snapshotColumns = @($baseline | Where-Object { $_ -like 'C:*' } | ForEach-Object { $p = $_ -split ':'; $p[1] + ':' + $p[3] })
    $paymentRows = @(
        [pscustomobject]@{status='PAID';date='20261020';reference='PAY-20261020-ABCDEF012345'},
        [pscustomobject]@{status='PAID';date='00000000';reference='PAY-00000000-ABCDEF012346'},
        [pscustomobject]@{status='UNPAID';date='00000000';reference=$null}
    )
    switch ($name) {
        'CompatLowerPrefix' { $paymentRows[0].reference = 'pay-20261020-ABCDEF012345' }
        'CompatLowerHex' { $paymentRows[0].reference = 'PAY-20261020-abcdef012345' }
        'CompatBadDate' { $paymentRows[0].reference = 'PAY-20261399-ABCDEF012345' }
        'CompatShortSuffix' { $paymentRows[0].reference = 'PAY-20261020-ABCDEF01234' }
        'CompatLongSuffix' { $paymentRows[0].reference = 'PAY-20261020-ABCDEF0123456' }
        'CompatTrailingNewline' { $paymentRows[0].reference = "PAY-20261020-ABCDEF012345`n" }
        'CompatNonAscii' { $paymentRows[0].reference = 'PAY-20261020-' + [char]0xC1 + 'BCDEF012345' }
        'CompatNullPaid' { $paymentRows[0].reference = $null }
        'CompatNonNullUnpaid' { $paymentRows[2].reference = 'PAY-00000000-ABCDEF012347' }
        'CompatDuplicate' { $paymentRows += [pscustomobject]@{status='PAID';date='20261020';reference='PAY-20261020-ABCDEF012345'} }
    }
    Save-State ([pscustomobject]@{case=$name;version=$version;shape=$shape;target=$target;rows=$rows;snapshotColumns=$snapshotColumns;paymentRows=$paymentRows;reads=0;dumps=0;ddl=0;events=@()})
    $backup = Join-Path $scratch ($name + '.sql')
    if ($name -in @('ExistingBackup','Complete')) { [IO.File]::WriteAllText($backup,'existing backup') }
    $scriptPath = $upgrade
    if ($name -in @('Changed004Metadata','Changed005Metadata','Missing004Metadata','Missing005Metadata','CRLFMetadata')) {
        $copy = Join-Path $scratch $name
        New-Item -ItemType Directory -Path (Join-Path $copy 'scripts'),(Join-Path $copy 'migrations') -Force | Out-Null
        foreach ($source in @($upgrade,(Join-Path $deploy 'scripts/protected-migration-metadata.ps1'),(Join-Path $deploy 'scripts/upgrade-clinical-database.ps1'),(Join-Path $deploy 'scripts/upgrade-hospital-onboarding-database.ps1'))) {
            $leaf = Split-Path $source -Leaf
            if (($name -eq 'Missing004Metadata' -and $leaf -eq 'upgrade-clinical-database.ps1') -or ($name -eq 'Missing005Metadata' -and $leaf -eq 'upgrade-hospital-onboarding-database.ps1')) { continue }
            $text = [IO.File]::ReadAllText($source)
            if (($name -eq 'Changed004Metadata' -and $leaf -eq 'upgrade-clinical-database.ps1') -or ($name -eq 'Changed005Metadata' -and $leaf -eq 'upgrade-hospital-onboarding-database.ps1')) { $text = $text.Replace('varchar(100)','varchar(99)') }
            if ($name -eq 'CRLFMetadata') { $text = $text.Replace("`r`n","`n").Replace("`n","`r`n") }
            [IO.File]::WriteAllText((Join-Path (Join-Path $copy 'scripts') $leaf),$text)
        }
        Copy-Item -LiteralPath (Join-Path $deploy $(if ($version -eq '006') { 'migrations/006_payment_reference.sql' } else { 'migrations/007_doctor_profile.sql' })) -Destination (Join-Path $copy 'migrations')
        $scriptPath = Join-Path (Join-Path $copy 'scripts') (Split-Path $upgrade -Leaf)
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath --run-upgrade $scriptPath $backup 2>&1) -join "`n"; $exit = $LASTEXITCODE
        if ($name -eq 'PartialRetry') { $output += @(& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $PSCommandPath --run-upgrade $scriptPath $backup 2>&1) -join "`n"; $exit = $LASTEXITCODE }
    } finally { $ErrorActionPreference = $saved }
    [IO.File]::WriteAllText((Join-Path $scratch ($name + '.log')),$output)
    if ($output -match 'synthetic-secret|prior-synthetic|offline_boundary|original-private-synthetic') { throw 'Private row or credential leaked to output' }
    Assert-Equal ($exit -eq 91) $false 'Password finally restoration'
    return [pscustomobject]@{exit=$exit;state=(Load-State);backup=(Test-Path -LiteralPath $backup);backupText=$(if (Test-Path -LiteralPath $backup) { [IO.File]::ReadAllText($backup) } else { '' });output=$output}
}
$passed = 0; $failures = @()
function Test-Case([string]$name,[scriptblock]$body) { try { & $body; $script:passed++; Write-Host "PASS $name" } catch { $script:failures += "FAIL ${name}: $($_.Exception.Message)"; Write-Host $script:failures[-1] } }
    Test-Case 'CompatValidMigration' { $r = Run-Case 'CompatValidMigration'; Assert-Equal $r.exit 0 ('Valid populated baseline must upgrade: ' + $r.output); Assert-Equal $r.state.dumps 1 'One backup'; Assert-Equal $r.state.ddl 1 'One DDL' }
    Test-Case 'CompatValidComplete' { $r = Run-Case 'CompatValidComplete'; Assert-Equal $r.exit 0 ('Valid complete target must verify read-only: ' + $r.output); Assert-Equal $r.state.dumps 0 'No backup'; Assert-Equal $r.state.ddl 0 'Read-only' }
    foreach ($case in @('CompatLowerPrefix','CompatLowerHex','CompatBadDate','CompatShortSuffix','CompatLongSuffix','CompatTrailingNewline','CompatNonAscii','CompatNullPaid','CompatNonNullUnpaid','CompatDuplicate')) {
        Test-Case $case { $r = Run-Case $case; Assert-Equal ($r.exit -ne 0) $true 'Invalid payment data rejected'; Assert-Equal $r.state.dumps 0 'No backup'; Assert-Equal $r.state.ddl 0 'No DDL' }
    }
    if (-not $compatOnly) {
    Test-Case 'ExactBaseline' { $r = Run-Case 'ExactBaseline'; Assert-Equal $r.exit 0 ('Baseline must upgrade: ' + $r.output); Assert-Equal $r.state.dumps 1 'One backup'; Assert-Equal $r.state.ddl 1 'One DDL'; Assert-Equal ($r.state.shape | Sort-Object) ($target | Sort-Object) 'Exact target'; Assert-Equal $r.backup $true 'Backup retained'; Assert-Equal (($r.state.events -join ',') -match 'shape,rows,backup,ddl,shape,rows') $true 'Full metadata and preservation before dump and after DDL' }
    Test-Case 'CompleteReadOnly' { $r = Run-Case 'Complete'; Assert-Equal $r.exit 0 'Complete accepted'; Assert-Equal $r.state.dumps 0 'No repeat dump'; Assert-Equal $r.state.ddl 0 'No repeat DDL'; Assert-Equal $r.backupText 'existing backup' 'Existing backup unchanged' }
    Test-Case 'NormalizedFrozenMetadata' { $r = Run-Case 'CRLFMetadata'; Assert-Equal $r.exit 0 'Windows newline normalization'; Assert-Equal $r.state.ddl 1 'Exactly one new migration main'; Assert-Equal $r.state.dumps 1 'Exactly one backup' }
    $rejects = @('WrongType','WrongNull','WrongOrder','WrongDefault','WrongCollation','WrongCharset','WrongEngine','WrongExtra','MissingFK','ForeignFK','WrongFKRule','MissingIndex','NonuniqueIndex','WrongIndexColumn','PrefixIndex','WrongIndexType','WrongIndexOrder','WrongAddedType','Partial','ExtraTable','MissingTable','Trigger','Check','IdentityReadFailure','MetadataReadFailure','SnapshotReadFailure','ExistingBackup')
    $rejects += @('Changed004Metadata','Changed005Metadata','Missing004Metadata','Missing005Metadata','WrongIdentity','CompleteInvalidPaid')
    if ($version -eq '006') { $rejects += 'Later007' } else { $rejects += @('BaselineInvalidPaid','Incomplete006') }
    foreach ($case in $rejects) { Test-Case $case { $r = Run-Case $case; Assert-Equal ($r.exit -ne 0) $true 'Unsafe state rejected'; Assert-Equal $r.state.dumps 0 'Rejected before backup'; Assert-Equal $r.state.ddl 0 'Rejected before DDL'; if ($case -eq 'ExistingBackup') { Assert-Equal $r.backupText 'existing backup' 'Backup never overwritten' } } }
    foreach ($case in @('DumpFailure','EmptyDump')) { Test-Case $case { $r = Run-Case $case; Assert-Equal ($r.exit -ne 0) $true 'Bad dump fails'; Assert-Equal $r.state.dumps 1 'One dump'; Assert-Equal $r.state.ddl 0 'No DDL after failed dump'; Assert-Equal $r.backup $true 'Failed dump retained' } }
    $afterFailures = @('PartialDdl','PartialRetry','DdlFailureComplete','PostShapeFailure','PostReadFailure','PostSnapshotReadFailure','PaymentReadFailure','Changedpayment_order','Changedclinical_record','Changeddoctor_profile','Changeduser_account','InvalidPaid','InvalidUnpaid','DuplicateReference')
    if ($version -eq '007') { $afterFailures += 'NonNullNewProfile' }
    foreach ($case in $afterFailures) { Test-Case $case { $r = Run-Case $case; Assert-Equal ($r.exit -ne 0) $true 'Postcondition failure cannot report success'; Assert-Equal $r.state.dumps 1 'One retained dump'; Assert-Equal $r.state.ddl 1 'One DDL, no retry or recovery'; Assert-Equal $r.backup $true 'Backup retained' } }
    }
    Write-Host "Native-boundary $version summary: passed=$passed failed=$($failures.Count) skipped=0; real MySQL=UNVERIFIED; MySQL5.6=UNVERIFIED"
    if ($failures.Count) { throw ($failures -join "`n") }
} finally { foreach ($n in $prior.Keys) { [Environment]::SetEnvironmentVariable($n,$prior[$n],'Process') }; Write-Host "Evidence retained: $scratch" }
