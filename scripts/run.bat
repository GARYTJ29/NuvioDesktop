@echo off
REM Runs the app straight from Gradle (no packaging). Usage: scripts\run.bat
setlocal
pushd "%~dp0.." || exit /b 1
call gradlew.bat :composeApp:run %*
set "RC=%ERRORLEVEL%"
popd
endlocal & exit /b %RC%
