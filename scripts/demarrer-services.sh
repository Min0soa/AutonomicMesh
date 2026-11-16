#!/usr/bin/env bash
# Démarre CalcService (50051), StorageService (50052) et NotifService (50053),
# chacun dans son propre processus Java. Journaux et PID dans ./logs/
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=mesh-services/target/mesh-services-all.jar
[ -f "$JAR" ] || { echo "JAR introuvable : lancez d'abord 'mvn package'"; exit 1; }
mkdir -p logs
for svc in "CalcServer 50051" "StorageServer 50052" "NotifServer 50053"; do
  set -- $svc
  java -cp "$JAR" "ca.usherbrooke.ift605.mesh.services.$1" "$2" >> "logs/$1.log" 2>&1 &
  echo $! > "logs/$1.pid"
  echo "$1 démarré sur le port $2 (PID $!)"
done
