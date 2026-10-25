"""Build the Android helper; org.json is supplied by Android, not shipped in the DEX."""
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--java-home", type=pathlib.Path, required=True)
parser.add_argument("--r8-jar", type=pathlib.Path, required=True)
parser.add_argument("--android-jar", type=pathlib.Path, required=True)
parser.add_argument("--json-jar", type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent
build = root / "build"
build.mkdir(parents=True, exist_ok=True)
temporary = tempfile.TemporaryDirectory(prefix="compile-", dir=build)
classes = pathlib.Path(temporary.name) / "classes"
dex = pathlib.Path(temporary.name) / "dex"
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(parents=True, exist_ok=True)
suffix = ".exe" if (args.java_home / "bin/java.exe").exists() else ""
subprocess.run([str(args.java_home / ("bin/javac" + suffix)), "--release", "8", "-encoding", "UTF-8", "-cp", str(args.json_jar.resolve()), "-d", str(classes), *map(str, (root / "src").rglob("*.java"))], check=True)
subprocess.run([str(args.java_home / ("bin/java" + suffix)), "-cp", str(args.r8_jar.resolve()), "com.android.tools.r8.D8", "--min-api", "26", "--lib", str(args.android_jar.resolve()), "--lib", str(args.json_jar.resolve()), "--output", str(dex), *map(str, classes.rglob("*.class"))], check=True)
destination = root.parent / "module/bin/duck-renewal.dex"
destination.write_bytes((dex / "classes.dex").read_bytes())
print("Built", destination)
