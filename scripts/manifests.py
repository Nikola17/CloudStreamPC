#!/usr/bin/env python3
"""
winget and Scoop manifests of a release (URLs and SHA-256 of its files), put in
CloudStreamPC-<version>-manifests.zip. Submitting them is done with the owner's GitHub account:
see "winget et Scoop" in the README.

Usage: manifests.py <dist dir> <version> <owner/repo>
"""
import io, json, os, sys, zipfile

dist, version, repo = sys.argv[1], sys.argv[2], sys.argv[3]
owner = repo.split('/')[0]
base = f'https://github.com/{repo}/releases/download/v{version}'
sums = {}
with open(os.path.join(dist, 'SHA256SUMS.txt')) as f:
    for line in f:
        digest, name = line.split(maxsplit=1)
        sums[name.strip().lstrip('*')] = digest.upper()

package = f'{owner}.CloudStreamPC'
setup = f'CloudStreamPC-{version}-setup.exe'
arm_setup = f'CloudStreamPC-{version}-arm64-setup.exe'
portable = f'CloudStreamPC-{version}-portable.zip'

installers = [f"""  - Architecture: x64
    InstallerUrl: {base}/{setup}
    InstallerSha256: {sums[setup]}"""]
if arm_setup in sums:
    installers.append(f"""  - Architecture: arm64
    InstallerUrl: {base}/{arm_setup}
    InstallerSha256: {sums[arm_setup]}""")

files = {
    f'winget/{package}.yaml': f"""# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.1.6.0.schema.json
PackageIdentifier: {package}
PackageVersion: {version}
DefaultLocale: fr-FR
ManifestType: version
ManifestVersion: 1.6.0
""",
    f'winget/{package}.locale.fr-FR.yaml': f"""# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.1.6.0.schema.json
PackageIdentifier: {package}
PackageVersion: {version}
PackageLocale: fr-FR
Publisher: CloudStreamPC
PublisherUrl: https://github.com/{owner}
PackageName: CloudStreamPC
PackageUrl: https://github.com/{repo}
License: GPL-3.0
ShortDescription: CloudStream pour ordinateur (films, séries, animés, TV en direct avec les extensions CloudStream)
Tags:
  - cloudstream
  - streaming
  - iptv
ManifestType: defaultLocale
ManifestVersion: 1.6.0
""",
    f'winget/{package}.installer.yaml': f"""# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.6.0.schema.json
PackageIdentifier: {package}
PackageVersion: {version}
InstallerType: inno
Scope: user
InstallModes:
  - interactive
  - silent
UpgradeBehavior: install
Installers:
{chr(10).join(installers)}
ManifestType: installer
ManifestVersion: 1.6.0
""",
    'scoop/cloudstreampc.json': json.dumps({
        'version': version,
        'description': 'CloudStream pour ordinateur (films, séries, animés, TV en direct)',
        'homepage': f'https://github.com/{repo}',
        'license': 'GPL-3.0-only',
        'architecture': {'64bit': {'url': f'{base}/{portable}', 'hash': sums[portable].lower()}},
        'extract_dir': 'CloudStreamPC',
        'bin': 'CloudStreamPC.exe',
        'shortcuts': [['CloudStreamPC.exe', 'CloudStreamPC']],
        'checkver': {'github': f'https://github.com/{repo}'},
        'autoupdate': {'architecture': {'64bit': {'url': f'https://github.com/{repo}/releases/download/v$version/CloudStreamPC-$version-portable.zip'}}},
    }, indent=4, ensure_ascii=False) + '\n',
}

buffer = io.BytesIO()
with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as z:
    for name, text in files.items():
        z.writestr(name, text)
out = os.path.join(dist, f'CloudStreamPC-{version}-manifests.zip')
with open(out, 'wb') as f:
    f.write(buffer.getvalue())
print(f'manifests: {out}')
for name, text in files.items():
    print(f'--- {name}\n{text}')
