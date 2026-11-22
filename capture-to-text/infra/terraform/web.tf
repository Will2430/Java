# Stage 4: the app on the public internet over HTTPS, so Stripe's webhooks can reach it and
# Keycloak's login works off localhost. Caddy (a container, see compose.server.yml) receives
# all web traffic, gets the certificates, and passes requests to the api or Keycloak.

# sslip.io is a free public DNS service: any name with an IP written inside it points at that
# IP, e.g. app.13-229-10-20.sslip.io -> 13.229.10.20. Nothing to buy or register. The IP is the
# Elastic IP, so these names stay the same across stop/start.
locals {
  site      = "${replace(aws_eip.server.public_ip, ".", "-")}.sslip.io"
  app_host  = "app.${local.site}"
  auth_host = "auth.${local.site}"
}

# 80: Let's Encrypt checks it to prove we control the name, and Caddy redirects it to 443.
# 443: HTTPS for browsers and Stripe. Every other port stays closed.
resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# Stripe's two secrets. Terraform creates each parameter with a placeholder, and you put the
# real value in with `aws ssm put-parameter --overwrite` (see Delpoy_Progress.md). Terraform
# only sends a write-only value when its version number changes, so it never puts the
# placeholder back, and the real key never passes through Terraform or its state file.
resource "aws_ssm_parameter" "stripe_secret_key" {
  name             = "/capture-to-text/stripe-secret-key"
  type             = "SecureString"
  value_wo         = "set-me"
  value_wo_version = 1
}

resource "aws_ssm_parameter" "stripe_webhook_secret" {
  name             = "/capture-to-text/stripe-webhook-secret"
  type             = "SecureString"
  value_wo         = "set-me"
  value_wo_version = 1
}
