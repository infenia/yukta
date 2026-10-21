#!/usr/bin/env bash
# Start or stop a Yukta server from a local container image (release/build-image.sh <version>).
# Usually reached through yukta-server.sh. The image is distroless: it has no JDK or Gradle, so
# this serves the Quickstart/smoke path; yukta-check.sh's gradle stages need a native, jar or release server.
#
# Usage: yukta-docker.sh start <name> | stop <name>
#   YUKTA_SERVER_IMAGE  the image (default: the newest local ghcr.io/infenia/yukta tag)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

action="${1:?usage: yukta-docker.sh start|stop <name>}"
name="${2:?usage: yukta-docker.sh start|stop <name>}"
valid_name "$name"
need docker
container="yukta-dev-$name"

case "$action" in
  start)
    image="${YUKTA_SERVER_IMAGE:-$(docker images --format '{{.Repository}}:{{.Tag}}' ghcr.io/infenia/yukta | head -1)}"
    [[ -n "$image" ]] || die "no local image; run release/build-image.sh <version> or set YUKTA_SERVER_IMAGE"
    need curl
    mkdir -p "$STATE_DIR"
    port="$(free_port)"
    url="http://localhost:$port"
    docker run -d --name "$container" -p "127.0.0.1:$port:8080" "$image" >/dev/null
    touch "$STATE_DIR/$name.container"
    cat >"$STATE_DIR/$name.env" <<ENV
YUKTA_DEV_NAME=$name
YUKTA_DEV_MODE=docker
YUKTA_SERVER_URL=$url
ENV
    cp "$STATE_DIR/$name.env" "$STATE_DIR/current.env"
    # wait_healthy watches a pid file; a container has none, so poll the endpoint here.
    for _ in $(seq 1 30); do
      curl -fsS "$url/actuator/health" >/dev/null 2>&1 && { echo "ready: export YUKTA_SERVER_URL=$url (image $image)"; exit 0; }
      sleep 2
    done
    docker logs "$container" >&2 || true
    die "container did not become healthy in 60s"
    ;;
  stop)
    [[ -f "$STATE_DIR/$name.env" ]] || die "no state for '$name' in $STATE_DIR"
    docker rm -f "$container" >/dev/null 2>&1 || true
    rm -f "$STATE_DIR/$name.container"
    forget_server "$name"
    echo "stopped $name"
    ;;
  *) die "unknown action '$action' (use start or stop)" ;;
esac
