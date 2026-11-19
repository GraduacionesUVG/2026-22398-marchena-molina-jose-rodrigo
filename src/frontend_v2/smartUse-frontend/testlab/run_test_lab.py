#!/usr/bin/env python3
"""
Build the debug app + instrumented tests and run them on Firebase Test Lab.

    python testlab/run_test_lab.py                   # devices from testlab/devices.txt
    python testlab/run_test_lab.py --devices my.txt
    python testlab/run_test_lab.py --skip-build      # reuse the last APKs
    python testlab/run_test_lab.py --models          # list the devices your project can use
    python testlab/run_test_lab.py --targets "class com.example.smartuse_frontend.e2e.PermissionsTest"   # one class (repeatable)
    python testlab/run_test_lab.py --live            # only @Live: the real backend, registers test users

Permissions start ungranted (--grant-permissions none, needs `gcloud components install beta`;
without it the runner warns and Test Lab pre-grants them), as on a participant's phone; the
soak test (@Soak) is always excluded: it waits 20-30 min for natural runs (emulator only).
@Live tests run only with --live.

Needs the Google Cloud CLI logged in to the Firebase project:
    gcloud auth login
    gcloud config set project <firebase-project-id>
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_APK = REPO / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = REPO / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"


def gcloud() -> str:
    exe = shutil.which("gcloud") or shutil.which("gcloud.cmd")
    if not exe:
        sys.exit("gcloud not found: install the Google Cloud CLI and log in (see the docstring).")
    return exe


def has_beta(exe: str) -> bool:
    """`--grant-permissions` exists only in `gcloud beta` (component `beta`)."""
    out = subprocess.run([exe, "components", "list", "--filter=id=beta", "--format=value(state.name)"],
                         capture_output=True, text=True)
    return out.stdout.strip() in ("Installed", "Update Available")


def devices(path: Path) -> list:
    lines = [l.strip() for l in path.read_text(encoding="utf-8").splitlines()]
    found = [l for l in lines if l and not l.startswith("#")]
    if not found:
        sys.exit(f"No devices in {path}")
    return found


def targets(live: bool) -> list:
    """The everyday run skips @Soak (waits 20-30 min for natural runs: emulator
    only) and @Live (talks to the real backend); --live runs only @Live."""
    e2e = "com.example.smartuse_frontend.e2e"
    if live:
        return [f"annotation {e2e}.Live"]
    return [f"notAnnotation {e2e}.Soak", f"notAnnotation {e2e}.Live"]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--devices", type=Path, default=REPO / "testlab/devices.txt")
    p.add_argument("--skip-build", action="store_true")
    p.add_argument("--models", action="store_true")
    p.add_argument("--timeout", default="30m")
    p.add_argument("--targets", action="append", default=[],
                   help='gcloud test target, repeatable, e.g. --targets "class com.example.smartuse_frontend.e2e.PermissionsTest"')
    p.add_argument("--live", action="store_true",
                   help="run only the @Live tests, against the real backend (registers real test users)")
    args = p.parse_args()

    if args.models:
        sys.exit(subprocess.call([gcloud(), "firebase", "test", "android", "models", "list"]))

    if not args.skip_build:
        gradlew = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
        subprocess.run([gradlew, "assembleDebug", "assembleDebugAndroidTest"], cwd=REPO, check=True)
    for apk in (APP_APK, TEST_APK):
        if not apk.exists():
            sys.exit(f"Missing {apk}; build first.")

    exe = gcloud()
    beta = has_beta(exe)
    if not beta:
        print("WARNING: gcloud's beta component is not installed, so permissions can't start ungranted\n"
              "  (--grant-permissions is beta-only). Test Lab will pre-grant them and the\n"
              "  notifications-missing test will be skipped. One-time fix (admin prompt):\n"
              "  gcloud components install beta\n")
    cmd = [exe] + (["beta"] if beta else []) + [
        "firebase", "test", "android", "run",
        "--type", "instrumentation",
        "--app", str(APP_APK),
        "--test", str(TEST_APK),
        "--use-orchestrator",
        "--environment-variables", "clearPackageData=true",
        "--timeout", args.timeout,
        "--test-targets", ",".join(targets(args.live) + args.targets),
    ]
    if beta:
        # Permissions start ungranted, as on a participant's phone; tests grant what they need.
        cmd += ["--grant-permissions", "none"]
    for d in devices(args.devices):
        cmd += ["--device", d]
    print(" ".join(cmd))
    sys.exit(subprocess.call(cmd, cwd=REPO))


if __name__ == "__main__":
    main()
