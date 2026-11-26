#!/usr/bin/env bash
set -uo pipefail

attempts=3
download_failures='was not found in any of the following sources|Could not resolve [a-zA-Z0-9]|Could not (GET|HEAD|download)|Downloading from \S+ failed|Read timed out|connect timed out|Connection reset|Connection refused|Remote host terminated the handshake|UnknownHostException|Premature end of Content-Length'

cd "$(dirname "${BASH_SOURCE[0]}")/.."
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if ((status == 0)); then
    exit 0
  fi
  if ((attempt == attempts)) || ! grep -qiE "$download_failures" "$log"; then
    exit "$status"
  fi
  delay=$((attempt * 30))
  echo "::warning::Gradle could not download what the build needs (attempt $attempt of $attempts); trying again in $delay seconds"
  sleep "$delay"
done
