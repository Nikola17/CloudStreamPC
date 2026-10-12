#!/usr/bin/env python3
"""
Signs CloudStreamPC.app with a Developer ID for Apple's notarization: Apple also checks the native
libraries packed inside the jars (Compose, the gamepad…), so each one is taken out, signed with the
hardened runtime and put back, then every binary of the bundle and the bundle itself are signed.

Usage: macos_sign.py <CloudStreamPC.app> "<Developer ID Application: …>" <entitlements.plist>
"""
import os, subprocess, sys, tempfile, zipfile

app, identity, entitlements = sys.argv[1], sys.argv[2], sys.argv[3]
NATIVE = ('.dylib', '.jnilib')


def sign(path, with_entitlements=False):
    args = ['codesign', '--force', '--timestamp', '--options', 'runtime', '--sign', identity]
    if with_entitlements:
        args += ['--entitlements', entitlements]
    subprocess.run(args + [path], check=True)


def sign_jar(jar):
    with zipfile.ZipFile(jar) as src:
        natives = [i for i in src.infolist() if i.filename.lower().endswith(NATIVE)]
        if not natives:
            return 0
        with tempfile.TemporaryDirectory() as tmp:
            signed = {}
            for info in natives:
                path = os.path.join(tmp, info.filename.replace('/', '_'))
                with open(path, 'wb') as f:
                    f.write(src.read(info.filename))
                sign(path)
                with open(path, 'rb') as f:
                    signed[info.filename] = f.read()
            out = jar + '.signed'
            with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as dst:
                for info in src.infolist():
                    data = signed.get(info.filename) or src.read(info.filename)
                    entry = zipfile.ZipInfo(info.filename, info.date_time)
                    entry.external_attr = info.external_attr
                    dst.writestr(entry, data)
    os.replace(out, jar)
    return len(natives)


count = 0
launchers = os.path.join(app, 'Contents', 'MacOS')
for root, _, files in os.walk(app):
    for name in files:
        path = os.path.join(root, name)
        if os.path.islink(path) or root == launchers:
            continue
        if name.endswith('.jar'):
            count += sign_jar(path)
        elif name.endswith(NATIVE) or os.access(path, os.X_OK):
            sign(path)
for launcher in os.listdir(launchers):
    sign(os.path.join(launchers, launcher), with_entitlements=True)
sign(app, with_entitlements=True)
print(f'macos_sign: {count} bibliothèques des jars signées, application signée')
