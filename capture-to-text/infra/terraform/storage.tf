# Replaces the MinIO container: uploaded images now live in S3.
data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "images" {
  # Bucket names are global across every AWS account, so the account id makes it unique.
  bucket = "capture-to-text-images-${data.aws_caller_identity.current.account_id}"

  # Lets terraform destroy delete the bucket even with test images still inside.
  force_destroy = true
}

# Belt and braces: nothing in this bucket can ever be made public, even by mistake.
# (New buckets already encrypt every object and ignore ACLs by default.)
resource "aws_s3_bucket_public_access_block" "images" {
  bucket                  = aws_s3_bucket.images.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
