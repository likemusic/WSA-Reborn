#!/usr/bin/env python3
"""Gives Microsoft's OMX.android.latte.mp4v.decoder the standard role, so Android lists an MPEG-4 Part 2 decoder.

Microsoft's OMX plugin (vendor /lib/libstagefrighthw.so and /lib64/libstagefrighthw.so) registers the MPEG-4 Part 2
component with the role "video_decoder.mp4v". Android's OmxInfoBuilder sets the role it derives from the mime type,
"video_decoder.mpeg4" for video/mp4v-es (OMXUtils GetComponentRole). The component compares it to its own string,
refuses setParameter(ParamStandardComponentRole), and the builder drops the component: no MPEG-4 decoder at all
(MediaCodec NAME_NOT_FOUND, docs/own-host.md, 29.10.2026).

The role string is set by one lea in the static table of components. The strings are packed with no room for a longer
one, so the script appends "video_decoder.mpeg4" after the end of the first (read-only) LOAD segment, in the zero
padding before the next segment's page, grows that segment's p_filesz and p_memsz over it, and points the lea at it.

    bin/latte-mpeg4-role.py <libstagefrighthw.so> <out .so>

Only the two plugins of WSA 2407.40000.4.0 are known (checked by their bytes); anything else is refused.
"""
import struct
import sys

OLD = b"video_decoder.mp4v\0"
NEW = b"video_decoder.mpeg4\0"

# Per ELF class: the address of the lea and how its displacement is formed.
#   64-bit: lea rax,[rip+disp32] at 0x19bc4 (7 bytes, disp at +3), target = next instruction + disp.
#   32-bit: lea eax,[ebx+disp32] at 0x17cf4 (6 bytes, disp at +2), ebx = _GLOBAL_OFFSET_TABLE_ (.got.plt).
KNOWN = {
    2: dict(lea=0x19bc4, opcode=b"\x48\x8d\x05", dispat=3, length=7),
    1: dict(lea=0x17cf4, opcode=b"\x8d\x83", dispat=2, length=6),
}


def main():
    src, out = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if d[:4] != b"\x7fELF":
        sys.exit("not an ELF file")
    cls = d[4]
    k = KNOWN.get(cls)
    if not k:
        sys.exit("unknown ELF class")
    if d.find(NEW) >= 0:
        sys.exit("already patched")
    if d.count(OLD) != 1:
        sys.exit("not the known plugin: role string not found once")
    old = d.find(OLD)  # a file offset; in this segment it equals the virtual address

    if cls == 2:
        phoff, = struct.unpack_from("<Q", d, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", d, 0x36)
        fmt, foff = "<IIQQQQQQ", (32, 40)  # p_filesz at +32, p_memsz at +40
    else:
        phoff, = struct.unpack_from("<I", d, 0x1c)
        phentsize, phnum = struct.unpack_from("<HH", d, 0x2a)
        fmt, foff = "<IIIIIIII", (16, 20)
    loads = []
    for i in range(phnum):
        e = phoff + i * phentsize
        f = struct.unpack_from(fmt, d, e)
        if f[0] == 1:  # PT_LOAD
            loads.append((e, f))
    e0, f0 = loads[0]
    if cls == 2:
        _, _, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = f0
    else:
        _, p_offset, p_vaddr, _, p_filesz, p_memsz, _, _ = f0
    if p_offset != 0 or p_vaddr != 0 or p_filesz != p_memsz:
        sys.exit("first LOAD segment is not the expected read-only one at 0")
    if not (p_vaddr <= old < p_vaddr + p_filesz):
        sys.exit("role string outside the first segment")
    new = (p_filesz + 7) & ~7
    end = new + len(NEW)
    next_off = loads[1][1][2] if cls == 2 else loads[1][1][1]
    if end > next_off or any(d[new:end]):
        sys.exit("no free zero padding after the first segment")

    # The lea: check that it points at the old string, then retarget it.
    lea = k["lea"]
    text = [(e, f) for e, f in loads if (f[1] if cls == 2 else f[6]) & 1]  # p_flags & PF_X
    _, tf = text[0]
    t_off, t_vaddr = (tf[2], tf[3]) if cls == 2 else (tf[1], tf[2])
    at = lea - t_vaddr + t_off
    if bytes(d[at:at + len(k["opcode"])]) != k["opcode"]:
        sys.exit("not the known plugin: no lea at 0x%x" % lea)
    disp, = struct.unpack_from("<i", d, at + k["dispat"])
    if cls == 2:
        base = lea + k["length"]
    else:
        base = got_plt(d)
    if base + disp != old:
        sys.exit("not the known plugin: the lea at 0x%x points at 0x%x, not the role string" % (lea, base + disp))
    struct.pack_into("<i", d, at + k["dispat"], new - base)

    d[new:end] = NEW
    word = "<Q" if cls == 2 else "<I"
    struct.pack_into(word, d, e0 + foff[0], end)
    struct.pack_into(word, d, e0 + foff[1], end)
    open(out, "wb").write(d)
    print("%s: role at 0x%x -> 0x%x \"%s\", first segment 0x%x -> 0x%x" % (src, old, new, NEW[:-1].decode(), p_filesz, end))


def got_plt(d):
    """The address of .got.plt (_GLOBAL_OFFSET_TABLE_, what ebx holds in i386 PIC code)."""
    shoff, = struct.unpack_from("<I", d, 0x20)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", d, 0x2e)
    names = shoff + shstrndx * shentsize
    stroff = struct.unpack_from("<I", d, names + 16)[0]
    for i in range(shnum):
        e = shoff + i * shentsize
        name, = struct.unpack_from("<I", d, e)
        n = d[stroff + name:d.index(b"\0", stroff + name)]
        if n == b".got.plt":
            return struct.unpack_from("<I", d, e + 12)[0]
    sys.exit("no .got.plt")


if __name__ == "__main__":
    main()
