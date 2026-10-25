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

# Permissions policy: AWS's ready-made one that lets the SSM Agent on the server
# register with Systems Manager, which is what makes Session Manager work.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# The wrapper that attaches a role to an EC2 server (the console makes this for you).
resource "aws_iam_instance_profile" "server" {
  name = "capture-to-text-server"
  role = aws_iam_role.server.name
}
