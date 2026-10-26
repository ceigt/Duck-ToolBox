"""Host-only regression suite. Generates fresh synthetic keys; never contacts a device/server."""
import argparse
import os
import pathlib
import subprocess
import shutil
import sys
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--java-home', type=pathlib.Path, required=True)
p.add_argument('--json-jar', type=pathlib.Path, required=True)
p.add_argument('--openssl', default='openssl')
args = p.parse_args()
root = pathlib.Path(__file__).resolve().parent
build = root / 'build'
build.mkdir(exist_ok=True)
suffix = '.exe' if os.name == 'nt' else ''
with tempfile.TemporaryDirectory(prefix='test-', dir=build) as temporary:
    directory = pathlib.Path(temporary)
    classes, fixtures = directory / 'classes', directory / 'fixtures'
    classes.mkdir(parents=True)
    subprocess.run([str(args.java_home / ('bin/javac' + suffix)), '--release', '8', '-encoding', 'UTF-8', '-cp', str(args.json_jar.resolve()), '-d', str(classes), *map(str, (root/'src').rglob('*.java')), *map(str, (root/'tests').rglob('*.java'))], check=True)
    subprocess.run([sys.executable, str(root/'tests/fixtures.py'), str(fixtures), '--openssl', args.openssl], check=True)
    shutil.copyfile(root.parent/'crates/duck-tricky-store/assets/aosp-keybox.xml', fixtures/'aosp.xml')
    for name in ('RenewalTest', 'RegressionTest', 'AuditFixTest'):
        subprocess.run([str(args.java_home/('bin/java'+suffix)), '-cp', os.pathsep.join([str(classes), str(args.json_jar.resolve())]), 'dev.duck.renewal.'+name, str(fixtures), str(directory/name)], check=True)
