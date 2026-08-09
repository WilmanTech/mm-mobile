# AVD E2E Pairing Test

End-to-end validation of the MusicManager pairing flow from a real Android
client (a headless AVD) against the FastAPI backend on `127.0.0.1:8765`.

## What this proves

That the `/api/pairing/{start,status,confirm}` endpoints actually round-trip
with a real Android process — the deep-link parsing, the GET-with-query-param
status poll, the 2-second polling cadence, and the post-confirm UI state
transition all work end-to-end.

## How to run

Prerequisites:
- MusicManager backend running on `:8765` (`./run-dev.sh` in the MM repo)
- Android SDK installed (`~/Library/Android/sdk`)
- `mm_test_tv` AVD already created (one-time setup, see below)
- `mm-pairing-e2e` APK built and installed (one-time, see below)

Then:
```bash
python3 scripts/run-mm-pairing-e2e.py
```

The script:
1. `POST /api/pairing/start` → gets `session_id`, `token`, `code`
2. Launches the APK with `am start -a VIEW -d 'mm://pair?session=...&...'`
3. Captures `01-pre-confirm.png` (waiting for approval)
4. `POST /api/pairing/confirm` (simulating the desktop UI)
5. Captures `02-post-confirm.png` (paired state with token shown)
6. Asserts the two screenshots differ — if they don't, the UI didn't flip

## Expected output

```
=== SUCCESS ===
  pre  screenshot: .../01-pre-confirm.png (~60 KB)
  post screenshot: .../02-post-confirm.png (~70 KB)
  delta: ~8 KB
  session_id: <from /start>
```

The post-confirm screenshot has a green (`#22C55E`) token line; the
pre-confirm one doesn't.

## One-time setup

### 1. Create the AVD
```bash
export ANDROID_HOME=~/Library/Android/sdk
avdmanager create avd -n mm_test_tv \
    -k 'system-images;android-34;google-tv;arm64-v8a' \
    -d 'tv_1080p' --force
sed -i '' 's/^hw.gpu.enabled=no/hw.gpu.enabled=yes/' \
    ~/.android/avd/mm_test_tv.avd/config.ini
sed -i '' 's/^hw.gpu.mode=auto/hw.gpu.mode=swiftshader_indirect/' \
    ~/.android/avd/mm_test_tv.avd/config.ini
```

### 2. Boot the AVD headless
```bash
$ANDROID_HOME/emulator/emulator -avd mm_test_tv \
    -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot \
    -netdelay none -netspeed full &
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed
# wait until it returns "1"
```

### 3. Build and install the APK
The APK source lives in `/tmp/mm-pairing-e2e/` (sibling to this repo).
```bash
cd /tmp/mm-pairing-e2e
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r \
    app/build/outputs/apk/debug/app-debug.apk
```

### 4. Forward the backend port into the AVD
```bash
adb -s emulator-5554 reverse tcp:8765 tcp:8765
```

This makes `http://10.0.2.2:8765` from the AVD land on the host's
127.0.0.1:8765 where uvicorn is listening.

## Captures

- `01-pre-confirm.png` — AVD after `am start`, before `/confirm`
- `02-post-confirm.png` — AVD ~5s after `/confirm`, after the next poll

## What this does NOT validate

This is a thin client only for the pairing handshake. The mm-mobile KMP
client itself (`shared/src/commonMain/...`) is exercised by jvmTest, not by
this AVD flow. The APK here is intentionally minimal so it builds fast
(<60s) and the failure modes are easy to read.
