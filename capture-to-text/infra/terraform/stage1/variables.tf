variable "region" {
  type    = string
  default = "ap-southeast-1"
}

variable "instance_type" {
  description = "8 GiB is the smallest free-plan type that fits two JVMs, Kafka, Keycloak and Mongo together."
  type        = string
  default     = "m7i-flex.large"
}

variable "server_running" {
  description = "false stops the server (only its disk is billed); true starts it again."
  type        = bool
  default     = true
}

variable "repo_url" {
  description = "The server clones this public repo and builds the API and worker images from it."
  type        = string
  default     = "https://github.com/Will2430/Java.git"
}

variable "git_ref" {
  type    = string
  default = "main"
}
