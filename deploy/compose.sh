#!/usr/bin/env bash
# docker compose with the three files a server uses (ADR-0085), so nobody types the -f list by hand. Run from the repository root:
#   ./deploy/compose.sh up -d --build      start or update everything
#   ./deploy/compose.sh ps                 what is running, and whether it is healthy
#   ./deploy/compose.sh logs -f booking    follow one service's log
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml "$@"
