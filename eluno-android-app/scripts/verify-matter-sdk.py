#!/usr/bin/env python3
"""Bind JNI, bindings and trust roots to the pinned, patched SDK build."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / '.matter-sdk/connectedhomeip'
MANIFEST = ROOT / '.matter-sdk/artifacts.json'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checkout():
    lock = json.loads((ROOT / 'scripts/matter-sdk.lock.json').read_text())
    def git(*args):
        return subprocess.check_output(['git', '-C', str(SDK), *args])
    revision = git('rev-parse', 'HEAD').decode().strip()
    if revision != lock['commit']:
        raise ValueError('SDK checkout revision differs from lock')
    actual = git('diff', '--binary', '--ignore-submodules=none', 'HEAD')
    for relative in sorted(git('ls-files', '--others', '--exclude-standard').decode().splitlines()):
        result = subprocess.run(['git', 'diff', '--no-index', '--', '/dev/null', relative], cwd=SDK, stdout=subprocess.PIPE, check=False)
        if result.returncode != 1:
            raise ValueError('Cannot verify SDK addition: ' + relative)
        actual += result.stdout
    patch = ROOT / lock['patch']
    if actual != patch.read_bytes():
        raise ValueError('SDK sources differ from the pinned Eluno patch')
    submodules = git('submodule', 'status', '--recursive').decode()
    if any(line.startswith(('+', 'U')) for line in submodules.splitlines()):
        raise ValueError('SDK submodule revision differs from checkout')
    patterns = ['src/controller/java/src/chip/**/*.java', 'src/controller/java/src/matter/tlv/**/*.kt', 'src/controller/java/src/matter/jsontlv/**/*.kt', 'src/controller/java/src/matter/onboardingpayload/**/*.kt', 'src/controller/java/generated/java/**/*.java', 'src/platform/android/java/**/*.java']
    excluded = {'BatchInvokeCallback.java', 'BatchInvokeCallbackJni.java', 'GetConnectedDeviceCallbackForTestJni.java'}
    bindings = sorted({p for pattern in patterns for p in SDK.glob(pattern) if p.name not in excluded})
    return {'revision': revision, 'patch_sha256': digest(patch), 'ndk': lock['ndk'],
            'build_script_sha256': digest(ROOT / 'scripts/build-matter-sdk.sh'),
            'bindings': {str(p.relative_to(SDK)): digest(p) for p in bindings},
            'submodules_sha256': hashlib.sha256(submodules.encode()).hexdigest()}


def artifacts():
    files = sorted((ROOT / '.matter-sdk/jniLibs').rglob('*.so')) + sorted((ROOT / '.matter-sdk/assets/paa').glob('*.der'))
    required = {'.matter-sdk/jniLibs/arm64-v8a/libCHIPController.so', '.matter-sdk/jniLibs/arm64-v8a/libc++_shared.so'}
    names = {str(p.relative_to(ROOT)) for p in files}
    if not required.issubset(names) or not any(p.suffix == '.der' for p in files):
        raise ValueError('Missing JNI or PAA artifacts')
    paa = SDK / 'credentials/production/paa-root-certs'
    if {p.name: digest(p) for p in (ROOT / '.matter-sdk/assets/paa').glob('*.der')} != {p.name: digest(p) for p in paa.glob('*.der')}:
        raise ValueError('Packaged PAA differ from production trust roots')
    return {str(p.relative_to(ROOT)): digest(p) for p in files}


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'check'
    state = checkout()
    pending = ROOT / '.matter-sdk/build-inputs.json'
    if mode == 'prepare':
        pending.write_text(json.dumps(state, indent=2) + '\n')
        return
    if mode == 'write':
        if json.loads(pending.read_text()) != state:
            raise ValueError('SDK inputs changed during native build')
        state['files'] = artifacts()
        MANIFEST.write_text(json.dumps(state, indent=2, sort_keys=True) + '\n')
    elif mode == 'check':
        state['files'] = artifacts()
        if json.loads(MANIFEST.read_text()) != state:
            raise ValueError('SDK artifact hashes or provenance mismatch; rebuild SDK')
    else:
        raise ValueError('Unknown mode')
    print('Matter SDK: revision, patch, submodules, JNI and PAA verified')

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
