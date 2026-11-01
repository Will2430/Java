# One VPC, one public subnet. No private subnet yet: a private one would need a NAT
# gateway (about $33/month) for the server to download Docker images.
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "capture-to-text" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = { Name = "capture-to-text-public" }
}

# The "IGW line": everything not for 10.0.0.0/16 goes to the internet gateway.
# AWS adds the "local line" for 10.0.0.0/16 itself.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# No inbound rules at all: you reach the app through Session Manager, which the
# server connects out to. Replies to the server's own outbound calls still get in,
# because a security group is stateful.
resource "aws_security_group" "server" {
  name        = "capture-to-text-server"
  description = "No inbound; all outbound"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_egress_rule" "all_out" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
