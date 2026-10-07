# Pinned manifests

Exact source revisions the patch series in `patches/<target>/` were made against. Every project points to an upstream revision (AOSP or LineageOS); our changes come only from `patches/`.

| file | target | upstream branch / tag |
| --- | --- | --- |
| `aosp14.xml` | `aosp14` | `android-14.0.0_r75` |
| `lineage21.xml` | `lineage21` | `lineage-21.0` (security patch level 2026-09-01) |
| `lineage22.xml` | `lineage22` | `lineage-22.2` (security patch level 2026-09-01) |
| `aosp15.xml` | `aosp15` | `android15-security-release` |
| `aosp16.xml` | `aosp16` | `android16-security-release` |
| `aosp17.xml` | `aosp17` | `android17-security-release` |

Use one with a local manifest file:

```bash
repo init -u https://android.googlesource.com/platform/manifest -b android15-security-release
cp manifests/aosp15.xml .repo/manifests/wsa-pinned.xml
repo init -m wsa-pinned.xml
repo sync -c -j8
```

For LineageOS use `-u https://github.com/LineageOS/android.git -b lineage-21.0` the same way.
