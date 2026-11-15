# The whole deploy, grown in place stage by stage (see Delpoy_Progress.md): the app on one
# EC2 server, reached through a Session Manager tunnel, with uploaded images in S3.
terraform {
  # 1.11 is the first version with write-only arguments (database.tf keeps the password out of state).
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    mongodbatlas = {
      source  = "mongodb/mongodbatlas"
      version = "~> 2.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

# Logs in to Atlas with the service account from the MONGODB_ATLAS_CLIENT_ID and
# MONGODB_ATLAS_CLIENT_SECRET environment variables, so the secret never sits in a file here.
provider "mongodbatlas" {}

provider "aws" {
  region = var.region

  # Stamped on everything this folder creates, so the console and the bill can be filtered by it.
  default_tags {
    tags = {
      project = "capture-to-text"
    }
  }
}
