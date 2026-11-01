# The role the server borrows. Trust policy: only the EC2 service may borrow it.
resource "aws_iam_role" "server" {
  name = "capture-to-text-server"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Permissions policy - aws:policy is an AWS managed policy that every account can attach, and it denotes a permission level for borrower to do 
# whatever 'AmazonSSMManagedInstanceCore' allows, and since SSM agaent is just a program on the server, it can then use the key issued for establishing
# outbound calls to System Manger.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# A second permissions policy, written here (inline) because no AWS-managed one is limited
# to a single bucket. ListBucket lets the API's startup check (bucketExists) see the bucket;
# Put/Get are the upload (API) and download (worker). Nothing else, and no other bucket.
resource "aws_iam_role_policy" "images" {
  name = "capture-to-text-images"
  role = aws_iam_role.server.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.images.arn
      },
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.images.arn}/*"
      }
    ]
  })
}

# The wrapper that attaches a role to an EC2 server (the console makes this for you).
resource "aws_iam_instance_profile" "server" {
  name = "capture-to-text-server"
  role = aws_iam_role.server.name
}
