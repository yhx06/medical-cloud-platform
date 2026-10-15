$ErrorActionPreference = 'Stop'
$policyScript = Join-Path $PSScriptRoot '../scripts/attachments-storage-policy.ps1'
if (-not (Test-Path -LiteralPath $policyScript)) { throw 'RED: shared attachment storage policy helper is missing' }
. $policyScript
$script:assertionCount = 0

function Assert-Throws([scriptblock]$Action,[string]$Name) {
    $script:assertionCount++
    try { & $Action; throw "ASSERTION FAILED: $Name accepted unsafe input" }
    catch { if ($_.Exception.Message -like 'ASSERTION FAILED:*') { throw } }
}
function Assert-True([bool]$Value,[string]$Name) {
    $script:assertionCount++
    if (-not $Value) { throw "ASSERTION FAILED: $Name" }
}

$bucket = 'medical-attachments-0123456789ab'
$prefix = 'files/'
$policy = Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix $prefix

Assert-True ($policy.Version -eq '2012-10-17') 'policy version'
Assert-True ($policy.Statement.Count -eq 3) 'exactly three statements'
$list,$meta,$objects = $policy.Statement

# 1. Prefix-scoped LIST only; the full-bucket list must stay conditioned.
Assert-True ($list.Effect -eq 'Allow') 'list statement allows'
Assert-True ($list.Action.Count -eq 1 -and $list.Action[0] -eq 's3:ListBucket') 'list statement action'
Assert-True ($list.Resource.Count -eq 1 -and $list.Resource[0] -eq "arn:aws:s3:::$bucket") 'list statement exact bucket ARN'
Assert-True ($list.Condition.StringLike.'s3:prefix'.Count -eq 1 -and $list.Condition.StringLike.'s3:prefix'[0] -eq ($prefix + '*')) 'list keeps the prefix condition'

# 2. The three read-only metadata actions the private store preflight needs, exact bucket ARN only.
Assert-True ($meta.Effect -eq 'Allow') 'metadata statement allows'
Assert-True ($meta.Action.Count -eq 3) 'metadata statement has three actions'
foreach ($action in @('s3:GetBucketVersioning','s3:GetBucketObjectLockConfiguration','s3:GetBucketPolicy')) {
    Assert-True ($meta.Action -contains $action) "metadata action $action"
}
Assert-True ($meta.Resource.Count -eq 1 -and $meta.Resource[0] -eq "arn:aws:s3:::$bucket") 'metadata statement exact bucket ARN'
Assert-True (-not $meta.Condition) 'metadata statement does not widen listing'

# 3. Object access remains confined to the owned prefix.
Assert-True ($objects.Effect -eq 'Allow') 'object statement allows'
Assert-True ($objects.Action.Count -eq 3) 'object statement has three actions'
foreach ($action in @('s3:GetObject','s3:PutObject','s3:DeleteObject')) {
    Assert-True ($objects.Action -contains $action) "object action $action"
}
Assert-True ($objects.Resource.Count -eq 1 -and $objects.Resource[0] -eq "arn:aws:s3:::$bucket/$prefix*") 'object statement prefix scope'

# Fail-closed shape checks: never widen to root credentials, full-bucket list or object wildcards.
$flattened = ($policy | ConvertTo-Json -Depth 10)
Assert-True ($flattened -notmatch '(?i)s3:\*') 'no wildcard actions'
Assert-True ($flattened -notmatch '"\*"') 'no wildcard principals or resources'
foreach ($statement in $policy.Statement) {
    Assert-True ($statement.Effect -eq 'Allow') 'only allow statements'
    foreach ($resource in @($statement.Resource)) {
        Assert-True ($resource -ne "arn:aws:s3:::$bucket/*") 'no whole-bucket object wildcard'
        Assert-True ($resource -match [regex]::Escape("arn:aws:s3:::$bucket")) 'resources stay inside the exact bucket'
    }
}

Assert-Throws { Get-AttachmentsStoragePolicy -Bucket '' -Prefix $prefix } 'emptyBucketRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix '' } 'emptyPrefixRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket '../other' -Prefix $prefix } 'bucketEscapeRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix '../' } 'prefixEscapeRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix '*' } 'wildcardPrefixRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix 'files/*"' } 'quotePrefixRejected'
Assert-Throws { Get-AttachmentsStoragePolicy -Bucket $bucket -Prefix 'files/ ' } 'spacePrefixRejected'

# The generator must consume this shared policy instead of drifting a private copy.
$creator = Join-Path $PSScriptRoot '../scripts/new-attachments-fixture.ps1'
$creatorText = [IO.File]::ReadAllText($creator)
Assert-True ($creatorText -match 'attachments-storage-policy\.ps1') 'generator loads the shared policy helper'
Assert-True ($creatorText -match 'Get-AttachmentsStoragePolicy') 'generator uses the shared policy helper'
Assert-True ($creatorText -notmatch 's3:ListBucket''\);\s*Resource') 'generator no longer inlines its own policy copy'

Write-Output "PASS: attachment storage policy least privilege ($script:assertionCount assertions, metadata preflight actions present)"
