#!/usr/bin/env bash
# Start an ngrok tunnel from the reserved static dev domain to the local
# Spring Boot server (port 8080).
#
# Usage:
#   ./scripts/ngrok.sh
#
# After starting, the webhook URL to put into Adyen Customer Area is:
#   https://${NGROK_DOMAIN}/webhooks
#
# Override the domain via env var if you ever reserve a new one:
#   NGROK_DOMAIN=other-domain.ngrok-free.dev ./scripts/ngrok.sh

set -euo pipefail

NGROK_DOMAIN="${NGROK_DOMAIN:-coolingly-supercretaceous-branden.ngrok-free.dev}"
PORT="${PORT:-8080}"

if ! command -v ngrok >/dev/null 2>&1; then
  echo "ngrok is not installed. Install it with: brew install ngrok" >&2
  exit 1
fi

echo "Public URL : https://${NGROK_DOMAIN}"
echo "Webhook URL: https://${NGROK_DOMAIN}/webhooks"
echo "Forwarding to http://localhost:${PORT}"
echo "Inspector  : http://localhost:4040"
echo

exec ngrok http "${PORT}" --url="${NGROK_DOMAIN}"
