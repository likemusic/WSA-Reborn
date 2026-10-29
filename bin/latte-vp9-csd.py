#!/usr/bin/env python3
"""Makes Microsoft's Latte VP9 decoder drop codec-config buffers, as AOSP's VP9 decoders do.

Android 15 and later hand a VP9 decoder from WebM or MP4 its codec private data (profile, level, bit depth) as csd-0,
which ACodec queues with OMX_BUFFERFLAG_CODECCONFIG. Microsoft's OMX plugin (vendor /lib/libstagefrighthw.so, used by
the 32-bit android.hardware.media.omx@1.0-service) sends it to the Windows decoder like a frame, and the decoder then
holds 128 frames that the plugin loses at the end of the stream: 22 of 150 (docs/own-host.md, 29.10.2026). Stock
WSA runs Android 13 and never sees it. The own host drops that packet itself (GskMediaListen); this fixes the guest,
for WsaClient as the host.

LatteOMXDecoder::onQueueFilled returns an empty input buffer without decoding it (cmp dword [esi+0x10],0 — nFilledLen —
at 0x196f3, je 0x1970d -> notifyEmptyBufferDone). The patch jumps from there to a few instructions in the int3 padding at
0xed60 that take the same path for a buffer with OMX_BUFFERFLAG_CODECCONFIG (0x80 in nFlags, [esi+0x3c]) when the
component is VP9 (codec 3 in [edi+0x124], set by the constructor from the component name), and go back otherwise.

    bin/latte-vp9-csd.py <lib/libstagefrighthw.so> <out .so>

Only the 32-bit plugin of WSA 2407.40000.4.0 is known (checked by its bytes); anything else is refused. The 64-bit one
is not loaded (the OMX service is 32-bit) and stays as it is.
"""
import struct
import sys

SITE = 0x196f3          # cmp dword [esi+0x10],0 ; je 0x1970d
SITE_WAS = bytes.fromhex("837e1000" "7414")
EMPTY = 0x1970d         # the empty-buffer path: erase from the queue, notifyEmptyBufferDone
BACK = 0x196f9          # right after the je: a buffer with data
CAVE = 0xed60           # int3 padding of the executable segment (0xed51-0xf000)
TEXT_DELTA = 0x1000     # executable segment: vaddr 0xe000 at file offset 0xd000


def rel32(at, size, target):
    return struct.pack("<i", target - (at + size))


def main():
    src, out = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if d[:5] != b"\x7fELF\x01":
        sys.exit("not a 32-bit ELF file")
    site, cave = SITE - TEXT_DELTA, CAVE - TEXT_DELTA
    if bytes(d[site:site + 6]) == b"\xe9" + rel32(SITE, 5, CAVE) + b"\x90":
        sys.exit("already patched")
    if bytes(d[site:site + 6]) != SITE_WAS:
        sys.exit("not the known plugin: other bytes at 0x%x" % SITE)
    c = CAVE
    code = bytearray()
    code += bytes.fromhex("837e1000")                       # cmp dword [esi+0x10],0
    code += b"\x0f\x84" + rel32(c + len(code), 6, EMPTY)    # je EMPTY
    code += bytes.fromhex("f6463c80")                       # test byte [esi+0x3c],0x80   (CODECCONFIG)
    code += bytes.fromhex("740e")                           # jz back
    code += bytes.fromhex("80bf2401000003")                 # cmp byte [edi+0x124],3      (VP9)
    code += bytes.fromhex("7505")                           # jne back
    code += b"\xe9" + rel32(c + len(code), 5, EMPTY)        # jmp EMPTY
    assert len(code) == 30
    code += b"\xe9" + rel32(c + len(code), 5, BACK)         # back: jmp BACK
    if any(b != 0xcc for b in d[cave:cave + len(code)]):
        sys.exit("not the known plugin: no int3 padding at 0x%x" % CAVE)
    d[cave:cave + len(code)] = code
    d[site:site + 6] = b"\xe9" + rel32(SITE, 5, CAVE) + b"\x90"
    open(out, "wb").write(d)
    print("%s: VP9 codec-config buffers dropped (0x%x -> 0x%x, %d bytes)" % (src, SITE, CAVE, len(code)))


if __name__ == "__main__":
    main()
