output "instance_id" {
  value = aws_instance.server.id
}

# Open this in a browser (alice/alice). No tunnels needed any more.
output "app_url" {
  value = "https://${local.app_host}"
}

# Paste this into Stripe's dashboard as the webhook endpoint.
output "stripe_webhook_url" {
  value = "https://${local.app_host}/api/webhooks/stripe"
}

output "shell" {
  value = "aws ssm start-session --target ${aws_instance.server.id}"
}

output "server_ip" {
  description = "The fixed IP that Atlas's access list allows, and that the sslip.io names point at."
  value       = aws_eip.server.public_ip
}

output "bucket" {
  value = aws_s3_bucket.images.bucket
}
