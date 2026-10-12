#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
mf_preflight
mf_ready
mf_compose ps
mf_log 'HTTPS readiness succeeded. Verify Prometheus targets, backup freshness and an external uptime check using docs/PRODUCTION.md.'
