#!/usr/bin/env bash
# Downloads NVIDIA's DLSS Frame Generation runtime (nvngx_dlssg.dll) from the
# public DLSS SDK (github.com/NVIDIA/DLSS) into vendor/dlss-sdk.
#
# Nuvio's Windows player needs it for frame generation. It is NVIDIA's file
# under NVIDIA's license (vendor/dlss-sdk/LICENSE.txt), so it is not committed
# or bundled in the app jar: scripts\build.bat copies it next to
# dist\NuvioDesktop\NuvioDesktop.exe. Without it the player hides the FG button.
#
#   bash scripts/fetch-dlss-sdk.sh          (Git Bash on Windows works)
#
# DLSS_SDK_TAG picks another SDK release. Keep it in step with the SDK the
# bridge (nuvio_fg.dll) was built against, see MPVKit/Windows/FrameGen.
set -euo pipefail
TAG="${DLSS_SDK_TAG:-v310.9.16}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/dlss-sdk"

if [ ! -d "$DEST/.git" ]; then
    mkdir -p "$DEST"
    git -C "$DEST" init -q
    git -C "$DEST" remote add origin https://github.com/NVIDIA/DLSS.git
    git -C "$DEST" config core.sparseCheckout true
    cat > "$DEST/.git/info/sparse-checkout" <<'LIST'
lib/Windows_x86_64/rel/nvngx_dlssg.dll
LICENSE.txt
LIST
fi
git -C "$DEST" fetch -q --depth 1 --filter=blob:none origin tag "$TAG"
git -C "$DEST" checkout -q "$TAG"

DLL="$DEST/lib/Windows_x86_64/rel/nvngx_dlssg.dll"
if [ ! -f "$DLL" ]; then
    echo "nvngx_dlssg.dll is missing from DLSS SDK $TAG" >&2
    exit 1
fi
echo "DLSS SDK $TAG: $DLL"
