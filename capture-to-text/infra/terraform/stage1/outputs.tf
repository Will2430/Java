output "instance_id" {
  value = aws_instance.server.id
}

# Run each in its own terminal and keep it open; then browse http://localhost:8080.
output "tunnel_api" {
  value = "aws ssm start-session --target ${aws_instance.server.id} --document-name AWS-StartPortForwardingSession --parameters portNumber=8080,localPortNumber=8080"
}

output "tunnel_keycloak" {
  value = "aws ssm start-session --target ${aws_instance.server.id} --document-name AWS-StartPortForwardingSession --parameters portNumber=8180,localPortNumber=8180"
}

output "shell" {
  value = "aws ssm start-session --target ${aws_instance.server.id}"
}
