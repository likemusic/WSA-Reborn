#!/usr/bin/env python3
"""mkcpio.py <init-binary> <out.img> — newc initrd: /init plus the directories and /dev/console it needs."""
import sys, time
entries = [('dev', 0o040755, b''), ('dev/console', 0o020600, b''), ('proc', 0o040755, b''), ('sys', 0o040755, b''),
           ('newroot', 0o040755, b''), ('init', 0o100755, open(sys.argv[1], 'rb').read())]
out = bytearray(); ino = 1; now = int(time.time())
def add(name, mode, data, rdev=(0, 0)):
    global ino
    nb = name.encode() + b'\0'
    hdr = b'070701' + b''.join(b'%08X' % v for v in (ino, mode, 0, 0, 1, now, len(data), 0, 0, rdev[0], rdev[1], len(nb), 0))
    out.extend(hdr + nb); out.extend(b'\0' * (-len(out) % 4)); out.extend(data); out.extend(b'\0' * (-len(out) % 4)); ino += 1
for name, mode, data in entries:
    add(name, mode, data, (5, 1) if name == 'dev/console' else (0, 0))
add('TRAILER!!!', 0, b'')
open(sys.argv[2], 'wb').write(bytes(out))
