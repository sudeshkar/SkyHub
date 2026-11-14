#!/usr/bin/env bash
# Writes .env for a server (ADR-0085) with a fresh random value for every key and password. Run once, from the repository root:
#   ./deploy/make-env.sh skyhub.example.com
# It refuses to overwrite an existing .env: a new SKYHUB_PII_KEY would make every saved booking unreadable,
# and a new POSTGRES_PASSWORD would no longer match the database that already exists.
set -euo pipefail
cd "$(dirname "$0")/.."

domain="${1:-}"
if [[ -z "$domain" || "$domain" == http* || "$domain" == */* ]]; then
  echo "Usage: ./deploy/make-env.sh your-domain   (just the name, for example skyhub.example.com or 203-0-113-5.sslip.io)" >&2
  exit 1
fi
if [[ -e .env ]]; then
  echo ".env already exists; not touching it (see the comment at the top of this script)." >&2
  exit 1
fi

key() { openssl rand -base64 32; }
password() { openssl rand -hex 16; }  # 32 characters, letters and digits only, so it is easy to copy

admin_password="$(password)"
umask 077  # only the owner may read the file
cat > .env <<EOF
# Written by deploy/make-env.sh on $(date -u +%Y-%m-%d). Never commit or share this file. Back it up somewhere safe:
# without SKYHUB_PII_KEY the travellers' details in the database cannot be read.
SKYHUB_DOMAIN=${domain}
SKYHUB_SITE_URL=https://${domain}
POSTGRES_PASSWORD=$(password)

SKYHUB_PII_KEY=$(key)
SKYHUB_MANAGE_TOKEN_KEY=$(key)
SKYHUB_BOARDING_PASS_KEY=$(key)
SKYHUB_LINK_TOKEN_KEY=$(key)

SKYHUB_BOOTSTRAP_ADMIN_USERNAME=admin
SKYHUB_BOOTSTRAP_ADMIN_PASSWORD=${admin_password}

SKYHUB_AGENT_API_TOKEN=$(openssl rand -hex 32)
SKYHUB_AGENT_USERNAME=agent.ops
SKYHUB_AGENT_PASSWORD=$(password)
# Optional: a free key from https://console.groq.com, and SKYHUB_AGENT_PLANNER=groq, to let the language model plan.
GROQ_API_KEY=
SKYHUB_AGENT_PLANNER=first-fit

GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$(password)
EOF

echo "Wrote .env for https://${domain}"
echo "Sign in to https://${domain}/ops as 'admin' with: ${admin_password}"
echo "(It is also in .env. Save it in your password manager now.)"
