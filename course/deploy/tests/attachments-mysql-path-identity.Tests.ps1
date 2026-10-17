# Native Windows PS5 boundary tests; no SQL server or process lifecycle is used.
$ErrorActionPreference='Stop'
if($PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -ne 5){throw 'Run with Windows PowerShell 5.1'}
$projectCandidate=[IO.DirectoryInfo]$PSScriptRoot
while($projectCandidate -and -not(Test-Path -LiteralPath (Join-Path $projectCandidate.FullName '.local/tests') -PathType Container)){$projectCandidate=$projectCandidate.Parent}
if(-not $projectCandidate){throw 'Existing private project tests parent required'}
$project=$projectCandidate.FullName
$testParent=Join-Path $project ('.local/tests/u'+[guid]::NewGuid().ToString('N').Substring(0,8))
if(Test-Path -LiteralPath $testParent){throw 'Unique identity test scratch already exists'}
[void][IO.Directory]::CreateDirectory($testParent)
$saved=@{}
foreach($name in @('TEMP','TMP','MYSQL_PWD','MYSQL_IDENTITY_ROW','MYSQL_IDENTITY_EXIT','MYSQL_IDENTITY_CAPTURE','MYSQL_IDENTITY_CWD')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$assertions=0;$failures=0
function Check([bool]$condition,[string]$name){$script:assertions++;if(-not $condition){$script:failures++;Write-Output "FAIL: $name"}}
try{
 $env:TEMP=$testParent;$env:TMP=$testParent
 $source=@'
using System;
using System.IO;
using System.Runtime.InteropServices;
public class NativeMysqlIdentityProbe {
 [DllImport("kernel32.dll")] public static extern uint GetACP();
 public static int Main(string[] args) {
  bool query=false,defaults=false,tcp=false;
  foreach(string arg in args){
   if(arg=="--execute=SELECT @@server_uuid,@@port,HEX(@@datadir),@@version_compile_os") query=true;
   if(arg=="--defaults-extra-file=./mysql-client.cnf") defaults=true;
   if(arg=="--protocol=TCP") tcp=true;
  }
  bool cwd=String.Equals(Environment.CurrentDirectory,Environment.GetEnvironmentVariable("MYSQL_IDENTITY_CWD"),StringComparison.OrdinalIgnoreCase);
  File.WriteAllLines(Environment.GetEnvironmentVariable("MYSQL_IDENTITY_CAPTURE"),new[]{(query&&defaults&&tcp).ToString(),String.IsNullOrEmpty(Environment.GetEnvironmentVariable("MYSQL_PWD")).ToString(),cwd.ToString()});
  int exit=Int32.Parse(Environment.GetEnvironmentVariable("MYSQL_IDENTITY_EXIT"));
  if(exit==0) Console.WriteLine(Environment.GetEnvironmentVariable("MYSQL_IDENTITY_ROW"));
  return exit;
 }
}
'@
 $nativeSource=Join-Path $testParent 'NativeMysqlIdentityProbe.cs';$native=Join-Path $testParent 'NativeMysqlIdentityProbe.exe'
 [IO.File]::WriteAllText($nativeSource,$source,[Text.UTF8Encoding]::new($false))
 & "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe "/out:$native" $nativeSource
 if($LASTEXITCODE -ne 0){throw 'Native SQL boundary helper compilation failed'}
 [void][Reflection.Assembly]::LoadFile($native)
 $acp=[int][NativeMysqlIdentityProbe]::GetACP()
 $ansi=[Text.Encoding]::GetEncoding($acp,[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)
 $utf8=[Text.UTF8Encoding]::new($false,$true)
 . (Join-Path $PSScriptRoot 'attachments-fixture-ownership.ps1')
 # Only the external OS observations are doubled; SQL invocation and guards run.
 function Get-CimInstance {return $script:fakeProcess}
 function Get-NetTCPConnection {param($LocalAddress,$LocalPort,$State,$ErrorAction);return [pscustomobject]@{OwningProcess=23456;LocalPort=$LocalPort}}
 $chinese=-join ([char[]]@(0x4E91,0x533B,0x7597))
 $uuid='12345678-1234-4234-8234-123456789abc'
 $cases=@('ascii-utf8','chinese-utf8','chinese-system-acp','foreign-utf8','foreign-system-acp','invalid-hex','odd-hex','malformed-bytes','changed-uuid','changed-port','nonwindows-acp','unknown-os','missing-field','extra-field','native-seven','unset-password-utf8','unset-password-reject')
 foreach($case in $cases){
  $parent=Join-Path $testParent $(if($case -eq 'ascii-utf8'){'ascii'}else{$chinese+'-'+$case})
  $root=Join-Path $parent ('attachments-'+[guid]::NewGuid().ToString('N'));$data=Join-Path $root 'mysql-data'
  [void][IO.Directory]::CreateDirectory($data)
  $owner=[IO.Path]::GetFileName($root).Substring(12);[IO.File]::WriteAllText((Join-Path $root 'owner.id'),$owner)
  [IO.File]::WriteAllText((Join-Path $data 'auto.cnf'),"[auto]`nserver-uuid=$uuid`n")
  $server=Join-Path $root 'mysqld.exe';$options=Join-Path $root 'mysql-client.cnf'
  [IO.File]::WriteAllText($server,'test boundary only');[IO.File]::WriteAllText($options,'synthetic options only')
  $created=[datetime]'2030-01-01T00:00:00Z'
  # All real files stay under the Chinese project root. The ASCII-only wire
  # contract is exercised at the identity entry point without a disk/runtime claim.
  $identityData=if($case -eq 'ascii-utf8'){'E:\synthetic-owned-ascii\attachments-'+$owner+'\mysql-data'}else{$data}
  $fixture=[pscustomobject]@{ownerId=$owner;runtimeRoot=$root;mysql=[pscustomobject]@{pid=23456;created=$created;executable=$server;host='127.0.0.1';port=45678;serverUuid=$uuid;dataDirectory=$identityData;clientExecutable=$native;credentialFile=$options}}
  $script:fakeProcess=[pscustomobject]@{ProcessId=23456;CreationDate=$created;ExecutablePath=$server;CommandLine=('"'+$server+'" --datadir="'+$data+'" --port=45678')}
  $reported=if($case -like 'foreign-*'){Join-Path $root 'foreign-data'}else{$identityData+'\'}
  $wireEncoding=if($case -in @('chinese-system-acp','foreign-system-acp','nonwindows-acp')){$ansi}else{$utf8}
  $hex=[BitConverter]::ToString($wireEncoding.GetBytes($reported)).Replace('-','')
  $reportedUuid=$uuid;$reportedPort='45678';$os='Win64';$exit=0
  switch($case){
   'invalid-hex'{$hex='0G'}
   'odd-hex'{$hex='0'}
   'malformed-bytes'{$hex+='81'}
   'changed-uuid'{$reportedUuid='00000000-0000-0000-0000-000000000000'}
   'changed-port'{$reportedPort='45679'}
   'nonwindows-acp'{$os='Linux'}
   'unknown-os'{$os='Unknown'}
   'native-seven'{$exit=7}
   'unset-password-reject'{$hex='0G'}
  }
  $env:MYSQL_IDENTITY_ROW="$reportedUuid`t$reportedPort`t$hex`t$os"
  if($case -eq 'missing-field'){$env:MYSQL_IDENTITY_ROW="$reportedUuid`t$reportedPort`t$hex"}
  if($case -eq 'extra-field'){$env:MYSQL_IDENTITY_ROW+="`textra"}
  $env:MYSQL_IDENTITY_EXIT=[string]$exit;$env:MYSQL_IDENTITY_CAPTURE=Join-Path $parent 'native-status.txt'
  $env:MYSQL_IDENTITY_CWD=$root
  $env:MYSQL_PWD=if($case -like 'unset-password-*'){$null}else{'synthetic-prior-password'}
  $prior=$env:MYSQL_PWD;$priorLocation=(Get-Location).Path;$caught=$false;$identity=$null
  try{$identity=Get-AttachmentsSqlIdentity $fixture;if($case -ne 'ascii-utf8'){Assert-OwnedAttachmentsFixture $fixture mysql}}catch{$caught=$true}
  $success=$case -in @('ascii-utf8','chinese-utf8','chinese-system-acp','unset-password-utf8')
  Check ($caught -ne $success) "$case safe identity result"
  if($success){Check ($identity -ceq "$uuid`t45678`t$identityData") "$case original three-field normalized identity"}
  Check ($env:MYSQL_PWD -ceq $prior) "$case prior password restored"
  Check ((Get-Location).Path -ceq $priorLocation) "$case caller location restored"
  $observations=[IO.File]::ReadAllLines($env:MYSQL_IDENTITY_CAPTURE)
  Check ($observations[0] -ceq 'True') "$case ASCII identity query and existing native options"
  Check ($observations[1] -ceq 'True') "$case inherited MYSQL_PWD suppressed at native boundary"
  Check ($observations[2] -ceq 'True') "$case native options resolved inside owned credential directory"
 }
 Write-Output "mysql path identity behavior: scenarios=$($cases.Count) assertions=$assertions failed=$failures ACP=$acp"
 Write-Output "Private boundary evidence: $testParent"
 if($failures){throw 'MySQL path identity regression failed'}
}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
