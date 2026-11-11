#!/usr/bin/env python3
"""Gives the bionic linker's internal symbols back the names libhoudini looks for (Android 16+).

    bin/linker-houdini-symbols.py <linker64 in> <linker64 out>     (prints what it renamed; exit 1 if nothing to do)

WHY (03.11.2026, patches/common/bionic/0033; ARM apps crashed in libhoudini at +0x39a0aa). libhoudini 14.0.0 (GoogleGame 1.2/1.3)
reads the x86 linker's own state: it parses /apex/com.android.runtime/bin/linker64 on disk and looks up, by exact
name in .symtab, `__dl__ZL6solist` (the soinfo list) and `__dl__ZL12g_cfi_shadow`. From Android 16 the linker is
built with ThinLTO that promotes these file-local statics and renames them `__dl__ZL6solist.llvm.<hash>`; the lookup
finds nothing, houdini takes offset 0 — the linker's base, whose first bytes are the ELF header — and walks it as a
list on the first ARM mprotect(): `mov 0x10(%r9),%rax` with r9 = 0x00010102464c457f ("\\x7fELF\\x02\\x01\\x01"),
SIGSEGV (code 128). Every ARM mprotect() dies, whatever the range or protection (a minimal ARM program that
calls mprotect() reproduces it); Android 15 names them without the suffix and works.

Android 17 renamed the variable too: `solist` became `solist_head` (`__dl__ZL11solist_head.llvm.<hash>`, same
meaning — the first soinfo, chained by `next`; linker_main.cpp; only the order changed: executable, then linker), and
`sonext` became `solist_tail`. So a name houdini wants may come from another variable of the same meaning.

The fix touches only the .symtab string table (not .dynsym, not code): the wanted name, which is never longer, is
written over the start of the found one and ended with NUL. A string another symbol could share (strtab suffix
merging) is checked first.
"""
import re
import struct
import sys

# wanted name -> names it may have instead (newest last), each possibly with a ThinLTO ".llvm.<hash>" suffix
WANTED = {
    "__dl__ZL6solist": ("__dl__ZL6solist", "__dl__ZL11solist_head"),
    "__dl__ZL12g_cfi_shadow": ("__dl__ZL12g_cfi_shadow",),
}


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        sys.exit("not a little-endian ELF64: " + src)
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum = struct.unpack_from("<HH", data, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + i * e_shentsize) for i in range(e_shnum)]
    symtab = [s for s in sections if s[1] == 2]  # SHT_SYMTAB
    if not symtab:
        sys.exit("no .symtab (stripped linker?): " + src)
    _, _, _, _, sym_off, sym_size, link, _, _, sym_ent = symtab[0]
    str_off, str_size = sections[link][4], sections[link][5]

    def name_at(i):
        end = data.index(b"\0", str_off + i)
        return data[str_off + i:end].decode("latin-1")

    names = [struct.unpack_from("<I", data, sym_off + k * sym_ent)[0] for k in range(sym_size // sym_ent)]
    done = 0
    for want, aliases in WANTED.items():
        if want in (name_at(n) for n in names):
            print("already plain: " + want)
            continue
        pattern = "(%s)(\\.llvm\\.\\d+)?" % "|".join(re.escape(a) for a in aliases)
        hits = [n for n in names if re.fullmatch(pattern, name_at(n))]
        if len(hits) != 1:
            sys.exit("%s: %d candidates among %s" % (want, len(hits), ", ".join(aliases)))
        start, old = hits[0], name_at(hits[0])
        if len(want) > len(old):
            sys.exit("%s: does not fit over %s" % (want, old))
        shared = [n for n in names if start < n <= start + len(old)]
        if shared:
            sys.exit("%s: string of %s shared by other symbols, not touching it" % (want, old))
        print("%s -> %s" % (old, want))
        data[str_off + start:str_off + start + len(want) + 1] = want.encode() + b"\0"
        done += 1
    open(dst, "wb").write(data)
    return 0 if done else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
