@echo off
REM Runs the app straight from Gradle (no packaging). Usage: scripts\run.bat
setlocal
pushd "%~dp0.." || exit /b 1
REM Same fast incremental Kotlin flag as scripts\build.bat (see its header). It
REM must match between the two scripts: a differing value changes the compile
REM task's inputs and forces a full ~90s recompile when switching scripts.
call gradlew.bat :composeApp:run -Pkotlin.internal.incremental.enableUnsafeOptimizationsForMultiplatform=true %*
set "RC=%ERRORLEVEL%"
popd
endlocal & exit /b %RC%
