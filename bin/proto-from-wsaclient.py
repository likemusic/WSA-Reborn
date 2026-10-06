#!/usr/bin/env python3
r"""Extract the GENUINE .proto schemas of the RCM channels from the host WsaClient.exe.

The guest half of WSA is built with protobuf-javalite, which strips descriptors — so the schema
of the "Shell" channel had to be reconstructed from RawMessageInfo in smali (bin/proto-from-smali.py).
The HOST half is plain C++ with full protobuf, which embeds in the binary the serialized
FileDescriptorProto of every .proto file in full: message and field names, numbers, required/
optional/repeated, types, enums, default values. Found 24.09.2026, when the clipboard
schema reconstructed from smali was compared with the original and matched field for field.

    bin/proto-from-wsaclient.py <path to WsaClient.exe>                   — list of schemas found
    bin/proto-from-wsaclient.py <path> Notification.proto                 — print one schema
    bin/proto-from-wsaclient.py <path> --all <directory>                  — all schemas as files

Where to get WsaClient.exe: it is not in the Android image but in the WSA install directory on the host —
<install>/WsaClient/WsaClient.exe (path of the live process: `(Get-Process WsaClient).Path`).

THE OUTPUT IS NOT COMMITTED TO THE REPOSITORY. It is not our reconstruction of the format but a verbatim schema from
a Microsoft binary; our rule is BYOB — we keep our own work, and everyone extracts third-party material from their own
install with this script (memory note wsa-github-publish-legal-constraint). Our own .proto files in
WsaShellBridge/proto/ remain written by us; the original is useful to VERIFY them.

How it searches: a descriptor starts with field 1 (name) — byte 0x0a, length, "<Name>.proto". The end
is found by walking the top-level fields: as soon as a tag is no longer valid for
FileDescriptorProto, the message has ended. Requires python-protobuf (pip install protobuf; PEP 668
will not let you install it into the system python — use a venv).
"""
import os
import sys

from google.protobuf import descriptor_pb2

TYPE = {1: 'double', 2: 'float', 3: 'int64', 4: 'uint64', 5: 'int32', 6: 'fixed64', 7: 'fixed32',
        8: 'bool', 9: 'string', 10: 'group', 11: 'message', 12: 'bytes', 13: 'uint32', 14: 'enum',
        15: 'sfixed32', 16: 'sfixed64', 17: 'sint32', 18: 'sint64'}
LABEL = {1: 'optional', 2: 'required', 3: 'repeated'}

# FileDescriptorProto fields are all length-delimited, except 12 (syntax, also a string).
TOP_FIELDS = set(range(1, 13))


def varint(buf, i):
    result = shift = 0
    while True:
        if i >= len(buf):
            raise ValueError('truncated varint')
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7
        if shift > 63:
            raise ValueError('varint too long')


def end_of_message(buf, start):
    """First byte AFTER the message: walk the tags while they are valid for FileDescriptorProto."""
    i = start
    while i < len(buf):
        try:
            tag, j = varint(buf, i)
        except ValueError:
            break
        field, wire = tag >> 3, tag & 7
        if field not in TOP_FIELDS or wire != 2:
            break
        try:
            length, j = varint(buf, j)
        except ValueError:
            break
        if length > (1 << 20) or j + length > len(buf):
            break
        i = j + length
    return i


def find_descriptors(data):
    out, seen, pos = [], set(), 0
    while True:
        pos = data.find(b'.proto', pos + 1)
        if pos < 0:
            return out
        # the name is short: try lengths until it is preceded by 0x0a <length>
        for name_len in range(7, 64):
            name_at = pos + 6 - name_len
            if name_at < 2 or data[name_at - 2] != 0x0A or data[name_at - 1] != name_len:
                continue
            start = name_at - 2
            fd = descriptor_pb2.FileDescriptorProto()
            try:
                fd.ParseFromString(data[start:end_of_message(data, start)])
            except Exception:
                break
            if fd.name.endswith('.proto') and (fd.message_type or fd.enum_type) \
                    and fd.name not in seen:
                seen.add(fd.name)
                out.append(fd)
            break


def print_enum(enum, indent, write):
    pad = '  ' * indent
    write(f'{pad}enum {enum.name} {{')
    for value in enum.value:
        write(f'{pad}  {value.name} = {value.number};')
    write(f'{pad}}}')


def print_message(msg, indent, write):
    pad = '  ' * indent
    write(f'{pad}message {msg.name} {{')
    for enum in msg.enum_type:
        print_enum(enum, indent + 1, write)
    for nested in msg.nested_type:
        print_message(nested, indent + 1, write)
    for f in msg.field:
        type_name = f.type_name.lstrip('.') if f.type in (11, 14) else TYPE.get(f.type, str(f.type))
        default = f' [default = {f.default_value}]' if f.HasField('default_value') else ''
        write(f'{pad}  {LABEL[f.label]} {type_name} {f.name} = {f.number}{default};')
    write(f'{pad}}}')


def print_file(fd, write):
    write(f'syntax = "{fd.syntax or "proto2"}";')
    write(f'package {fd.package};')
    for dep in fd.dependency:
        write(f'import "{dep}";')
    write('')
    for enum in fd.enum_type:
        print_enum(enum, 0, write)
    for msg in fd.message_type:
        print_message(msg, 0, write)


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    data = open(argv[1], 'rb').read()
    files = find_descriptors(data)
    if not files:
        sys.exit('no descriptors found — is this really WsaClient.exe?')

    if len(argv) == 2:
        for fd in sorted(files, key=lambda f: f.name):
            print(f'{fd.name:36} package={fd.package:32} messages={len(fd.message_type):3} '
                  f'imports={",".join(fd.dependency) or "-"}')
        return

    if argv[2] == '--all':
        out_dir = argv[3] if len(argv) > 3 else '.'
        os.makedirs(out_dir, exist_ok=True)
        for fd in files:
            path = os.path.join(out_dir, fd.name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w') as fh:
                print_file(fd, lambda line: fh.write(line + '\n'))
            print(f'{path}')
        return

    for fd in files:
        if fd.name == argv[2]:
            print_file(fd, print)
            return
    sys.exit(f'no such schema: {argv[2]}')


if __name__ == '__main__':
    main(sys.argv)
