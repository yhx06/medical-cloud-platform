param(
    [Parameter(Mandatory = $true)][string]$FixturePath,
    [Parameter(Mandatory = $true)][string]$EvidenceRoot
)
# Contract tests for verify-attachments.ps1: mandatory skips must fail the summary, every
# evidence write is ownership guarded, and the summary never carries signed URL material.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
. (Join-Path $repo 'course\deploy\tests\attachments-fixture-ownership.ps1')
$verify = Join-Path $repo 'course\deploy\scripts\verify-attachments.ps1'
if (-not (Test-Path -LiteralPath $verify)) { throw 'RED: verify-attachments.ps1 is missing' }
$fixtureFull = [IO.Path]::GetFullPath($FixturePath)
$evidenceFull = [IO.Path]::GetFullPath($EvidenceRoot)
$fixture = [IO.File]::ReadAllText($fixtureFull) | ConvertFrom-Json
foreach ($component in @('mysql','storage','portal','paas')) { Assert-OwnedAttachmentsFixture $fixture $component }
$assertions = 0

function Assert-True([bool]$Value, [string]$Name) {
    $script:assertions++
    if (-not $Value) { throw "ASSERTION FAILED: $Name" }
}
function Invoke-Verify([string]$Fixture, [string[]]$ExtraSkip, [string]$Evidence) {
    $outFile = Join-Path $env:TEMP ('attachment-verify-out-' + [guid]::NewGuid().ToString('N') + '.txt')
    $errFile = Join-Path $env:TEMP ('attachment-verify-err-' + [guid]::NewGuid().ToString('N') + '.txt')
    $inner = '& "' + $verify + '" -FixturePath "' + $Fixture + '" -EvidenceRoot "' + $Evidence + '"'
    if ($ExtraSkip) { $inner += ' -SkipGate "' + ($ExtraSkip -join ',') + '"' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($inner))
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & powershell.exe -NoProfile -EncodedCommand $encoded 1>$outFile 2>$errFile
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $output = ''
    if (Test-Path $outFile) { $output += [IO.File]::ReadAllText($outFile) }
    if (Test-Path $errFile) { $output += [IO.File]::ReadAllText($errFile) }
    Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue
    return @{ exit = $exit; output = $output }
}

# 1. A skipped mandatory gate must fail the summary contract.
$contractEvidence = Join-Path $evidenceFull 'contract-mandatory-skip'
[void][IO.Directory]::CreateDirectory($contractEvidence)
$skippedRun = Invoke-Verify $fixtureFull @('app-health') $contractEvidence
Assert-True ($skippedRun.exit -ne 0) 'mandatorySkippedGateFailsSummary: exit must be non-zero'
Assert-True ($skippedRun.output -match 'mandatory gate skipped') 'mandatorySkippedGateFailsSummary: failure reason recorded'
$summary = [IO.File]::ReadAllText((Join-Path $contractEvidence 'attachments-verification-summary.json')) | ConvertFrom-Json
Assert-True (@($summary.mandatorySkipped) -contains 'app-health') 'mandatorySkippedGateFailsSummary: summary lists the skipped mandatory gate'

# 2. Evidence writes are ownership guarded: a foreign fixture identity is refused before any write.
$foreignDir = Join-Path $evidenceFull 'contract-foreign'
[void][IO.Directory]::CreateDirectory($foreignDir)
$foreign = [IO.File]::ReadAllText($fixtureFull) | ConvertFrom-Json
$foreign.ownerId = 'ffffffffffffffffffffffffffffffff'
$foreignPath = Join-Path $foreignDir 'foreign-fixture.json'
[IO.File]::WriteAllText($foreignPath, ($foreign | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$foreignRun = Invoke-Verify $foreignPath $null $foreignDir
Assert-True ($foreignRun.exit -ne 0) 'fixtureOwnershipRequiredBeforeEveryWrite: foreign identity refused'
Assert-True (-not (Test-Path (Join-Path $foreignDir 'attachments-verification-summary.json'))) 'fixtureOwnershipRequiredBeforeEveryWrite: no summary written for a foreign fixture'

# 3. The redacted summary never carries signed URL material.
$mainEvidence = Join-Path $evidenceFull 'contract-redaction'
[void][IO.Directory]::CreateDirectory($mainEvidence)
$fullRun = Invoke-Verify $fixtureFull $null $mainEvidence
Assert-True ($fullRun.exit -eq 0) 'verification gates must pass for the redaction contract'
$summaryText = [IO.File]::ReadAllText((Join-Path $mainEvidence 'attachments-verification-summary.json'))
Assert-True ($summaryText -notmatch 'X-Amz-Signature') 'signedUrlsRedactedFromEvidenceSummary: no signature in summary'
Assert-True ($summaryText -notmatch 'X-Amz-Credential') 'signedUrlsRedactedFromEvidenceSummary: no credential scope in summary'
Assert-True ($summaryText -notmatch 'X-Amz-Security-Token') 'signedUrlsRedactedFromEvidenceSummary: no session token in summary'
foreach ($file in Get-ChildItem -LiteralPath $mainEvidence -Filter '*.json') {
    $text = [IO.File]::ReadAllText($file.FullName)
    Assert-True ($text -notmatch 'X-Amz-Signature') "signedUrlsRedactedFromEvidenceSummary: no signature in $($file.Name)"
}

Write-Output "PASS: attachment acceptance contracts ($assertions assertions, 3 required scenarios)"
