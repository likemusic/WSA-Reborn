#!/usr/bin/env python3
"""Lets Microsoft's Latte OMX plugin register its decoders whatever debug.stagefright.ccodec says.

The constructor of LatteOMXPlugin (vendor /lib/libstagefrighthw.so and /lib64/libstagefrighthw.so) reads
debug.stagefright.ccodec and lists its components only when the value is exactly "0"; with the property unset or any
other value it lists none. WSA's vendor /build.prop sets 0, which also turns Codec2 off, so Android has the
OMX.android.latte.* decoders and no c2.android.* software codecs at all; with 4 it is the other way round (docs/
own-host.md, 29.10.2026). The patch jumps over the check, so with ccodec=4 the list is mixed: Latte first, then
c2.android.* (MPEG-4 Part 2 under WsaClient, H.263 above CIF, codecs Latte does not have).

    bin/latte-ccodec.py <libstagefrighthw.so> <out .so>

Only the two plugins of WSA 2407.40000.4.0 are known (checked by their bytes); anything else is refused.
"""
import sys

# Per ELF class: the address of the "length <= 0" jump after property_get, the bytes there (the jump and the
# cmpw $0x30 that follows it), and what replaces the jump: a short jmp to the code that lists the components.
#   32-bit: jle 0x180b0 at 0x180a6 (2 bytes); the cmpw is followed by je 0x180d7.
#   64-bit: jle 0x1a174 at 0x19f2d (6 bytes); the cmpw is followed by jne 0x1a174, the list starts at 0x19f3f.
KNOWN = {
    1: dict(at=0x180a6, was=bytes.fromhex("7e08" "66837c244030"), new=bytes.fromhex("eb2f")),
    2: dict(at=0x19f2d, was=bytes.fromhex("0f8e41020000" "66837c244030"), new=bytes.fromhex("eb10" "90909090")),
}
TEXT_DELTA = 0x1000     # executable segment: vaddr - file offset, the same in both plugins


def main():
    src, out = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if d[:4] != b"\x7fELF":
        sys.exit("not an ELF file")
    k = KNOWN.get(d[4])
    if not k:
        sys.exit("unknown ELF class")
    at, was, new = k["at"] - TEXT_DELTA, k["was"], k["new"]
    if bytes(d[at:at + len(new)]) == new:
        sys.exit("already patched")
    if bytes(d[at:at + len(was)]) != was:
        sys.exit("not the known plugin: other bytes at 0x%x" % k["at"])
    d[at:at + len(new)] = new
    open(out, "wb").write(d)
    print("%s: components listed whatever debug.stagefright.ccodec is (0x%x)" % (src, k["at"]))


if __name__ == "__main__":
    main()
