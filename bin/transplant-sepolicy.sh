#!/usr/bin/env bash
# Transplants Microsoft service domains into our system_ext policy.
#
#   bin/transplant-sepolicy.sh <our selinux dir> <Microsoft selinux dir>
#
#   example: bin/transplant-sepolicy.sh /mnt/los/new13/system_ext/etc/selinux \
#                                      /mnt/los/wsa/system_ext/etc/selinux
#
# WHY. Microsoft services (graphics, Windows link, system preparation) live in
# vendor, which we do not touch, and there are over a hundred rules for them there.
# But their domains and types are DECLARED in Microsoft's system_ext. Once that
# partition is replaced with ours, the system loses the domains of its own
# services and init refuses to start them:
#
#   Could not start service 'sysprep_service': Cannot find
#     '/vendor/bin/sysprep_service': Permission denied
#
# ALL types missing from our policy are transplanted, not a list guessed from the
# first error seen. The first version of this script did that: it moved three
# services found from a single log message, and the next run stopped on the
# fourth — sysprep_service, without which post-fs-data does not complete and
# zygote does not start. The list comes from the difference, not from observation.
set -euo pipefail

OUR="${1:?specify our etc/selinux directory}"
MS="${2:?specify the Microsoft etc/selinux directory}"
VENDOR_SELINUX="${VENDOR_SELINUX:-/mnt/los/tmp/vsel/selinux}"
# The platform policy is needed not for transplanting but to know its types: rules like
#   (allow system_app wsa_systemapp_service (service_manager (add)))
# link a PLATFORM TYPE with our new one. Without this list such rules are dropped
# as referring to an unknown type; the name-to-type binding works but the
# permission is missing, and the service still fails to register.
PLAT_SELINUX="${PLAT_SELINUX:-/mnt/los/new13/system/system/etc/selinux}"

python3 - "$OUR" "$MS" "$VENDOR_SELINUX" "$PLAT_SELINUX" <<'PY'
import re, sys, os

our_dir, ms_dir, vendor_dir, plat_dir = sys.argv[1:5]
our_cil = os.path.join(our_dir, "system_ext_sepolicy.cil")
ms_cil  = os.path.join(ms_dir,  "system_ext_sepolicy.cil")

our = open(our_cil).read().splitlines()
ms  = open(ms_cil).read().splitlines()

# MARK is also matched against already-processed files (idempotency), so it stays as is.
MARK = "; домены служб Microsoft, перенесены bin/transplant-sepolicy.sh"
if MARK in our:
    print("domains already transplanted — nothing to do"); sys.exit(0)

def types(lines):
    return {m.group(1) for l in lines for m in [re.match(r'^\(type ([a-z_0-9]+)\)$', l)] if m}

missing = sorted(types(ms) - types(our))
if not missing:
    print("no missing types"); sys.exit(0)
print("missing types: %d" % len(missing))
print("  " + " ".join(missing))

out = [MARK]

# 1. HAL attributes: the vendor policy refers to them.
for l in ms:
    if re.match(r'^\((typeattribute|expandtypeattribute) \(?hal_', l) and any(t in l for t in missing):
        out.append(l)

# 2. The types themselves and their roles.
for t in missing:
    out.append("(type %s)" % t)
    if "(roletype object_r %s)" % t in ms:
        out.append("(roletype object_r %s)" % t)

# 3. Attribute membership. Microsoft's lines cannot be copied as is: they list
#    hundreds of Microsoft build types we do not have, and the policy would not
#    compile. So from each one we take only the intersection with missing types.
for l in ms:
    m = re.match(r'^\(typeattributeset ([a-z_0-9]+) \(([^)]*)\)\)$', l)
    if not m:
        continue
    attr, members = m.group(1), m.group(2).split()
    mine = [x for x in members if x in missing]
    if mine and attr not in missing:
        out.append("(typeattributeset %s (%s))" % (attr, " ".join(mine)))

# 4. Rules where ALL mentioned types are known to us.
plat_cil = os.path.join(plat_dir, "plat_sepolicy.cil")
plat_types = types(open(plat_cil).read().splitlines()) if os.path.exists(plat_cil) else set()
known = types(our) | set(missing) | plat_types
for l in ms:
    if not l.startswith("(allow "):
        continue
    words = set(re.findall(r'[a-z_0-9]+', l))
    if any(t in words for t in missing) and words & set(missing):
        subjects = re.match(r'^\(allow ([a-z_0-9]+) ([a-z_0-9]+) ', l)
        if subjects and all(s in known for s in subjects.groups()):
            out.append(l)

open(our_cil, "a").write("\n" + "\n".join(out) + "\n")
print("statements added to policy: %d" % len(out))

# 5. Mapping. The vendor policy is written in VERSIONED Treble types:
#      (typetransition init_33_0 gskserver_exec_33_0 process gskserver)
#    The versioned type is linked to the real one by the mapping of the version
#    vendor requires. Without it the types are declared and the file label is
#    right, but there is no domain transition — and init will not start the service.
vers = open(os.path.join(vendor_dir, "plat_sepolicy_vers.txt")).read().strip()
our_map = os.path.join(our_dir, "mapping", vers + ".cil")
ms_map  = os.path.join(ms_dir,  "mapping", vers + ".cil")
if os.path.exists(our_map) and os.path.exists(ms_map):
    lines = [l for l in open(ms_map).read().splitlines()
             if any(re.match(r'^\(typeattributeset %s_[0-9]+_[0-9]+ \(%s\)\)$' % (t, t), l) for t in missing)]
    if lines:
        open(our_map, "a").write("\n; Microsoft services, transplanted by bin/transplant-sepolicy.sh\n"
                                 + "\n".join(lines) + "\n")
    print("added to mapping %s: %d" % (vers, len(lines)))
else:
    print("WARNING: mapping not found — there will be no domain transition", file=sys.stderr)

# 6. Context files. Declaring the type is not enough: a service or property name
#    is bound to the type separately, in *_contexts. Without that the service
#    gets the default type and registration is denied:
#      avc: denied { add } for name=com.microsoft.windows.systemapp.IWsaSystemApp
#        scontext=u:r:system_app:s0 tcontext=u:object_r:default_android_service
#    The Microsoft app then crashes with SecurityException right at startup.
for name in ("system_ext_service_contexts", "system_ext_property_contexts"):
    ours_p, ms_p = os.path.join(our_dir, name), os.path.join(ms_dir, name)
    if not (os.path.exists(ours_p) and os.path.exists(ms_p)):
        continue
    have = set(open(ours_p).read().split("\n"))
    add = [l for l in open(ms_p).read().split("\n")
           if l.strip() and not l.startswith("#") and l not in have
           and re.search(r'microsoft|wsa', l, re.I)]
    if add:
        open(ours_p, "a").write("\n# Microsoft services, transplanted by bin/transplant-sepolicy.sh\n"
                                + "\n".join(add) + "\n")
    print("%s: lines transplanted %d" % (name, len(add)))
PY
