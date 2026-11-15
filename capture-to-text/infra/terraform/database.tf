# Replaces the mongo container: the data now lives in a free MongoDB Atlas cluster (M0),
# which Atlas runs on its own AWS servers in the same region. Atlas is reached over the
# internet, so the only ways in are this server's fixed IP plus the database user's password.

resource "mongodbatlas_project" "main" {
  name   = "capture-to-text"
  org_id = var.atlas_org_id
}

resource "mongodbatlas_advanced_cluster" "main" {
  project_id   = mongodbatlas_project.main.id
  name         = "capture-to-text"
  cluster_type = "REPLICASET"

  # M0 = the free shared tier. "TENANT" means "a slice of a server Atlas shares between
  # free users"; backing_provider_name says that server is on AWS.
  replication_specs = [
    {
      region_configs = [
        {
          electable_specs = {
            instance_size = "M0"
          }
          provider_name         = "TENANT"
          backing_provider_name = "AWS"
          region_name           = "AP_SOUTHEAST_1"
          priority              = 7
        }
      ]
    }
  ]
}

# A fixed public IP for the server. The automatic one changes on every stop/start, which
# would silently lock the server out of Atlas's access list below.
resource "aws_eip" "server" {
  domain   = "vpc"
  instance = aws_instance.server.id
}

# Atlas's firewall: only this one address may connect.
resource "mongodbatlas_project_ip_access_list" "server" {
  project_id = mongodbatlas_project.main.id
  ip_address = aws_eip.server.public_ip
  comment    = "capture-to-text EC2 server"
}

# Password story, three pieces that only work together:
# - random_password is "ephemeral": made fresh in memory on each run and never saved in the
#   state file. Without that, terraform.tfstate would hold the database password in plain text.
# - password_wo / value_wo are "write-only": Terraform hands the value to Atlas / SSM and then
#   forgets it. Without them, an ephemeral value isn't allowed in the resource at all.
# - *_wo_version: write-only values can't be compared with what's already there, so Terraform
#   only sends a new one when this number changes. To change the password, bump BOTH to 2.
# The one place the password survives is the SSM parameter, which the server reads at boot.
ephemeral "random_password" "db" {
  length  = 32
  special = false # letters and digits only, so the password can sit inside a URL unescaped
}

resource "mongodbatlas_database_user" "app" {
  project_id          = mongodbatlas_project.main.id
  auth_database_name  = "admin"
  username            = "capture-to-text-app"
  password_wo         = ephemeral.random_password.db.result
  password_wo_version = 1

  # Read and write the app's one database, nothing else (no admin, no other databases).
  roles {
    role_name     = "readWrite"
    database_name = "capturetotext"
  }
}

# The full connection string, stored encrypted (SecureString) in SSM Parameter Store.
# Only its name goes into user_data; user_data is readable by anything on the server via
# the instance metadata service, so a secret must not travel there.
resource "aws_ssm_parameter" "mongo_uri" {
  name = "/capture-to-text/mongo-uri"
  type = "SecureString"
  # Atlas gives "mongodb+srv://<host>"; this adds user:password@ in front of the host and
  # /capturetotext at the end, so Spring takes the database name from the string itself.
  value_wo = "${replace(
    mongodbatlas_advanced_cluster.main.connection_strings.standard_srv,
    "mongodb+srv://",
    "mongodb+srv://${mongodbatlas_database_user.app.username}:${ephemeral.random_password.db.result}@"
  )}/capturetotext?retryWrites=true&w=majority"
  value_wo_version = 1
}
