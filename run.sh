#!/usr/bin/env bash
# Wrapper — see scripts/run.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${ROOT}/scripts/run.sh" "$@"
