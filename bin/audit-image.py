#!/usr/bin/env python3
"""Check that a system image contains no files taken from Microsoft's WSA image (or other third-party blobs).

    bin/audit-image.py --ref <dir> [--ref <dir> …] [--strict <dir> …] <image.img|image.vhdx|dir> …

Every regular file of the candidate images is compared, by size and CRC32, with every file of the reference
trees (unpacked partitions of a stock WSA install — see bin/unpack-image.sh — or any other folder of files that
must not be redistributed). Matches are confirmed with SHA-256 when both sides are directories, and are
reported in two groups:

  * STRICT — a match with a file under a --strict reference (e.g. the stock vendor partition, the ARM
    translator, Microsoft's own apps). Release images must have none: exit status 1.
  * REVIEW — a match with a file under a plain --ref reference. Microsoft's system image is largely AOSP,
    so identical generic files (fonts, time zone data, configuration XML) are expected; check the list.

Re-signing changes an APK's hash but not its code, so for .apk/.jar files the CRCs of their classes*.dex
entries are compared as well (a re-signed copy of a reference APK is a STRICT/REVIEW hit too).

Files smaller than --min-size bytes (default 256) are ignored: empty files, tiny configs and symlink
targets produce meaningless matches.

Images are unpacked one at a time into a temporary directory with 7-Zip (no root needed); a .vhdx is
converted to a temporary raw image with qemu-img first.
"""
import argparse, hashlib, os, subprocess, sys, tempfile, zlib


def crc_of(path):
    crc = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            crc = zlib.crc32(chunk, crc)
    return "%08X" % crc


def sha_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tree_index(root, min_size):
    """{(size, crc): [path, …]} for regular files of a directory tree."""
    idx = {}
    for d, _, files in os.walk(root):
        for n in files:
            p = os.path.join(d, n)
            if os.path.islink(p) or not os.path.isfile(p):
                continue
            s = os.path.getsize(p)
            if s < min_size:
                continue
            idx.setdefault((s, crc_of(p)), []).append(p)
    return idx


def zip_dex_crcs(path):
    """{crc} of classes*.dex entries inside an apk/jar."""
    out = subprocess.run(["7z", "l", "-slt", path], capture_output=True, text=True).stdout
    crcs, name = set(), None
    for line in out.splitlines():
        if line.startswith("Path = "):
            name = line[7:]
        elif line.startswith("CRC = ") and name and os.path.basename(name).startswith("classes") and name.endswith(".dex"):
            crcs.add(line[6:].upper())
    return crcs


def dex_index(root):
    """{dex crc: [apk path, …]} for apk/jar files of a reference tree."""
    idx = {}
    for d, _, files in os.walk(root):
        for n in files:
            if n.endswith((".apk", ".jar")):
                p = os.path.join(d, n)
                for c in zip_dex_crcs(p):
                    idx.setdefault(c, []).append(p)
    return idx


def image_dir(image, tmp):
    """A directory with the image's files: the directory itself, or the image unpacked with debugfs rdump."""
    if os.path.isdir(image):
        return image
    src = image
    if image.endswith(".vhdx"):
        src = os.path.join(tmp, "img.raw")
        subprocess.run(["qemu-img", "convert", "-f", "vhdx", "-O", "raw", image, src], check=True)
    out = os.path.join(tmp, "root")
    # 7-Zip reads ext4 and, unlike `debugfs rdump`, does not try to chown (which fails without root and
    # makes rdump skip whole directories).
    subprocess.run(["7z", "x", "-y", "-o" + out, src], capture_output=True, check=True)
    if src != image:
        os.remove(src)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ref", action="append", default=[], help="reference tree: matches are listed for review")
    ap.add_argument("--strict", action="append", default=[], help="reference tree: any match fails the audit")
    ap.add_argument("--min-size", type=int, default=256)
    ap.add_argument("images", nargs="+")
    a = ap.parse_args()
    if not a.ref and not a.strict:
        ap.error("give at least one --ref or --strict tree")

    refs = [(r, False, tree_index(r, a.min_size), dex_index(r)) for r in a.ref] + \
           [(r, True, tree_index(r, a.min_size), dex_index(r)) for r in a.strict]
    strict_hits, review_hits = [], []
    for img in a.images:
      with tempfile.TemporaryDirectory() as tmp:
            root_dir = image_dir(img, tmp)
            files = [(p, k[0], k[1]) for k, ps in tree_index(root_dir, a.min_size).items() for p in ps]
            print(f"== {img}: {len(files)} files ≥ {a.min_size} bytes", file=sys.stderr)
            for path, size, crc in files:
                for root, strict, idx, didx in refs:
                    for ref in idx.get((size, crc), []):
                        if sha_of(path) != sha_of(ref):
                            continue  # CRC collision, not the same file
                        (strict_hits if strict else review_hits).append((img, os.path.relpath(path, root_dir), size, os.path.relpath(ref, root), root))
                if path.endswith((".apk", ".jar")):
                    dcrcs = zip_dex_crcs(path)
                    for root, strict, idx, didx in refs:
                        for ref in sorted({r for c in dcrcs for r in didx.get(c, [])}):
                            (strict_hits if strict else review_hits).append((img, os.path.relpath(path, root_dir) + " [same code]", size, os.path.relpath(ref, root), root))
    for title, hits in (("STRICT (must not ship)", strict_hits), ("REVIEW (identical to a file of the reference image)", review_hits)):
        print(f"## {title}: {len(hits)}")
        for img, path, size, ref, root in hits:
            print(f"  {os.path.basename(img)}:{path}  ({size} B)  ==  {root}/{ref}")
    sys.exit(1 if strict_hits else 0)


if __name__ == "__main__":
    main()
