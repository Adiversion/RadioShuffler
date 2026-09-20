#!/usr/bin/env python3
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

try:
    import yaml
except ImportError:
    yaml = None

def main():
    print("=== Running RadioShuffler Pre-Push Verification ===")
    errors = 0

    # 1. Verify Workflow YAML files
    print("\n[1/4] Checking GitHub Actions workflow YAML syntax...")
    if yaml is None:
        print("  [WARN] pyyaml not installed. Run 'pip install pyyaml' for YAML validation.")
    else:
        workflows = glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml")
        for wf in workflows:
            try:
                with open(wf, "r", encoding="utf-8") as f:
                    yaml.safe_load(f)
                print(f"  [PASS] {wf}")
            except Exception as e:
                print(f"  [FAIL] {wf}: {e}")
                errors += 1

    # 2. Verify Android XML resources
    print("\n[2/4] Checking Android XML syntax...")
    xml_files = glob.glob("app/src/main/**/*.xml", recursive=True)
    for xf in xml_files:
        try:
            ET.parse(xf)
            print(f"  [PASS] {xf}")
        except Exception as e:
            print(f"  [FAIL] {xf}: {e}")
            errors += 1

    # 3. Check Kotlin source files for basic syntax sanity
    print("\n[3/4] Checking Kotlin source file balance (braces/parentheses)...")
    kt_files = glob.glob("app/src/main/**/*.kt", recursive=True) + glob.glob("*.gradle.kts") + glob.glob("app/*.gradle.kts")
    for kf in kt_files:
        try:
            with open(kf, "r", encoding="utf-8") as f:
                content = f.read()
            # Simple bracket match check
            stack = []
            pairs = {')': '(', '}': '{', ']': '['}
            in_str = False
            str_char = ''
            escaped = False
            for line_no, line in enumerate(content.splitlines(), 1):
                # Skip comments
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("/*"):
                    continue
                for char in line:
                    if escaped:
                        escaped = False
                        continue
                    if char == '\\':
                        escaped = True
                        continue
                    if char in ('"', "'"):
                        if not in_str:
                            in_str = True
                            str_char = char
                        elif str_char == char:
                            in_str = False
                        continue
                    if not in_str:
                        if char in "({[":
                            stack.append((char, line_no))
                        elif char in ")}]":
                            if not stack:
                                raise ValueError(f"Unmatched closing '{char}' at line {line_no}")
                            top, start_line = stack.pop()
                            if top != pairs[char]:
                                raise ValueError(f"Mismatched '{char}' at line {line_no}, expected closing for '{top}' from line {start_line}")
            if stack:
                top, start_line = stack[-1]
                raise ValueError(f"Unclosed '{top}' opened at line {start_line}")
            print(f"  [PASS] {kf}")
        except Exception as e:
            print(f"  [FAIL] {kf}: {e}")
            errors += 1

    # 4. Check Android Manifest permission consistency against Kotlin usage
    print("\n[4/6] Checking Android Manifest permissions against code usage...")
    manifest_path = "app/src/main/AndroidManifest.xml"
    if os.path.exists(manifest_path):
        try:
            tree = ET.parse(manifest_path)
            root = tree.getroot()
            declared_permissions = set()
            for elem in root.findall("uses-permission"):
                name = elem.attrib.get("{http://schemas.android.com/apk/res/android}name", "")
                if name:
                    declared_permissions.add(name)

            kt_sources = ""
            for kf in glob.glob("app/src/main/**/*.kt", recursive=True):
                with open(kf, "r", encoding="utf-8") as f:
                    kt_sources += f.read() + "\n"

            permission_rules = [
                ("WAKE_MODE_", "android.permission.WAKE_LOCK", "ExoPlayer setWakeMode requires WAKE_LOCK"),
                ("newWakeLock", "android.permission.WAKE_LOCK", "PowerManager.newWakeLock requires WAKE_LOCK"),
                ("mediaPlayback", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "Media playback service requires FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
                ("ConnectivityManager", "android.permission.ACCESS_NETWORK_STATE", "Network checking requires ACCESS_NETWORK_STATE"),
            ]

            for token, perm, reason in permission_rules:
                if token in kt_sources and perm not in declared_permissions:
                    print(f"  [FAIL] Missing Manifest Permission: {perm}")
                    print(f"         Reason: {reason}")
                    errors += 1
                elif token in kt_sources:
                    print(f"  [PASS] Verified permission {perm} declared for '{token}'")
        except Exception as e:
            print(f"  [FAIL] Manifest permission check error: {e}")
            errors += 1

    # 5. Check if another GitHub Actions build is currently in progress
    print("\n[5/6] Checking for active GitHub Actions builds...")
    if os.environ.get("ALLOW_CONCURRENT_PUSH") == "1":
        print("  [NOTE] ALLOW_CONCURRENT_PUSH=1 set; skipping active CI build check.")
    else:
        import urllib.request
        import json
        try:
            url = "https://api.github.com/repos/Adiversion/radioshuffler/actions/runs?per_page=5"
            req = urllib.request.Request(url, headers={"User-Agent": "RadioShuffler-PrePush"})
            with urllib.request.urlopen(req, timeout=6) as response:
                if response.status == 200:
                    payload = json.loads(response.read().decode("utf-8"))
                    active_statuses = {"in_progress", "queued", "waiting", "requested"}
                    active_runs = [r for r in payload.get("workflow_runs", []) if r.get("status") in active_statuses]
                    if active_runs:
                        print("  [FAIL] Active GitHub Actions build in progress! Pushing now would create duplicate/conflicting builds.")
                        for run in active_runs:
                            print(f"         - Run ID: {run['id']}")
                            print(f"           Name: {run.get('name', 'Build')}")
                            print(f"           Status: {run.get('status')}")
                            print(f"           URL: {run.get('html_url')}")
                        print("         Please wait for the current build to finish before pushing.")
                        print("         (To force push anyway, set ALLOW_CONCURRENT_PUSH=1)")
                        errors += 1
                    else:
                        print("  [PASS] No active GitHub Actions builds in progress. Safe to push.")
                else:
                    print(f"  [WARN] Unable to reach GitHub Actions API (HTTP {response.status}). Proceeding.")
        except Exception as e:
            print(f"  [NOTE] Could not verify remote CI status ({e}). Proceeding.")

    # 6. Check if local Gradle build is possible
    print("\n[6/6] Checking local compilation tools...")
    java_check = subprocess.run(["where", "java"], capture_output=True, text=True, shell=True)
    if java_check.returncode == 0:
        print(f"  [INFO] Java detected: {java_check.stdout.strip()}")
        gradle_check = subprocess.run(["gradle", "--version"], capture_output=True, text=True, shell=True)
        if gradle_check.returncode == 0:
            print("  [INFO] Running local gradle check...")
            res = subprocess.run(["gradle", "check", "--dry-run"], capture_output=True, text=True, shell=True)
            if res.returncode == 0:
                print("  [PASS] Gradle dry-run check succeeded.")
            else:
                print(f"  [FAIL] Gradle dry-run failed: {res.stderr}")
                errors += 1
    else:
        print("  [NOTE] Java/Android SDK is not installed locally. Builds run in GitHub Actions.")

    print("\n" + "=" * 51)
    if errors == 0:
        print(">>> ALL LOCAL PRE-CHECKS PASSED! Ready to push. <<<")
        return 0
    else:
        print(f">>> {errors} ERROR(S) FOUND! Fix before pushing. <<<")
        return 1

if __name__ == "__main__":
    sys.exit(main())
