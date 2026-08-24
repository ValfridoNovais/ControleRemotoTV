#!/usr/bin/env python3
import os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def run(cmd):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run(cmd, cwd=ROOT, check=True)

def find_gradle_command():
    """Prefere o wrapper do projeto (gradlew/gradlew.bat), que já fixa a
    versão correta do Gradle (veja gradle/wrapper/gradle-wrapper.properties).
    Cai para o `gradle` do PATH apenas se o wrapper não existir."""
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper = ROOT / wrapper_name
    if wrapper.exists():
        return [str(wrapper)]

    gradle = shutil.which("gradle")
    if gradle:
        return [gradle]

    return None

def main():
    mode = (sys.argv[1] if len(sys.argv) > 1 else "debug").lower()
    if mode not in {"debug", "release"}:
        raise SystemExit("Uso: python tools/build.py [debug|release]")

    sdk = os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME")
    if not sdk:
        raise SystemExit(
            "ANDROID_SDK_ROOT/ANDROID_HOME não definido. "
            "Python coordena o build, mas o Android SDK continua obrigatório localmente. "
            "Alternativa recomendada: GitHub Actions."
        )

    gradle_cmd = find_gradle_command()
    if not gradle_cmd:
        raise SystemExit(
            "Nem o wrapper do projeto (gradlew/gradlew.bat) nem o Gradle do PATH "
            "foram encontrados. Use GitHub Actions ou instale Gradle localmente."
        )

    task = "assembleDebug" if mode == "debug" else "assembleRelease"
    run(gradle_cmd + ["--no-daemon", task])

    out = ROOT / "app" / "build" / "outputs" / "apk" / mode
    print("\nAPK(s):")
    for apk in out.glob("*.apk"):
        print(" -", apk)

if __name__ == "__main__":
    main()
