#!/usr/bin/env bash
# Arrête les services démarrés par demarrer-services.sh
cd "$(dirname "$0")/.."
for f in logs/*Server.pid; do
  [ -f "$f" ] || continue
  pid=$(cat "$f"); kill "$pid" 2>/dev/null && echo "Arrêt de $(basename "$f" .pid) (PID $pid)"
  rm -f "$f"
done
