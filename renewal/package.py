"""Compatibility entry point; all builds use the full source/license-aware packager."""
import argparse
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("destination", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
subprocess.run([sys.executable, str(root/"scripts/package-module.py"),
                "--binary", "module/bin/duckd", "--channel", "stable",
                "--output", str(args.destination.resolve())], cwd=root, check=True)
