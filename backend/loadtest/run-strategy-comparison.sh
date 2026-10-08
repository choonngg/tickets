#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# 로그/시드 AT는 gitignore 대상이며 다른 사용자가 읽지 못하게 한다.
umask 077
exec python3 compare.py "$@"
