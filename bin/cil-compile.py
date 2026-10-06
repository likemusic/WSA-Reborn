#!/usr/bin/env python3
"""Compiles a set of .cil files with the HOST libsepol — a replacement for Android's secilc.

    bin/cil-compile.py <file.cil> [file.cil ...]

Why. WSA keeps a precompiled policy in vendor along with the partition hashes it was built for.
Our build's hash matches none of them, so init has to compile the policy itself by calling secilc
from the image. If compilation fails, init panics BEFORE logd starts: from the outside this is
indistinguishable from any other silent failure, and each attempt costs a run on the live WSA (grabbing
the shared subsystem + an owner's command) or an offline bench run.

This check used to be done by bin/check-sepolicy.sh with a hand-built secilc on a separate machine;
those paths are long dead. Android's /system/bin/secilc from the image cannot run under WSL (bionic,
linker and libc from APEX), and the secilc package cannot be installed without a password. But
libsepol is already on the system and exports the whole CIL API that secilc uses — this script calls
it directly via ctypes, repeating exactly the flags init passes (system/core/init/selinux.cpp):

    -m       cil_set_multiple_decls(1)        multiple declarations of one type
    -M true  cil_set_mls(1)                   MLS enabled
    -G       cil_set_attrs_expand_generated(1) expand generated attributes
    -N       cil_set_disable_neverallow(1)    neverallow is NOT checked on device

Keep -N in mind: a neverallow violation does not break boot on device (the build and CTS catch it).
So "compiled" here means exactly what it means on the device — no more.

LIMITATION not to forget: the host libsepol is not the same build as the one in the Android image.
So the check must start with a CONTROL run on a known-good set (Microsoft's policy from the reference
image). If even that fails to compile, the tool is unfit, not the configuration under test, and any
negative answer means nothing. The control run is done by bin/check-sepolicy.sh.
"""
import ctypes
import ctypes.util
import sys
import os

CIL_ERR = 1


def load_libsepol():
    name = ctypes.util.find_library("sepol") or "libsepol.so.2"
    try:
        return ctypes.CDLL(name)
    except OSError as e:
        sys.exit(f"cannot load libsepol ({name}): {e}")


def compile_files(paths):
    lib = load_libsepol()

    lib.cil_db_init.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
    lib.cil_add_file.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
    lib.cil_compile.argtypes = [ctypes.c_void_p]
    lib.cil_build_policydb.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]

    lib.cil_set_log_level(CIL_ERR)

    db = ctypes.c_void_p()
    lib.cil_db_init(ctypes.byref(db))
    lib.cil_set_mls(db, 1)
    lib.cil_set_multiple_decls(db, 1)
    lib.cil_set_attrs_expand_generated(db, 1)
    lib.cil_set_disable_neverallow(db, 1)
    lib.cil_set_disable_dontaudit(db, 0)

    for p in paths:
        if not os.path.isfile(p):
            sys.exit(f"no such file: {p}")
        data = open(p, "rb").read()
        rc = lib.cil_add_file(db, p.encode(), data, len(data))
        if rc != 0:
            sys.exit(f"cil_add_file rejected {p}: rc={rc}")

    rc = lib.cil_compile(db)
    if rc != 0:
        return rc, "cil_compile"
    pdb = ctypes.c_void_p()
    rc = lib.cil_build_policydb(db, ctypes.byref(pdb))
    if rc != 0:
        return rc, "cil_build_policydb"
    return 0, ""


def main():
    paths = sys.argv[1:]
    if not paths:
        sys.exit(__doc__)
    rc, where = compile_files(paths)
    if rc == 0:
        print(f"policy COMPILED from {len(paths)} files")
    else:
        print(f"policy did NOT compile: {where} returned {rc} (libsepol messages above)", file=sys.stderr)
    sys.exit(0 if rc == 0 else 1)


if __name__ == "__main__":
    main()
