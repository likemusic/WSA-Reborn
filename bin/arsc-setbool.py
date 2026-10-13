#!/usr/bin/env python3
# arsc-setbool.py <resources.arsc> <type id> <entry index> <0|1> — set one bool resource in every configuration of a
# compiled resource table that has it (plain, offset16 and sparse type chunks, compact entries). Used by
# bin/slot-rro-bool.sh. 13.10.2026.
import struct, sys
p, tid, eidx, val = sys.argv[1], int(sys.argv[2], 0), int(sys.argv[3]), int(sys.argv[4])
b = bytearray(open(p, 'rb').read())
def walk(off, end):
    while off < end:
        t, hs, sz = struct.unpack_from('<HHI', b, off)
        yield off, t, hs, sz
        off += sz
_, hs, sz = struct.unpack_from('<HHI', b, 0)
done = 0
for off, t, h, s in walk(hs, sz):
    if t != 0x0200: continue
    for o2, t2, h2, s2 in walk(off + h, off + s):
        if t2 != 0x0201: continue
        ident, flags = b[o2 + 8], b[o2 + 9]
        if ident != tid: continue
        count, start = struct.unpack_from('<II', b, o2 + 12)
        tab = o2 + h2; eo = None
        if flags & 1:      # sparse: (u16 index, u16 offset/4)
            for i in range(count):
                ix, of = struct.unpack_from('<HH', b, tab + 4 * i)
                if ix == eidx: eo = of * 4
        elif flags & 2:    # offset16: u16 offset/4, 0xffff = none
            of = struct.unpack_from('<H', b, tab + 2 * eidx)[0]
            eo = None if of == 0xffff else of * 4
        else:
            of = struct.unpack_from('<I', b, tab + 4 * eidx)[0]
            eo = None if of == 0xffffffff else of
        if eo is None: continue   # not in this configuration
        e = o2 + start + eo
        esz, eflags = struct.unpack_from('<HH', b, e)
        if eflags & 0x8:   # compact: u16 key, u16 flags (type in high byte), u32 data
            assert eflags >> 8 == 0x12, hex(eflags); dpos = e + 4
        else:
            assert b[e + 11] == 0x12, hex(b[e + 11]); dpos = e + 12
        print('old', hex(struct.unpack_from('<I', b, dpos)[0]))
        struct.pack_into('<I', b, dpos, 0xffffffff if val else 0); done += 1
assert done, 'not found'
open(p, 'wb').write(b); print('patched', done)
