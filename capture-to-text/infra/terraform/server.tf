# AWS publishes the newest Amazon Linux 2023 image id under this public name.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "server" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.server.id]
  iam_instance_profile   = aws_iam_instance_profile.server.name

  # Only answer metadata requests that carry a session token (IMDSv2).
  metadata_options {
    http_tokens = "required"
    # The api/worker containers ask for the role's keys from behind Docker's network, one
    # extra hop away. With a limit of 1 the token reply dies on the way and S3 calls fail.
    http_put_response_hop_limit = 2
  }

  # Docker images, build caches and Kafka data need more than the default 8 GiB.
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  # Runs once, as root, on first boot. compose.server.yml, the Caddyfile and the secrets
  # script are pasted into it. Only the secrets' names travel here, never their values.
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    repo_url = var.repo_url
    git_ref  = var.git_ref
    app_host = local.app_host
    compose_server = templatefile("${path.module}/compose.server.yml", {
      bucket    = aws_s3_bucket.images.bucket
      region    = var.region
      app_host  = local.app_host
      auth_host = local.auth_host
    })
    caddyfile = templatefile("${path.module}/Caddyfile.tftpl", {
      app_host  = local.app_host
      auth_host = local.auth_host
    })
    refresh_secrets = templatefile("${path.module}/refresh_secrets.sh.tftpl", {
      region                      = var.region
      mongo_uri_param             = aws_ssm_parameter.mongo_uri.name
      stripe_secret_key_param     = aws_ssm_parameter.stripe_secret_key.name
      stripe_webhook_secret_param = aws_ssm_parameter.stripe_webhook_secret.name
    })
  })
  # The script only runs on first boot, so a changed script means a new server.
  user_data_replace_on_change = true

  # A newer Amazon Linux release shouldn't replace a running server on its own.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "capture-to-text-server" }
}

# this is to allow Terraform to start and stop the server based on the state
resource "aws_ec2_instance_state" "server" {
  instance_id = aws_instance.server.id
  state       = var.server_running ? "running" : "stopped"
}
