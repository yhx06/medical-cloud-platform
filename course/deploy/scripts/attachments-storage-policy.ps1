# Application storage policy for one owned attachments fixture.
# Least privilege: prefix-scoped LIST and object read/write/delete, plus the three
# read-only bucket metadata actions the private store preflight must observe.
function Get-AttachmentsStoragePolicy {
    param(
        [Parameter(Mandatory=$true)][string]$Bucket,
        [Parameter(Mandatory=$true)][string]$Prefix
    )
    if ([string]::IsNullOrWhiteSpace($Bucket)) { throw 'Bucket is required' }
    if ([string]::IsNullOrWhiteSpace($Prefix)) { throw 'Prefix is required' }
    if ($Bucket -match '[^A-Za-z0-9.\-]') { throw 'Bucket escapes the owned naming rules' }
    if ($Prefix -notmatch '^[A-Za-z0-9_./-]+$' -or $Prefix -match '\.\.') { throw 'Prefix escapes the owned naming rules' }
    [ordered]@{
        Version = '2012-10-17'
        Statement = @(
            [ordered]@{
                Effect = 'Allow'
                Action = @('s3:ListBucket')
                Resource = @("arn:aws:s3:::$Bucket")
                Condition = @{ 'StringLike' = @{ 's3:prefix' = @($Prefix + '*') } }
            },
            [ordered]@{
                Effect = 'Allow'
                Action = @('s3:GetBucketVersioning','s3:GetBucketObjectLockConfiguration','s3:GetBucketPolicy')
                Resource = @("arn:aws:s3:::$Bucket")
            },
            [ordered]@{
                Effect = 'Allow'
                Action = @('s3:GetObject','s3:PutObject','s3:DeleteObject')
                Resource = @("arn:aws:s3:::$Bucket/$Prefix*")
            }
        )
    }
}
