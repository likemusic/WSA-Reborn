#!/usr/bin/env python3
"""Sends vkUpdateDescriptorSetWithTemplateKHR through ResourceTracker, like the core-named call (libvulkan_enc.so).

    bin/vulkan-enc-template-khr.py <libvulkan_enc.so in> <out>     (prints what it patched; exit 1 if nothing to do)

WHY (04.11.2026, patches/common/hardware_google_gfxstream/0034). In the guest's gfxstream encoder the entry points of the core name go to
ResourceTracker::on_vkUpdateDescriptorSetWithTemplate, which resolves descriptor sets that exist only in the guest so
far (batched descriptor updates) and sends the Sized form. The KHR entries call VkEncoder::
vkUpdateDescriptorSetWithTemplateKHR directly, so such a set reaches the host unknown, unboxes to NULL, and the AMD
driver dies on it: amdvlk64.dll+0x1bad5b, access 0x18. That kills the whole GPU process of the host. Unity takes the
KHR name (Crazy Taxi, Shopee PriceTool, 03.11.2026).

Each KHR entry starts with a jump to the core entry: same arguments, same return (void). Both functions are local,
so they are found by what they call: the PLT stubs of the two exported functions above.
"""
import re
import struct
import subprocess
import sys

ON = "_ZN9gfxstream2vk15ResourceTracker36on_vkUpdateDescriptorSetWithTemplate"
KHR = "_ZN9gfxstream2vk9VkEncoder36vkUpdateDescriptorSetWithTemplateKHR"


def readelf(flag, path):
    return subprocess.run(["readelf", flag, "-W", path], capture_output=True, text=True, check=True).stdout


def section(path, name):
    m = re.search(r"\s%s\s+\w+\s+(\w+) (\w+) (\w+)" % re.escape(name), readelf("-S", path))
    return [int(x, 16) for x in m.groups()]


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    slots = {}
    for line in readelf("-r", src).splitlines():
        for key, sym in (("on", ON), ("khr", KHR)):
            if sym in line and "JUMP_SLO" in line:
                slots[int(line.split()[0], 16)] = key
    if len(slots) != 2:
        sys.exit("expected symbols not found in " + src)
    plt_va, plt_off, plt_size = section(src, ".plt")
    stubs = {}
    for i in range(plt_size - 6):
        if data[plt_off + i:plt_off + i + 2] == b"\xff\x25":
            got = plt_va + i + 6 + struct.unpack_from("<i", data, plt_off + i + 2)[0]
            if got in slots:
                stubs[plt_va + i] = slots[got]
    text_va, text_off, text_size = section(src, ".text")

    def head(pos):
        """Function start: the first byte after the int3 padding before `pos` (a file offset)."""
        while data[pos - 1] != 0xCC:
            pos -= 1
        return pos

    heads = {"on": [], "khr": []}
    for i in range(text_off, text_off + text_size - 5):
        if data[i] == 0xE8:
            target = text_va + (i - text_off) + 5 + struct.unpack_from("<i", data, i + 1)[0]
            if target in stubs and head(i) not in heads[stubs[target]]:
                heads[stubs[target]].append(head(i))
    # Two callers each: dynCheck_entry_ and entry_, the entry_ functions lying later in .text (func_table order).
    if len(heads["on"]) != 2 or len(heads["khr"]) != 2:
        sys.exit("callers: %s" % heads)
    core = max(heads["on"])
    done = 0
    for entry in heads["khr"]:
        if data[entry] == 0xE9:
            print("already patched: %#x" % (entry - text_off + text_va))
            continue
        data[entry:entry + 5] = b"\xe9" + struct.pack("<i", core - (entry + 5))
        print("%#x -> jmp %#x" % (entry - text_off + text_va, core - text_off + text_va))
        done += 1
    open(dst, "wb").write(data)
    return 0 if done else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
