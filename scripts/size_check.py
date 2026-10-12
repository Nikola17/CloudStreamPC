#!/usr/bin/env python3
"""
Download size watch, run before publishing: lists the size of every file and compares it with the
same file of the latest release. A file more than 10 % bigger stops the release (the app must stay
light to download). Usage: size_check.py <dist dir> <version> <owner/repo>
"""
import json, os, re, sys, urllib.request

dist, version, repo = sys.argv[1], sys.argv[2], sys.argv[3]
LIMIT = 1.10


def kind(name, ver):
    """CloudStreamPC-2.7.0-amd64.deb -> -amd64.deb (the same file whatever the version)."""
    return name.replace(f"CloudStreamPC-{ver}", "", 1) if ver else re.sub(r"^CloudStreamPC-[\d.]+", "", name)


previous = {}
try:
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/latest",
                                 headers={"Authorization": f"Bearer {os.environ.get('GH_TOKEN', '')}", "User-Agent": "CloudStreamPC"})
    release = json.load(urllib.request.urlopen(req, timeout=30))
    old = release["tag_name"].lstrip("v")
    previous = {kind(a["name"], old): a["size"] for a in release["assets"]}
    print(f"Comparaison avec {release['tag_name']}")
except Exception as e:  # first release, API down: just list the sizes
    print(f"Pas de version précédente lisible ({e})")

rows, too_big = [], []
for name in sorted(os.listdir(dist)):
    size = os.path.getsize(os.path.join(dist, name))
    before = previous.get(kind(name, version))
    change = f"{(size / before - 1) * 100:+.0f} %" if before else "nouveau"
    rows.append(f"| {name} | {size / 1e6:.1f} Mo | {before / 1e6:.1f} Mo |" if before else f"| {name} | {size / 1e6:.1f} Mo | — |")
    rows[-1] += f" {change} |"
    if before and size > before * LIMIT:
        too_big.append(f"{name} : {before / 1e6:.1f} → {size / 1e6:.1f} Mo")

table = "| Fichier | Taille | Version précédente | Écart |\n|---|---|---|---|\n" + "\n".join(rows)
print(table)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as f:
        f.write("## Tailles des fichiers\n\n" + table + "\n")
if too_big:
    print("Fichiers plus lourds de plus de 10 % :\n" + "\n".join(too_big))
    sys.exit(1)
