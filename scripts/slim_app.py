#!/usr/bin/env python3
"""
Makes the packaged app smaller to download (run on the app image before building installers):
- jars are rewritten without compression: the installer (LZMA / xz) then compresses all the
  classes together, much better than each jar's own zip compression;
- native libraries of the other systems are removed from the jars (a jar often carries
  Windows, Linux and macOS versions);
- debug symbols are stripped from the Java runtime's native libraries (Linux, macOS).

Usage: slim_app.py <app image dir> <windows|linux|macos>
"""
import os, shutil, subprocess, sys, zipfile

NATIVE = {'.dll': 'windows', '.so': 'linux', '.dylib': 'macos', '.jnilib': 'macos'}


def native_os(name):
    base = name.lower()
    for ext, system in NATIVE.items():
        if base.endswith(ext):
            return system
    return None


def slim_jar(path, system):
    tmp = path + '.tmp'
    removed = 0
    with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_STORED) as dst:
        for info in src.infolist():
            owner = native_os(info.filename)
            # Only top-level / plain natives of another system are dropped; sha256 sidecars follow them.
            if owner and owner != system:
                removed += info.file_size
                continue
            data = src.read(info.filename)
            out = zipfile.ZipInfo(info.filename, info.date_time)
            out.external_attr = info.external_attr
            out.compress_type = zipfile.ZIP_STORED
            dst.writestr(out, data)
    os.replace(tmp, path)
    return removed


def main():
    root, system = sys.argv[1], sys.argv[2]
    before = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(root) for f in fs)
    dropped = 0
    for d, _, files in os.walk(root):
        for f in files:
            p = os.path.join(d, f)
            if f.endswith('.jar'):
                dropped += slim_jar(p, system)
            elif system != 'windows' and native_os(f) == system and shutil.which('strip') and '/runtime/' in p.replace('\\', '/'):
                args = ['strip', '-S', p] if system == 'macos' else ['strip', '--strip-unneeded', p]
                subprocess.run(args, check=False, stderr=subprocess.DEVNULL)
    after = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(root) for f in fs)
    print(f'slim_app: {before / 1e6:.1f} Mo -> {after / 1e6:.1f} Mo sur disque, {dropped / 1e6:.1f} Mo de bibliothèques natives d\'autres systèmes retirées')


if __name__ == '__main__':
    main()
