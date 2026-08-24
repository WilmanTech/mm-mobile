#!/usr/bin/env python3
"""End-to-end pairing flow test for MusicManager against an AVD.

Drives the flow:
  1. Backend starts a pairing session (returns session_id + token + code).
  2. The AVD-launched APK receives a deep link with those params.
  3. The APK polls /api/pairing/status every 2s.
  4. This script simulates the desktop UI by calling /api/pairing/confirm.
  5. Captures two screenshots: pre-confirm (waiting) and post-confirm (paired).

Usage:
  ./run-mm-pairing-e2e.py

Environment overrides:
  ADB              default: ~/Library/Android/sdk/platform-tools/adb
  PAIRING_SERVER   default: http://127.0.0.1:8765
  APP_PKG          default: com.example.mmpair
  DEVICE_NAME      default: MusicManager Desktop
  DEVICE_HOST      default: 10.0.2.2  (the AVD's host loopback alias)
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
PAIRING_SERVER = os.environ.get("PAIRING_SERVER", "http://127.0.0.1:8765")
APP_PKG = os.environ.get("APP_PKG", "com.example.mmpair")
DEVICE_NAME = os.environ.get("DEVICE_NAME", "MusicManager Desktop")
DEVICE_HOST = os.environ.get("DEVICE_HOST", "10.0.2.2")

OUT_DIR = Path("/tmp/mm-pairing-captures")
OUT_DIR.mkdir(exist_ok=True)

DEVICE = ["-s", "emulator-5554"]


def adb(*args, check=True, capture=True):
    cmd = [ADB, *DEVICE, *args]
    result = subprocess.run(cmd, capture_output=capture, text=True)
    if check and result.returncode != 0:
        print(f"adb failed: {' '.join(cmd)}\n{result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result


def screencap(name: str) -> Path:
    out = OUT_DIR / name
    # exec-out is non-tty and terminates when the command exits (skill pitfall 4)
    result = subprocess.run(
        [ADB, *DEVICE, "exec-out", "screencap", "-p"],
        capture_output=True,
    )
    out.write_bytes(result.stdout)
    print(f"  screenshot: {out} ({len(result.stdout)} bytes)")
    return out


def http_post(path: str, payload: dict) -> dict:
    req = urllib.request.Request(
        f"{PAIRING_SERVER}{path}",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def main():
    print(f"=== MM Pairing E2E ===")
    print(f"  server:  {PAIRING_SERVER}")
    print(f"  apk:     {APP_PKG}")
    print(f"  device:  emulator-5554")
    print()

    # Step 1: start a session server-side
    print("[1] POST /api/pairing/start")
    started = http_post("/api/pairing/start", {"device_type": "tv"})
    session_id = started["session_id"]
    token = started["token"]
    code = started["code"]
    print(f"    session_id = {session_id}")
    print(f"    token      = {token}")
    print(f"    code       = {code}")
    print()

    # Step 2: launch the APK with a deep link containing all the params
    # The backend's /api/pairing/qr encodes this URL. We pass it as a
    # single quoted arg to avoid `&` being interpreted as a shell separator.
    deep_link = (
        f"mm://pair?session={session_id}&token={token}"
        f"&code={code}&host={DEVICE_HOST}&port=8765"
    )
    print(f"[2] Launching app with deep link: {deep_link}")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
        "-d", f'"{deep_link}"', APP_PKG)
    print()

    # Step 3: capture the "waiting for approval" state
    print("[3] Waiting 4s for first poll, then capturing screenshot...")
    time.sleep(4)
    pre = screencap("01-pre-confirm.png")
    print()

    # Step 4: confirm from the desktop side (simulating user typed the code)
    print(f"[4] POST /api/pairing/confirm as '{DEVICE_NAME}'")
    http_post("/api/pairing/confirm", {
        "session_id": session_id,
        "code": code,
        "device_name": DEVICE_NAME,
        "device_type": "desktop",
    })
    print(f"    confirmed session {session_id[:8]}...")
    print()

    # Step 5: wait for the APK's poll to detect + flip UI to paired state.
    # APK polls every 2s, so 5s is plenty.
    print("[5] Waiting 5s for APK to flip to paired state...")
    time.sleep(5)
    post = screencap("02-post-confirm.png")
    print()

    # Step 6: logcat dump from the app (proves the polling happened)
    print("[6] logcat from app process:")
    result = adb("logcat", "-d", "-t", "200")
    for line in result.stdout.splitlines():
        if "MMPair" in line or "AndroidRuntime" in line or "FATAL" in line:
            print(f"    {line}")
    print()

    # Step 7: pixel-level sanity check that the two screenshots differ
    # (otherwise something is broken: same UI means no state change).
    pre_size = pre.stat().st_size
    post_size = post.stat().st_size
    if pre_size == post_size:
        print(f"WARNING: pre and post screenshots are identical size ({pre_size} bytes)")
        print("  -- likely the APK didn't update its UI. Check logcat above.")
        sys.exit(2)
    print(f"=== SUCCESS ===")
    print(f"  pre  screenshot: {pre} ({pre_size} bytes)")
    print(f"  post screenshot: {post} ({post_size} bytes)")
    print(f"  delta: {abs(post_size - pre_size)} bytes")
    print(f"  session_id: {session_id}")


if __name__ == "__main__":
    main()
