#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Run in ~/book-tickets after uploading .env.next, compose, and smoke.sh.
backend_image=$1
nginx_image=$2
mode=${3:-deploy}
compose=(docker compose -f docker-compose.prod.yml)

# Pull both requested tags BEFORE touching the active env/containers.
# Also serves as the existence/access check for manual rollback images.
"${compose[@]}" --env-file .env.next pull
if [[ -f .env ]]; then
  cp .env .env.previous
  chmod 600 .env.previous
else
  rm -f .env.previous
fi

rollback() {
  # ERR is inherited by command substitutions; only the parent may recover.
  [[ $BASHPID == "$$" ]] || exit 1
  trap - ERR
  set +e
  echo "## Failed deployment: backend/nginx status and logs (before rollback)"
  "${compose[@]}" ps --all backend nginx
  "${compose[@]}" logs --tail=200 backend nginx
  if [[ $mode == manual-rollback ]]; then
    echo "Manual rollback failed checks; automatic rollback disabled"
    exit 1
  fi
  echo "Deployment failed; attempting rollback"
  if [[ ! -f .env.previous ]]; then
    echo "Rollback unavailable: no previous deployment"
  elif cp .env.previous .env && "${compose[@]}" up -d --no-build --remove-orphans \
    && bash smoke.sh --health-only http://localhost:80; then
    echo "Rollback succeeded: previous images restored and health UP"
  else
    echo "Rollback failed: operator intervention required"
  fi
  exit 1
}
trap rollback ERR
mv .env.next .env
"${compose[@]}" up -d --no-build --remove-orphans

check_image() {
  local service=$1 expected=$2 container actual
  container=$("${compose[@]}" ps -q "$service")
  [[ -n $container ]]
  actual=$(docker inspect --format '{{.State.Running}} {{.Config.Image}}' "$container")
  if [[ $actual != "true $expected" ]]; then
    echo "FAIL image SHA: $service is not running the requested image $expected"
    return 1
  fi
  echo "PASS image SHA: $service $expected"
}
check_image backend "$backend_image"
check_image nginx "$nginx_image"
bash smoke.sh http://localhost:80
trap - ERR
echo "Deployment passed: image SHA and smoke checks"
# Default prune removes only dangling images; previous SHA tags stay available.
docker image prune -f
