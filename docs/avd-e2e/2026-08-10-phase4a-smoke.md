# Phase 4.A — AVD smoke test (2026-08-10)

A quick sanity check that Phase 3.A (downloads) didn't regress the
basic app launch. Limited to the PairingScreen because the TV AVD
does not respond to `input tap` (see [android-emulator-e2e-validation
pitfall 20][pitfall-20]).

## TL;DR

**Green.** The APK launches, reaches the PairingScreen, and renders
all expected fields. No crash. No logcat errors. The download
functionality is exercised by the new `DownloadViewModelTest`
(5 tests, JVM-only) — full E2E of the download path needs a
phone-profile AVD (see [Next steps](#next-steps)).

## What was tested

| Step | Result |
|------|--------|
| Repo map (pitfall 17a) | mm-mobile + MusicManager are sibling repos, mm-mobile is the consumer app |
| AVD `mm_test_tv` (emulator-5554) | booted, responsive |
| Backend MM on `:8765` | running, `openapi.json` exposes all expected endpoints |
| APK installed | `com.wtm.musicmanager.debug` (v0.1.0) |
| `am start MainActivity` | launched in 1062ms |
| PairingScreen renders | "Emparejar con MusicManager" + Host field (10.0.2.2) + Port field (8765) + "Iniciar emparejamiento" button |
| No crash | logcat `*:E` clean for the launch window |
| UI dump | `/sdcard/ui.xml` captures the full Compose tree (5.4 KB) |
| Screenshot | 81 KB PNG, non-zero (pitfall 16 fallback not needed) |

## Evidence

- [2026-08-10-phase4a-smoke-initial.png](./2026-08-10-phase4a-smoke-initial.png) — PairingScreen, fresh launch
- [2026-08-10-phase4a-smoke-ui.xml](./2026-08-10-phase4a-smoke-ui.xml) — `uiautomator dump` from same state

UI tree (excerpt):

```xml
<node text='Emparejar con MusicManager' />
<node text='Introduce la dirección del servidor MusicManager de tu escritorio.' />
<node text='10.0.2.2' focused='true' clickable='true' bounds='[96,472][1232,600]' />
<node text='8765' clickable='true' bounds='[1256,472][1824,600]' />
<node text='Iniciar emparejamiento' clickable='false' bounds='[813,676][1107,716]' />
```

## Bugs found

### 1. Deep-link scheme was `vm://pair` instead of `mm://pair`

**Where**:
- `android/src/main/AndroidManifest.xml` line 47
- `android/src/main/kotlin/com/wtm/musicmanager/MainActivity.kt` line 33-35

**Symptom**: `scripts/run-mm-pairing-e2e.py` (which lives in
`mm-mobile/scripts/`) was launching the APK with `am start -d
'mm://pair?...'` — the wrong scheme. The MM deep link filter in the
manifest matched VideoManager's `vm://pair` instead.

**Root cause**: the manifest was likely copy-pasted from a sibling
project (`vm-android`?) without updating the scheme. The
`run-mm-pairing-e2e.py` script assumed the scheme was already `mm` —
which meant E2E pairing had been failing silently against this APK,
and the only thing it ever validated was the *Pair* app at
`com.example.mmpair` on the same AVD.

**Fix**: change scheme `vm` → `mm` in both the manifest and the
runtime check. See PR #7.

**Verify after fix**:

```bash
adb -s emulator-5554 shell pm clear com.wtm.musicmanager.debug
adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW \
  -d 'mm://pair?session=TEST&token=T&code=AAA-BBB&host=10.0.2.2&port=8765' \
  com.wtm.musicmanager.debug
adb -s emulator-5554 shell dumpsys activity activities | grep topResumed
# expect: ...com.wtm.musicmanager.debug/.MainActivity
# expect: (no SecurityException in logcat)
```

### 2. TV AVD does not respond to `input tap`

**Where**: `mm_test_tv` (system image `google-tv;arm64-v8a`).

**Symptom**: `adb shell input tap 960 696` (centre of the
"Iniciar emparejamiento" button) returns without error but the UI
does not change. `input touchscreen tap` behaves identically. The
`mm_test_tv` AVD has no touchscreen input device — it expects
D-pad navigation.

**Workarounds attempted**:
- `KEYCODE_DPAD_DOWN` — focus stayed on the Host field
  (`OutlinedTextField` doesn't move focus on D-pad unless
  explicitly wired).
- `KEYCODE_DPAD_CENTER` on the focused field — typed "a" into the
  Host field instead of advancing.

**Conclusion**: A full E2E for mm-mobile needs a phone-profile AVD
(per [pitfall 20 option C][pitfall-20]).

## Next steps

1. **Create a phone-profile AVD** for proper touch-driven E2E:
   ```bash
   sdkmanager 'system-images;android-34;google_apis;arm64-v8a'
   echo no | avdmanager create avd \
       -n mm_test_phone \
       -k 'system-images;android-34;google_apis;arm64-v8a' \
       -d 'pixel_6'
   ```
2. **Re-run `scripts/run-mm-pairing-e2e.py`** with `APP_PKG=com.wtm.musicmanager.debug` —
   the script will now actually hit the right app (after the scheme fix).
3. **Add download E2E**: tap-download on an AlbumDetail track, wait
   for the spinner, confirm CloudDone, tap-play, kill backend,
   confirm audio continues (per Phase 3.A's offline-playback path).
4. **Consider deleting `run-mm-pairing-e2e.py`** if it's been
   silently testing the wrong app for weeks — historical evidence
   may be misleading.

## References

- [android-emulator-e2e-validation] skill, pitfalls 17 (pkg collision),
 17a (repo map), 17b (don't claim shipped without git log), 18
 (absolute adb path), 19 (mtime verify), 20 (TV AVD no touch).
- mm-mobile commit `9006fee` (Phase 3.A merge) — the work this
 smoke validated.
- mm-mobile commit (this branch) — the scheme fix.

[pitfall-20]: ../../.hermes/skills/software-development/android-emulator-e2e-validation/SKILL.md#20-google-tv-avd-does-not-respond-to-input-tap--use-d-pad-or-uiautomator-dump-for-structural-verification
[android-emulator-e2e-validation]: ../../.hermes/skills/software-development/android-emulator-e2e-validation/SKILL.md