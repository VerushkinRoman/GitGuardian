@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "JAR="

if defined GIT_GUARDIAN_HOME (
    set "JAR=%GIT_GUARDIAN_HOME%\gitGuardian-all.jar"
    if exist "!JAR!" goto :found
)

set "JAR=%SCRIPT_DIR%build\libs\KMP-Code-Guardian-all.jar"
if exist "!JAR!" goto :found

set "JAR=%USERPROFILE%\.gitGuardian\gitGuardian-all.jar"
if exist "!JAR!" goto :found

echo [error] gitGuardian JAR not found.
echo Build: gradlew shadowJar
exit /b 1

:found
java -Xmx512m -jar "!JAR!" %*
