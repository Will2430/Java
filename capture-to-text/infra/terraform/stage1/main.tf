# Stage 1: the whole app (today's docker-compose.yml plus the API and worker images)
# on one EC2 server in its own VPC, reached only through a Session Manager tunnel.
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  # Stamped on everything this folder creates, so the console and the bill can be filtered by it.
  default_tags {
    tags = {
      project = "capture-to-text"
      stage   = "1"
    }
  }
}
