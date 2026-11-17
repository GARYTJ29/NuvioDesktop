@echo off
REM ---------------------------------------------------------------------------
REM Builds Nuvio Desktop for Windows and produces dist\NuvioDesktop\NuvioDesktop.exe
REM
REM Usage:  scripts\build.bat [release] [clean] [msi] [safe]
REM   release  use createReleaseDistributable instead of createDistributable
REM   clean    run :composeApp:clean first (forces a full rebuild)
REM   msi      also build the MSI installer (needs WiX Toolset 3.x on PATH)
REM   safe     disable fast incremental Kotlin compilation (see below)
REM
REM Fast incremental compilation: by default Kotlin 2.x throws away incremental
REM state and recompiles the whole module (~300k lines, ~90s) whenever a file
REM in a shared source set (commonMain, fullCommonMain, ...) changes - see
REM KT-62686. Dev builds opt in to Kotlin's incremental optimizations for
REM multiplatform, so a one-line edit recompiles only the affected files (~1s).
REM The upstream rebuild exists because expect/actual edits can in rare cases
REM compile incrementally to a stale result. If a build behaves strangely after
REM changing an expect/actual declaration, rerun with "clean". Release builds
REM always use the safe mode.
REM
REM Requirements (see scripts\README-build.md):
REM   - JDK 17+ (JAVA_HOME or java on PATH)
REM   - Visual Studio Build Tools with the C++ x64 toolchain (player bridge DLL)
REM   - Microsoft.Web.WebView2 NuGet package in %USERPROFILE%\.nuget\packages
REM ---------------------------------------------------------------------------
setlocal EnableExtensions

set "ROOT=%~dp0.."
pushd "%ROOT%" || exit /b 1
set "ROOT=%CD%"

set "TASK=createDistributable"
set "RELEASE_DIR=main"
set "DO_CLEAN=0"
set "DO_MSI=0"
set "FAST_IC=1"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="release" (set "TASK=createReleaseDistributable" & set "RELEASE_DIR=main-release" & set "FAST_IC=0")
if /i "%~1"=="clean" set "DO_CLEAN=1"
if /i "%~1"=="msi" set "DO_MSI=1"
if /i "%~1"=="safe" set "FAST_IC=0"
shift
goto parse
:parsed

set "GRADLE_ARGS="
if "%FAST_IC%"=="1" set "GRADLE_ARGS=-Pkotlin.internal.incremental.enableUnsafeOptimizationsForMultiplatform=true"

REM --- Java check -------------------------------------------------------------
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" (
        echo [build] JAVA_HOME is set but "%JAVA_HOME%\bin\java.exe" does not exist.
        goto :fail
    )
) else (
    where java >nul 2>nul || (
        echo [build] Java not found. Install JDK 17+ and set JAVA_HOME.
        goto :fail
    )
)

REM --- Backend config (local-only, git-ignored) ----------------------------------
REM Reads KEY=VALUE lines from scripts\nuvio.local.env (see nuvio.local.env.example)
REM and exports them so Gradle bakes them into SupabaseConfig. Lines starting with
REM # are comments. Without this file the build has no backend: no account sync,
REM no Continue Watching from the cloud, no profile avatars.
set "LOCAL_ENV=%ROOT%\scripts\nuvio.local.env"
if exist "%LOCAL_ENV%" (
    echo [build] Loading backend config from scripts\nuvio.local.env
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%LOCAL_ENV%") do (
        if not "%%B"=="" set "%%A=%%B"
    )
)
if not defined NUVIO_SUPABASE_URL (
    echo [build] WARNING: NUVIO_SUPABASE_URL is not set. The build will have no backend.
    echo [build]          Copy scripts\nuvio.local.env.example to scripts\nuvio.local.env and fill it in.
)

REM --- Gradle build -----------------------------------------------------------
REM clean runs in the same Gradle invocation, which saves a second
REM daemon/configuration round trip.
set "TASKS=:composeApp:%TASK%"
if "%DO_CLEAN%"=="1" set "TASKS=:composeApp:clean %TASKS%"

if "%FAST_IC%"=="1" (echo [build] Fast incremental Kotlin compilation: on) else (echo [build] Fast incremental Kotlin compilation: off)
echo [build] Running %TASKS% ...
call "%ROOT%\gradlew.bat" %TASKS% %GRADLE_ARGS% || goto :fail

set "APP_SRC=%ROOT%\composeApp\build\compose\binaries\%RELEASE_DIR%\app\Nuvio"
if not exist "%APP_SRC%\Nuvio.exe" (
    echo [build] Expected "%APP_SRC%\Nuvio.exe" was not produced.
    goto :fail
)

REM --- Stage dist\NuvioDesktop with the launcher renamed -----------------------
REM jpackage's launcher reads app\<exe name>.cfg, so the .cfg must be renamed too.
REM /MIR copies only files that changed since the last build (usually one jar)
REM and removes stale ones, instead of re-copying the whole ~300MB image. The
REM launcher and its .cfg are excluded from the mirror - excluded files are
REM also never purged - and copied under their new names afterwards.
set "DIST=%ROOT%\dist\NuvioDesktop"
echo [build] Staging %DIST% ...
robocopy "%APP_SRC%" "%DIST%" /MIR /XF Nuvio.exe Nuvio.cfg NuvioDesktop.exe NuvioDesktop.cfg /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo [build] robocopy failed.
    goto :fail
)
REM jpackage marks the launcher read-only, which copy /y cannot overwrite.
if exist "%DIST%\NuvioDesktop.exe" del /f /q "%DIST%\NuvioDesktop.exe" || goto :fail
copy /y "%APP_SRC%\Nuvio.exe" "%DIST%\NuvioDesktop.exe" >nul || goto :fail
copy /y "%APP_SRC%\app\Nuvio.cfg" "%DIST%\app\NuvioDesktop.cfg" >nul || goto :fail
REM Older builds staged a plain copy; drop any leftover original names.
if exist "%DIST%\Nuvio.exe" del /f /q "%DIST%\Nuvio.exe"
if exist "%DIST%\app\Nuvio.cfg" del /q "%DIST%\app\Nuvio.cfg"

REM --- Optional MSI -------------------------------------------------------------
if "%DO_MSI%"=="1" (
    if /i "%TASK%"=="createReleaseDistributable" (set "MSI_TASK=packageReleaseMsi") else (set "MSI_TASK=packageMsi")
    echo [build] Running :composeApp:%MSI_TASK% ...
    call "%ROOT%\gradlew.bat" :composeApp:%MSI_TASK% %GRADLE_ARGS% || goto :fail
)

echo.
echo [build] Done.
echo [build] Executable: %DIST%\NuvioDesktop.exe
popd
endlocal & exit /b 0

:fail
echo.
echo [build] FAILED.
popd
endlocal & exit /b 1
