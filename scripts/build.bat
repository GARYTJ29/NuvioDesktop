@echo off
REM ---------------------------------------------------------------------------
REM Builds Nuvio Desktop for Windows and produces dist\NuvioDesktop\NuvioDesktop.exe
REM
REM Usage:  scripts\build.bat [release] [clean] [msi]
REM   release  use createReleaseDistributable instead of createDistributable
REM   clean    run :composeApp:clean first (forces a full rebuild)
REM   msi      also build the MSI installer (needs WiX Toolset 3.x on PATH)
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

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="release" (set "TASK=createReleaseDistributable" & set "RELEASE_DIR=main-release")
if /i "%~1"=="clean" set "DO_CLEAN=1"
if /i "%~1"=="msi" set "DO_MSI=1"
shift
goto parse
:parsed

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

REM --- Gradle build -----------------------------------------------------------
if "%DO_CLEAN%"=="1" (
    echo [build] Cleaning...
    call "%ROOT%\gradlew.bat" :composeApp:clean || goto :fail
)

echo [build] Running :composeApp:%TASK% ...
call "%ROOT%\gradlew.bat" :composeApp:%TASK% || goto :fail

set "APP_SRC=%ROOT%\composeApp\build\compose\binaries\%RELEASE_DIR%\app\Nuvio"
if not exist "%APP_SRC%\Nuvio.exe" (
    echo [build] Expected "%APP_SRC%\Nuvio.exe" was not produced.
    goto :fail
)

REM --- Stage dist\NuvioDesktop with the launcher renamed -----------------------
REM jpackage's launcher reads app\<exe name>.cfg, so the .cfg must be renamed too.
set "DIST=%ROOT%\dist\NuvioDesktop"
if exist "%DIST%" rmdir /s /q "%DIST%"
echo [build] Staging %DIST% ...
robocopy "%APP_SRC%" "%DIST%" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo [build] robocopy failed.
    goto :fail
)
ren "%DIST%\Nuvio.exe" NuvioDesktop.exe || goto :fail
ren "%DIST%\app\Nuvio.cfg" NuvioDesktop.cfg || goto :fail

REM --- Optional MSI -------------------------------------------------------------
if "%DO_MSI%"=="1" (
    if /i "%TASK%"=="createReleaseDistributable" (set "MSI_TASK=packageReleaseMsi") else (set "MSI_TASK=packageMsi")
    echo [build] Running :composeApp:%MSI_TASK% ...
    call "%ROOT%\gradlew.bat" :composeApp:%MSI_TASK% || goto :fail
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
