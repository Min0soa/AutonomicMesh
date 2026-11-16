@echo off
REM Démarre les 3 services, chacun dans sa propre fenêtre (processus Java indépendant)
cd /d "%~dp0\.."
set JAR=mesh-services\target\mesh-services-all.jar
if not exist "%JAR%" (
  echo JAR introuvable : lancez d'abord "mvn package"
  exit /b 1
)
start "CalcService 50051"    java -cp "%JAR%" ca.usherbrooke.ift605.mesh.services.CalcServer 50051
start "StorageService 50052" java -cp "%JAR%" ca.usherbrooke.ift605.mesh.services.StorageServer 50052
start "NotifService 50053"   java -cp "%JAR%" ca.usherbrooke.ift605.mesh.services.NotifServer 50053
