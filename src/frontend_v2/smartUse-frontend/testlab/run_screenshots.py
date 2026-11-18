#!/usr/bin/env python3
"""
Run the screenshot tour (e2e/ScreenshotTour.kt) on Firebase Test Lab and
download its PNGs.

    python testlab/run_screenshots.py                       # MediumPhone API 36, Spanish
    python testlab/run_screenshots.py --device model=SmallPhone.arm,version=33 --device model=...
    python testlab/run_screenshots.py --skip-build
    python testlab/run_screenshots.py --method h_dataDaysAndExport   # one walk only
    python testlab/run_screenshots.py --project <firebase-project-id>   # not gcloud's active project
    python testlab/run_screenshots.py --download-only gs://<bucket>/<dir>   # fetch an earlier run

The tour is skipped in every other run: it needs the instrumentation argument
screenshots=true, which only this script sets. PNGs land in
testlab/screenshots/<run>/<device>/ (git-ignored). Debug build, fake backend;
the [DEBUG] card is hidden for the tour.
"""
import argparse
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_test_lab import APP_APK, REPO, TEST_APK, gcloud, has_beta  # noqa: E402

DEVICE_DIR = "/sdcard/Download/smartuse_screens"
TOUR = "class com.example.smartuse_frontend.e2e.ScreenshotTour"
OUT = REPO / "testlab/screenshots"


def run_streaming(cmd) -> str:
    """Runs cmd, echoing its output, and returns it."""
    proc = subprocess.Popen(cmd, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace")
    lines = []
    for line in proc.stdout:
        print(line, end="")
        lines.append(line)
    proc.wait()
    return "".join(lines)


def download(exe: str, gs_dir: str, dest: Path) -> int:
    gs_dir = gs_dir.rstrip("/")
    listing = subprocess.run([exe, "storage", "ls", "-r", f"{gs_dir}/**"], capture_output=True, text=True)
    pngs = [l.strip() for l in listing.stdout.splitlines()
            if "smartuse_screens/" in l and l.strip().endswith((".png", ".csv"))]
    if not pngs:
        print(f"No PNGs under {gs_dir}\n{listing.stderr}")
        return 0
    for url in pngs:
        # gs://bucket/dir/<device>/artifacts/sdcard/Download/smartuse_screens/<name>.png
        device = url[len(gs_dir) + 1:].split("/")[0]
        target = dest / device
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run([exe, "storage", "cp", url, str(target / url.rsplit("/", 1)[1])], check=True,
                       capture_output=True)
    print(f"{len(pngs)} files (screenshots + exports) in {dest}")
    return len(pngs)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--device", action="append", default=[], help="gcloud --device value, repeatable")
    p.add_argument("--skip-build", action="store_true")
    p.add_argument("--timeout", default="25m")
    p.add_argument("--download-only", metavar="GS_DIR")
    p.add_argument("--method", action="append", default=[],
                   help="only this walk, e.g. h_dataDaysAndExport (repeatable)")
    p.add_argument("--project", help="Firebase project id (default: gcloud's active project)")
    args = p.parse_args()
    exe = gcloud()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")

    if args.download_only:
        sys.exit(0 if download(exe, args.download_only, OUT / stamp) else 1)

    if not args.skip_build:
        gradlew = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
        subprocess.run([gradlew, "assembleDebug", "assembleDebugAndroidTest"], cwd=REPO, check=True)
    for apk in (APP_APK, TEST_APK):
        if not apk.exists():
            sys.exit(f"Missing {apk}; build first.")

    beta = has_beta(exe)
    results_dir = f"screenshots-{stamp}"
    cmd = [exe] + (["beta"] if beta else []) + [
        "firebase", "test", "android", "run",
        "--type", "instrumentation",
        "--app", str(APP_APK),
        "--test", str(TEST_APK),
        "--use-orchestrator",
        "--environment-variables", "clearPackageData=true,screenshots=true",
        "--timeout", args.timeout,
        # One "class a.B#m" target per walk (gcloud splits the list on commas).
        "--test-targets", ",".join(f"{TOUR}#{m}" for m in args.method) if args.method else TOUR,
        "--directories-to-pull", DEVICE_DIR,
        "--results-dir", results_dir,
    ]
    if beta:
        cmd += ["--grant-permissions", "none"]
    if args.project:
        cmd += ["--project", args.project]
    for d in args.device or ["model=MediumPhone.arm,version=36,locale=es_419"]:  # Spanish system UI, like the app
        cmd += ["--device", d]
    print(" ".join(cmd))
    out = run_streaming(cmd)

    m = re.search(r"storage/browser/([^/\]\s]+)/", out)
    if not m:
        sys.exit("Couldn't find the results bucket in gcloud's output; use --download-only gs://<bucket>/" + results_dir)
    gs_dir = f"gs://{m.group(1)}/{results_dir}"
    print(f"\nResults: {gs_dir}")
    sys.exit(0 if download(exe, gs_dir, OUT / stamp) else 1)


if __name__ == "__main__":
    main()
