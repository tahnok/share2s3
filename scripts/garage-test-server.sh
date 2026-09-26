#!/usr/bin/env bash
# Starts a throwaway single-node Garage server for the integration tests and prints the
# environment variables GarageIntegrationTest needs. Usage:
#
#   eval "$(scripts/garage-test-server.sh)"
#   ./gradlew testDebugUnitTest
#
# GARAGE_BIN may point at an existing garage binary; otherwise one is downloaded.
set -euo pipefail

GARAGE_VERSION="${GARAGE_VERSION:-v2.4.1}"
WORK_DIR="${GARAGE_WORK_DIR:-$(mktemp -d)}"
BUCKET="share2s3-test"
KEY_ID="GK0123456789abcdef01234567"
SECRET="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

if [[ -z "${GARAGE_BIN:-}" ]]; then
  GARAGE_BIN="$WORK_DIR/garage"
  curl -sSfL -o "$GARAGE_BIN" \
    "https://garagehq.deuxfleurs.fr/_releases/$GARAGE_VERSION/x86_64-unknown-linux-musl/garage"
  chmod +x "$GARAGE_BIN"
fi

CONF="$WORK_DIR/garage.toml"
cat > "$CONF" <<EOF
metadata_dir = "$WORK_DIR/meta"
data_dir = "$WORK_DIR/data"
db_engine = "sqlite"
replication_factor = 1
rpc_bind_addr = "127.0.0.1:3901"
rpc_public_addr = "127.0.0.1:3901"
rpc_secret = "$(openssl rand -hex 32)"

[s3_api]
s3_region = "garage"
api_bind_addr = "127.0.0.1:3900"
root_domain = ".s3.garage.localhost"

[s3_web]
bind_addr = "127.0.0.1:3902"
root_domain = ".web.garage.localhost"
EOF

garage() { "$GARAGE_BIN" -c "$CONF" "$@"; }

"$GARAGE_BIN" -c "$CONF" server > "$WORK_DIR/garage.log" 2>&1 &
echo "$!" > "$WORK_DIR/garage.pid"

for _ in $(seq 1 50); do
  garage status > /dev/null 2>&1 && break
  sleep 0.2
done
if ! garage status > /dev/null 2>&1; then
  echo "Garage failed to start:" >&2
  cat "$WORK_DIR/garage.log" >&2
  exit 1
fi

{
  NODE_ID="$(garage node id -q | cut -d@ -f1)"
  garage layout assign -z dc1 -c 1G "$NODE_ID"
  garage layout apply --version 1
  garage bucket create "$BUCKET"
  garage key import --yes -n share2s3-test "$KEY_ID" "$SECRET"
  garage bucket allow --read --write "$BUCKET" --key "$KEY_ID"
  garage bucket website --allow "$BUCKET"
} >&2

cat <<EOF
export GARAGE_ENDPOINT=http://127.0.0.1:3900
export GARAGE_WEB_ENDPOINT=http://127.0.0.1:3902
export GARAGE_WEB_ROOT_DOMAIN=.web.garage.localhost
export GARAGE_BUCKET=$BUCKET
export GARAGE_ACCESS_KEY=$KEY_ID
export GARAGE_SECRET_KEY=$SECRET
export GARAGE_PID=$(cat "$WORK_DIR/garage.pid")
EOF
