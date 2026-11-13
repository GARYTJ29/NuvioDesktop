# Building Nuvio Desktop on Windows

```bat
scripts\build.bat            :: debug-ish distributable  -> dist\NuvioDesktop\NuvioDesktop.exe
scripts\build.bat release    :: release distributable
scripts\build.bat clean      :: full rebuild
scripts\build.bat msi        :: also builds the MSI installer (needs WiX 3.x)
scripts\run.bat              :: run from Gradle without packaging
```

## What it does

1. Runs `:composeApp:createDistributable` (or `createReleaseDistributable`). Gradle compiles the
   Kotlin code, builds `player_bridge.dll` with MSVC against WebView2, bundles libmpv and the
   engine DLL, and jpackage produces `composeApp\build\compose\binaries\main\app\Nuvio\Nuvio.exe`.
2. Copies that folder to `dist\NuvioDesktop\` and renames `Nuvio.exe` to `NuvioDesktop.exe`
   (and `app\Nuvio.cfg` to `app\NuvioDesktop.cfg`, which the launcher requires).

Ship or run the whole `dist\NuvioDesktop` folder; the exe does not work alone.

## Requirements

- JDK 17+ (`JAVA_HOME` or `java` on PATH)
- Visual Studio Build Tools with the "C++ x64 build tools" workload
- `Microsoft.Web.WebView2` NuGet package in `%USERPROFILE%\.nuget\packages`
  (or pass `-Pnuvio.webview2.dir=...` via `gradle.properties`)
- `composeApp\src\desktopMain\native\windows\runtime\libmpv-2.dll` (already in the repo)
- `composeApp\src\desktopMain\native\windows\thumbfast\`: seek-bar hover thumbnails.
  `thumbfast.lua` (in the repo) is a fork of [thumbfast](https://github.com/po5/thumbfast)
  (MPL 2.0) with a background timeline cache. `mpv.exe` (~113 MB, mpv is GPL/LGPL) is the
  standalone player it spawns to decode frames. It is **not** in the repo (git-ignored), so put
  a Windows `mpv.exe` build in that folder yourself. Both are bundled flat next to `libmpv-2.dll`.
  If either is missing the player still works, just without thumbnails. Set
  `NUVIO_THUMBFAST=0` to turn previews off at runtime.
- WiX Toolset 3.x only for the `msi` option
