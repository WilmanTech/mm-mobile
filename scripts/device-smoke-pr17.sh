#!/bin/bash
# mm-mobile iPhone device-deploy: end-to-end script for PR #17
#
# Prereqs (one-time):
#   - iPhone 11 (iPhone12,1) paired + trusted in Xcode
#   - MusicManager backend running on Mac at 192.168.1.201:8765 with library open
#   - Same WiFi network as Mac
#   - iPhone unlocked + MusicManager permission for Local Network granted
#
# What this does:
#   1. Verifies backend + library is reachable
#   2. Pairs iPhone (POST /api/pairing/start → 4-word code → /confirm-by-code)
#   3. Installs the PR #17 .app via devicectl
#   4. Launches app with -MM_TEST_TOKEN bypass
#   5. Prints test checklist
#
# Usage:
#   cd ~/projects/mm-mobile
#   ./scripts/device-smoke-pr17.sh

set -e

DEVICE_UDID="1A21A603-811B-552F-9FE4-912C75F7D007"
BUNDLE_ID="com.wtm.musicmanager.MusicManager"
APP_PATH="$HOME/Library/Developer/Xcode/DerivedData/MusicManager-device/Build/Products/Debug-iphoneos/MusicManager.app"
BACKEND="http://192.168.1.201:8765"
PORT=8765
DEVICE_NAME="Moi iPhone (smoke PR17)"

# ── 1. Sanity checks ──────────────────────────────────────────────────────────
echo "═══════════════════════════════════════════════════════════════"
echo " mm-mobile PR #17 device smoke"
echo "═══════════════════════════════════════════════════════════════"

echo ""
echo "▸ iPhone status..."
DEVICE_STATE=$(xcrun devicectl list devices 2>&1 | grep "$DEVICE_UDID" | awk '{print $NF}')
if [ "$DEVICE_STATE" != "available" ] && [ "$DEVICE_STATE" != "(paired)" ]; then
    echo "  ✗ iPhone is '$DEVICE_STATE'. Unlock it + tap 'Trust' in iOS VPN & Device Management."
    echo "    Then re-run this script."
    exit 1
fi
echo "  ✓ iPhone available"

echo ""
echo "▸ App binary..."
if [ ! -d "$APP_PATH" ]; then
    echo "  ✗ .app not found at $APP_PATH"
    echo "    Run: cd ~/projects/mm-mobile/ios && xcodebuild -workspace MusicManager.xcworkspace -scheme MusicManager -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' -allowProvisioningUpdates -allowProvisioningDeviceRegistration -derivedDataPath ~/Library/Developer/Xcode/DerivedData/MusicManager-device build"
    exit 1
fi
echo "  ✓ App at $APP_PATH"

echo ""
echo "▸ Backend..."
if ! curl -sS --max-time 3 "$BACKEND/api/library/recent/tracks" -o /dev/null -w "%{http_code}" 2>&1 | grep -qE "200|401"; then
    echo "  ✗ Backend not reachable at $BACKEND"
    echo "    Start: cd ~/projects/MusicManager/backend && source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8765"
    exit 1
fi
echo "  ✓ Backend responding"

echo ""
echo "▸ Library open?..."
RECENT=$(curl -sS --max-time 3 "$BACKEND/api/library/recent/tracks" 2>&1)
if [ -z "$RECENT" ] || [ "$RECENT" = "[]" ]; then
    echo "  ⚠ Library has 0 recent tracks — open one first:"
    echo "    curl -X POST $BACKEND/api/library/open -H 'Content-Type: application/json' -d '{\"path\":\"/tmp/mmsmb/Documents/MyMusic/Foo Fighters\"}'"
    echo "    (or whatever your SMB mount path is)"
    exit 1
fi
echo "  ✓ Library has tracks"

# ── 2. Pair iPhone ────────────────────────────────────────────────────────────
echo ""
echo "▸ Pairing..."
PAIR_RESP=$(curl -sS --max-time 5 -X POST "$BACKEND/api/pairing/start" -H 'Content-Type: application/json' -d '{"device_type":"ios"}')
TOKEN=$(echo "$PAIR_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
CODE=$(echo "$PAIR_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")

echo "  4-word code: $CODE"
CONFIRM_RESP=$(curl -sS --max-time 5 -X POST "$BACKEND/api/pairing/confirm-by-code" -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"device_name\":\"$DEVICE_NAME\",\"device_type\":\"ios\"}")
CONFIRMED=$(echo "$CONFIRM_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','error'))")
if [ "$CONFIRMED" != "confirmed" ]; then
    echo "  ✗ Confirm failed: $CONFIRM_RESP"
    exit 1
fi
echo "  ✓ Token confirmed: ${TOKEN:0:12}..."

# ── 3. Install .app ───────────────────────────────────────────────────────────
echo ""
echo "▸ Installing .app..."
xcrun devicectl device install app --device "$DEVICE_UDID" "$APP_PATH" 2>&1 | tail -2
echo "  ✓ Installed"

# ── 4. Launch with token bypass ───────────────────────────────────────────────
echo ""
echo "▸ Launching..."
xcrun devicectl device terminate app --device "$DEVICE_UDID" "$BUNDLE_ID" 2>&1 | tail -1 || true
sleep 1
xcrun devicectl device process launch --device "$DEVICE_UDID" "$BUNDLE_ID" \
    -MM_TEST_TOKEN "$TOKEN" \
    -MM_TEST_HOST "192.168.1.201" \
    -MM_TEST_PORT "$PORT" 2>&1 | tail -2
echo "  ✓ Launched with token bypass"

# ── 5. Test checklist ─────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo " ✅ App is running on iPhone. Manual test checklist:"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  PHASE 3.B++ — Home + Library load:"
echo "    □ Home shows 'Buenas noches, Moi' + recent tracks"
echo "    □ Tap a track → playback starts"
echo "    □ Mini-player bar appears at bottom"
echo "    □ Tap mini-player → full NowPlayingView opens"
echo ""
echo "  PHASE 3.C — Queue persistence:"
echo "    □ Play a few tracks, build a queue"
echo "    □ Force-quit app (swipe up from app switcher)"
echo "    □ Cold launch — 'Resume queue · <artist>' mini-bar should appear"
echo "    □ Tap resume → playback continues from currentIndex"
echo ""
echo "  PHASE 3.D — Settings:"
echo "    □ Tap 'Ajustes' tab → 6 sections visible:"
echo "      • Dispositivo (nombre + canciones)"
echo "      • Fuente (host + puerto actuales)"
echo "      • Sincronización (estado + última sync + 2 botones)"
echo "      • Descargas (count downloaded + estimated size)"
echo "      • Cuenta (desvincular)"
echo "      • Acerca de (versión + build)"
echo "    □ Tap 'Sincronizar ahora' → 'Última sincronización' updates to 'hace 0 s'"
echo "    □ DEBUG-only 'Marcar primera (debug)' toggle (visible in Descargas)"
echo ""
echo "  PHASE 3.B++ tap-to-play edge cases:"
echo "    □ Tap album in Library → plays first track, queue = album tracks"
echo "    □ Tap 'Aleatorio' (shuffle) → random track starts, queue rebuilt"
echo "    □ In NowPlaying: tap prev/next → queue walks"
echo "    □ Toggle shuffle/repeat → state persists (Phase 3.C bonus)"
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo " When done, capture any issues with:"
echo "   xcrun devicectl device info files --device $DEVICE_UDID --domain-type systemCrashLogs"
echo "═══════════════════════════════════════════════════════════════"