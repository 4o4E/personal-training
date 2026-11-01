#!/usr/bin/env bash
set -euo pipefail
image="$1"
docker run --detach --name training-image-smoke --publish 127.0.0.1:18080:8080 "$image"
trap 'docker rm --force training-image-smoke >/dev/null' EXIT
for attempt in {1..30}; do
  if curl --silent --fail http://127.0.0.1:18080/health >/dev/null; then break; fi
  sleep 1
done
curl --fail --silent --show-error http://127.0.0.1:18080/health | jq --exit-status '.status == "ok"'
status="$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:18080/v1/history)"
test "$status" = 401
token="$(docker exec training-image-smoke cat /data/token)"
curl --fail --silent --show-error --header "Authorization: Bearer $token" http://127.0.0.1:18080/v1/history \
  | jq --exit-status '.sessions == [] and .strengthSets == 0 and .stretchSets == 0'
