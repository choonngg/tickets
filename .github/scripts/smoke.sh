#!/usr/bin/env bash
set -euo pipefail

# EC2 prerequisites: bash, curl, python3. Never log response bodies or headers.
health_only=false
if [[ ${1:-} == --health-only ]]; then
  health_only=true
  shift
fi
base_url=${1:-http://localhost:80}
attempts=${SMOKE_ATTEMPTS:-12}
delay=${SMOKE_DELAY:-5}
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

check() {
  local method=$1 path=$2 expected=$3 kind=$4 attempt status content_type
  for ((attempt=1; attempt<=attempts; attempt++)); do
    status=000
    content_type=''
    if result=$(curl --disable --silent --connect-timeout 3 --max-time 10 \
      --request "$method" --output "$temp_dir/body" \
      --write-out '%{http_code} %{content_type}' "${base_url%/}${path}"); then
      read -r status content_type <<< "$result"
      if [[ $status == "$expected" ]] && python3 - "$temp_dir/body" "$kind" "$content_type" <<'PY'
import json
import sys
from html.parser import HTMLParser

class SPA(HTMLParser):
    root = False
    html = False

    def handle_starttag(self, tag, attrs):
        self.html |= tag == "html"
        self.root |= tag == "div" and dict(attrs).get("id") == "root"

try:
    body = open(sys.argv[1], encoding="utf-8").read()
    kind, content_type = sys.argv[2:]
    if kind == "spa":
        parser = SPA()
        parser.feed(body)
        valid = content_type.split(";", 1)[0] == "text/html" and parser.html and parser.root
    else:
        data = json.loads(body)
        valid = content_type.split(";", 1)[0] == "application/json"
        if kind == "health":
            valid &= isinstance(data, dict) and data.get("status") == "UP"
        elif kind == "concerts":
            valid &= isinstance(data, list)
        elif kind == "jwks":
            valid &= isinstance(data, dict) and isinstance(data.get("keys"), list) and bool(data["keys"])
        elif kind == "refresh":
            valid &= (isinstance(data, dict) and data.get("code") == "REAUTH_REQUIRED"
                      and isinstance(data.get("message"), str) and bool(data["message"]))
    sys.exit(0 if valid else 1)
except (ValueError, OSError, TypeError):
    sys.exit(1)
PY
      then
        echo "PASS $method $path HTTP $status (attempt $attempt/$attempts)"
        return 0
      fi
    fi
    echo "RETRY $method $path HTTP $status (attempt $attempt/$attempts)"
    if ((attempt < attempts)); then sleep "$delay"; fi
  done
  echo "FAIL $method $path: expected HTTP $expected and $kind contract"
  return 1
}

failed=0
check GET /actuator/health 200 health || failed=1
if [[ $health_only == false ]]; then
  check GET / 200 spa || failed=1
  check GET /api/concerts 200 concerts || failed=1
  check GET /.well-known/jwks.json 200 jwks || failed=1
  check GET /api/queue/.well-known/jwks.json 200 jwks || failed=1
  check POST /api/auth/refresh 401 refresh || failed=1
fi
exit "$failed"
