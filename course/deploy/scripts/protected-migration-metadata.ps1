# Metadata-only functions: no main, clients, environment changes or SQL execution.
# Import verified declarations only; never execute an older upgrader's main.
function Read-PinnedMigrationAst([string]$Path,[string]$Fingerprint) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Frozen migration metadata dependency is unavailable; no SQL was attempted' }
    $text = [IO.File]::ReadAllText($Path).Replace("`r`n","`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace('-','') }
    finally { $sha.Dispose() }
    if ($actual -cne $Fingerprint) { throw 'Frozen migration metadata dependency changed; no SQL was attempted' }
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'Frozen migration metadata dependency is invalid; no SQL was attempted' }
    return $ast
}

function Get-ProtectedMigrationMetadata([string]$ScriptDirectory) {
    $ast004 = Read-PinnedMigrationAst (Join-Path $ScriptDirectory 'upgrade-clinical-database.ps1') '4FD064E442EFF4961E084566D12834DA2248F6119D006A85A17722DFF1CB2C57'
    $ast005 = Read-PinnedMigrationAst (Join-Path $ScriptDirectory 'upgrade-hospital-onboarding-database.ps1') '747E400D44F805D2F7FF6D9DC68E79BA1ABB958FFCBA5FF80D32483D9CDD56D7'
    $selected = @()
    foreach ($declaration in @('$columns:Equals','$indexes:Equals','$foreignKeys:Equals','$clinicalColumns:Equals',
        '$columns.clinical_draft:Equals','$columns.record_correction:Equals',
        '$indexes.clinical_draft:Equals','$indexes.record_correction:Equals','$foreignKeys:PlusEquals','$shapeSql:Equals')) {
        $nodes = @($ast004.EndBlock.Statements | Where-Object {
            $_ -is [Management.Automation.Language.AssignmentStatementAst] -and ($_.Left.Extent.Text + ':' + $_.Operator.ToString()) -ceq $declaration
        })
        if ($nodes.Count -ne 1) { throw 'Frozen 004 metadata declaration is missing or duplicated; no SQL was attempted' }
        $selected += $nodes[0]
    }
    $builder = @($ast004.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Get-ExpectedShape' })
    if ($builder.Count -ne 1) { throw 'Frozen 004 shape builder missing or duplicated; no SQL was attempted' }
    foreach ($node in @($selected + $builder | Sort-Object { $_.Extent.StartOffset })) { . ([scriptblock]::Create($node.Extent.Text)) }
    $additive005 = @($ast005.EndBlock.Statements | Where-Object {
        $_ -is [Management.Automation.Language.AssignmentStatementAst] -and ($_.Left.Extent.Text + ':' + $_.Operator.ToString()) -ceq '$newShape:Equals'
    })
    if ($additive005.Count -ne 1) { throw 'Frozen 005 metadata declaration missing or duplicated; no SQL was attempted' }
    . ([scriptblock]::Create($additive005[0].Extent.Text))
    $oldTables = @('tenant','department','user_account','doctor_profile','schedule_slot','appointment','clinical_record','payment_order','audit_event','file_metadata','clinical_draft','record_correction')
    $shape005 = @(Get-ExpectedShape $oldTables $true) + $newShape
    $shape006 = @($shape005) + @(
        'C:payment_order:8:payment_reference:varchar(25):YES:NULL::utf8mb4:utf8mb4_unicode_ci',
        'I:payment_order:uk_course_order_payment_reference:0:BTREE:payment_reference:~:A'
    )
    $shape007 = @($shape006) + @(
        'C:doctor_profile:5:title:varchar(100):YES:NULL::utf8mb4:utf8mb4_unicode_ci',
        'C:doctor_profile:6:bio:varchar(1000):YES:NULL::utf8mb4:utf8mb4_unicode_ci'
    )
    return [pscustomobject]@{ ShapeSql=$shapeSql; Shape005=@($shape005 | Sort-Object); Shape006=@($shape006 | Sort-Object); Shape007=@($shape007 | Sort-Object) }
}
