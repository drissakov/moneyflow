#!/usr/bin/env bash
# Source this file; never enable shell tracing around production secrets.
set -Eeuo pipefail

MF_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
MF_ENV="$MF_ROOT/.env.production"
MF_COMPOSE="$MF_ROOT/infra/compose.production.yaml"

mf_fail() { printf 'MoneyFlow: %s\n' "$*" >&2; exit 1; }
mf_log() { printf 'MoneyFlow: %s\n' "$*"; }

mf_require_tools() {
  local tool
  for tool in bash python3 git curl docker flock; do
    command -v "$tool" >/dev/null || mf_fail "Install required command: $tool"
  done
}

mf_env_value() {
  python3 "$MF_ROOT/scripts/production/validate.py" value "$MF_ENV" "$1"
}

mf_validate() {
  python3 "$MF_ROOT/scripts/production/validate.py" check "$MF_ROOT"
}

mf_compose() {
  docker compose --project-name moneyflow-production --project-directory "$MF_ROOT/infra" \
    --env-file "$MF_ENV" -f "$MF_COMPOSE" "$@"
}

mf_preflight() {
  mf_require_tools
  mf_validate
  python3 "$MF_ROOT/scripts/production/validate.py" host "$MF_ROOT"
  docker info >/dev/null 2>&1 || mf_fail 'Docker daemon is unavailable to this user.'
  local engine_version compose_version
  engine_version="$(docker version --format '{{.Server.Version}}')"
  compose_version="$(docker compose version --short)"
  python3 "$MF_ROOT/scripts/production/validate.py" versions "$engine_version" "$compose_version"
  mf_compose config --quiet
}

mf_ready() {
  local domain
  domain="$(mf_env_value DOMAIN)"
  # Default CA verification is deliberate: never accept a self-signed certificate here.
  curl --fail --silent --show-error --retry 18 --retry-all-errors --retry-delay 5 \
    --connect-timeout 5 --max-time 10 "https://$domain/readyz" >/dev/null
}
