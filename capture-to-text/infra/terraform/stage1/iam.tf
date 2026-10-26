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

# The wrapper that attaches a role to an EC2 server (the console makes this for you).
resource "aws_iam_instance_profile" "server" {
  name = "capture-to-text-server"
  role = aws_iam_role.server.name
}
