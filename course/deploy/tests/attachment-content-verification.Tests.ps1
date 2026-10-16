# Boundary-only tests. The AST replaces the ownership import with a test double;
# the shipped entry point has no fake transport, skip mode or test switch.
$ErrorActionPreference = 'Stop'
$verify = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../scripts/verify-attachment-content.ps1'))
if (-not (Test-Path -LiteralPath $verify)) { throw 'RED: content verification entry point is missing' }
$root = Join-Path $env:TEMP ('attachment-content-tests-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
$created = New-Object 'System.Collections.Generic.List[string]'
$assertions = 0
function Check($Value, $Name) { $script:assertions++; if (-not $Value) { throw ('ASSERTION FAILED: ' + $Name) } }
$fake = @'
function Assert-OwnedAttachmentsFixture($Fixture,$Component) {
 if ($Fixture.ownerId -ne '11111111111111111111111111111111' -or ($env:CONTENT_CASE -eq 'revoked' -and $global:calls -ge 8) -or ($env:CONTENT_CASE -eq 'checkpoint-interrupted' -and $global:calls -ge 11)) { throw 'foreign identity secret-token' }
}
$global:calls=0; $global:uploads=@{}; $global:serial=0; $global:deletes=0; $global:storageReads=0
function Invoke-WebRequest {
 param($Uri,$Method,$Headers,$Body,$ContentType,$TimeoutSec,$MaximumRedirection,[switch]$UseBasicParsing,$ErrorAction)
 $global:calls++; $u=[uri]$Uri; $p=$u.AbsolutePath; $status=200; $data=$null; $bytes=[byte[]]@()
 if($p -eq '/paas/api/auth/login') {$data=@{accessToken='secret-token'}}
 elseif($p -eq '/paas/api/hospital/slots' -and $Method -eq 'GET') {$data=@()}
 elseif($p -eq '/paas/api/hospital/slots') {$data=@{id='10000000-0000-0000-0000-000000000001'}}
 elseif($p -eq '/paas/api/patient/appointments' -and $Method -eq 'POST') {$data=@{id='20000000-0000-0000-0000-000000000001';status='BOOKED'};if($env:CONTENT_CASE -eq 'incomplete'){$data.status='IN_PROGRESS'}}
 elseif($p -match '/files/uploads$') {
  $d=$Body|ConvertFrom-Json
  if($d.sizeBytes -eq 0){$status=400} elseif($d.sizeBytes -gt 10485760){$status=413}
  else {$global:serial++; $id=('30000000-0000-0000-0000-{0:d12}' -f $global:serial); $global:uploads[$id]=@{descriptor=$d;bytes=$null};$data=@{uploadId=$id}}
 }
 elseif($p -match '/uploads/([^/]+)/content$') {
  $upload=$global:uploads[$Matches[1]]; $upload.bytes=[byte[]]$Body
  switch($upload.descriptor.fileName) {
   'malformed.pdf' {$status=400}; 'damaged.png' {$status=400}; 'spoofed.png' {$status=415}
   'digest-mismatch.png' {
    $hash=[Security.Cryptography.SHA256]::Create()
    try{$actual=([BitConverter]::ToString($hash.ComputeHash([byte[]]$Body))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
    if($upload.descriptor.sha256 -ne $actual){$status=400}
   }
  }
  if($upload.descriptor.fileName -eq 'exact.jpg' -and ([byte[]]$Body).Length -ne 10485760){$status=400}
  if($env:CONTENT_CASE -eq 'negative-accepted'){$status=200}
  if($env:CONTENT_CASE -eq 'positive-rejected' -and $upload.descriptor.fileName -eq 'positive.jpg'){$status=415}
 }
 elseif($p -match '/files/([^/]+)/download-url$') {$data=@{url=('http://127.0.0.1:19001/bucket/'+$Matches[1]+'?X-Amz-Signature=secret-signature')};if($env:CONTENT_CASE -eq 'storage-foreign'){$data.url='http://remote.invalid:19001/bucket/secret-signature'}}
 elseif($p -match '^/bucket/(.+)$') {$global:storageReads++;$bytes=$global:uploads[$Matches[1]].bytes; if($env:CONTENT_CASE -eq 'download-corrupt'){$bytes=[byte[]]@(1,2,3)}}
 elseif($p -match '/patient/files/([^/]+)/delete$') {$global:deletes++;$data=@{deleted=$true}}
 else {throw 'Unexpected request secret-token'}
 $text=@{data=$data}|ConvertTo-Json -Depth 8
 if($status -ne 200){
  $response=New-Object PSObject -Property @{StatusCode=$status}; $ex=New-Object Exception 'secret-token http://private'; $ex|Add-Member NoteProperty Response $response
  throw $ex
 }
 $stream=New-Object IO.MemoryStream(,$bytes)
 return @{StatusCode=$status;Content=$text;RawContentStream=$stream}
}
'@
try {
 $source=[IO.File]::ReadAllText($verify)
 $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
 Check ($errors.Count -eq 0) 'PS5.1 parser accepts entry point'
 $imports=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.InvocationOperator -eq 'Dot'},$true))
 Check ($imports.Count -eq 1) 'single ownership boundary import'
 $node=$imports[0];$source=$source.Remove($node.Extent.StartOffset,$node.Extent.EndOffset-$node.Extent.StartOffset)
 foreach($case in @('success','foreign','wrong-target','positive-rejected','negative-accepted','download-corrupt','revoked','missing','incomplete','storage-foreign','checkpoint-interrupted')) {
  $fixture=Join-Path $root ($case+'.json');$evidence=Join-Path $root $case
  $identity=@{ownerId='11111111111111111111111111111111';paas=@{port=19000;url='http://127.0.0.1:19000'};storage=@{port=19001;bucket='bucket'}}
  if($case -eq 'foreign'){$identity.ownerId='ffffffffffffffffffffffffffffffff'}
  if($case -eq 'wrong-target'){$identity.paas.url='http://remote.invalid:19000/paas'}
  [IO.File]::WriteAllText($fixture,($identity|ConvertTo-Json -Depth 5));$created.Add($fixture)
  $fixtureArg=$fixture;if($case -eq 'missing'){$fixtureArg=Join-Path $root 'absent.json'}
  $env:CONTENT_CASE=$case
  $trace=Join-Path $root ($case+'-boundary.json');$created.Add($trace)
  $command=$fake+"`ntry { . {`n"+$source+"`n} -FixturePath '"+$fixtureArg+"' -EvidenceRoot '"+$evidence+"' } finally { [IO.File]::WriteAllText('"+$trace+"', (@{calls=`$global:calls;deletes=`$global:deletes;storageReads=`$global:storageReads}|ConvertTo-Json)) }"
  $wrapper=Join-Path $root ($case+'-wrapper.ps1');$created.Add($wrapper)
  [IO.File]::WriteAllText($wrapper,$command,[Text.UTF8Encoding]::new($true))
  $output=@(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $wrapper 2>&1);$code=$LASTEXITCODE
  $boundary=[IO.File]::ReadAllText($trace)|ConvertFrom-Json
  Check (($output -join "`n") -notmatch 'secret-token|secret-signature|remote.invalid|http://private') ($case+' safe console')
  if($case -eq 'success'){Check ($code -eq 0) 'positive and negative expectations pass'}else{Check ($code -ne 0) ($case+' cannot pass')}
  $allEvidence=@(Get-ChildItem -LiteralPath $evidence -Recurse -File -ErrorAction SilentlyContinue)
  $summaries=@($allEvidence|Where-Object Name -eq 'content-summary.json')
  $progressFiles=@($allEvidence|Where-Object Name -eq 'content-progress.json')
  foreach($file in $allEvidence){$created.Add($file.FullName);Check ([IO.File]::ReadAllText($file.FullName) -notmatch 'secret-|https?://|X-Amz') ($case+' safe evidence')}
  if($case -in @('foreign','wrong-target','missing')){Check ($summaries.Count -eq 0) ($case+' refused before evidence');Check ($boundary.calls -eq 0) ($case+' refused before requests')}
  if($case -eq 'revoked'){Check ($boundary.calls -eq 8) 'ownership rechecked before next request'}
  if($case -eq 'storage-foreign'){Check ($boundary.storageReads -eq 0) 'signed download target refused before GET'}
  if($case -eq 'incomplete'){$partial=[IO.File]::ReadAllText($summaries[0].FullName)|ConvertFrom-Json;Check (@($partial.unexecuted).Count -eq 10) 'missing required cases explicit and unsuccessful'}
  if($case -eq 'success'){
   $summary=[IO.File]::ReadAllText($summaries[0].FullName)|ConvertFrom-Json
   Check (@($summary.passed).Count -eq 10) 'all ten required content cases measured'
   Check (-not $summary.fullGate1Accepted) 'partial coverage never passes whole gate1'
   Check ($boundary.deletes -eq 4 -and $summary.ownAvailableFilesCleaned) 'all four available positives deleted through API'
   Check ($progressFiles.Count -eq 1) 'completed cases leave incremental checkpoint'
   $progress=[IO.File]::ReadAllText($progressFiles[0].FullName)|ConvertFrom-Json
   Check (-not $progress.complete -and @($progress.executed).Count -eq 10 -and @($progress.passed).Count -eq 10) 'checkpoint remains incomplete even after all cases'
   Check ($summary.complete) 'only final summary states complete'
   Check (@($output|Where-Object {$_ -match '^CONTENT CASE [a-z-]+ PASSED$'}).Count -eq 10) 'fixed case names have safe completion output'
  }
  if($case -eq 'checkpoint-interrupted'){
   Check ($summaries.Count -eq 0) 'lost ownership cannot write complete conclusion'
   Check ($progressFiles.Count -eq 1) 'earlier checkpoint survives later ownership exception'
   $progress=[IO.File]::ReadAllText($progressFiles[0].FullName)|ConvertFrom-Json
   Check (-not $progress.complete -and (@($progress.executed) -join ',') -eq 'patient-jpg' -and (@($progress.passed) -join ',') -eq 'patient-jpg' -and @($progress.failed).Count -eq 0 -and @($progress.unexecuted).Count -eq 9) 'last completed case stays observable without claiming remaining cases'
   Check (@($output|Where-Object {$_ -eq 'CONTENT CASE patient-jpg PASSED'}).Count -eq 1) 'completed case visible before interruption'
   Check (@($output|Where-Object {$_ -match '^CONTENT CASE patient-png PASSED$|^CONTENT CASES PASSED'}).Count -eq 0) 'failed checkpoint cannot emit later success'
  }
  if($case -eq 'positive-rejected'){
   Check (@($output|Where-Object {$_ -eq 'CONTENT CASE patient-jpg FAILED'}).Count -eq 1) 'failed case emits fixed safe completion status'
  }
 }
 Write-Output ('PASS: harness-only content boundary tests ('+$assertions+' assertions); real HTTP not executed')
} finally {
 Remove-Item Env:CONTENT_CASE -ErrorAction SilentlyContinue
 foreach($file in $created){if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file -Force}}
 # Delete only empty directories under this unique owned test root.
 Get-ChildItem -LiteralPath $root -Directory -Recurse | Sort-Object FullName -Descending | ForEach-Object {if(@(Get-ChildItem -LiteralPath $_.FullName -Force).Count -eq 0){[IO.Directory]::Delete($_.FullName)}}
 if(@(Get-ChildItem -LiteralPath $root -Force).Count -eq 0){[IO.Directory]::Delete($root)}
}
