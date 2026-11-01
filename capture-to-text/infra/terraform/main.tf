# The whole deploy, grown in place stage by stage (see Delpoy_Progress.md): the app on one
# EC2 server, reached through a Session Manager tunnel, with uploaded images in S3.
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
    }
  }
}
