# Android 17 build helpers (not platform patches)

- `stub-system-desktop-cros-codecs/Android.bp` — copy to `system/desktop/cros-codecs/Android.bp` in the A17 tree. The published `android17-security-release` manifest lacks `platform/system/desktop/cros-codecs`, but `external/rust/cros-libva` imports a Soong module type from it, so the tree does not even analyse without this stub.
- `build-soong-pass-gomemlimit.diff` — optional, for build machines with ~16 GB of RAM: apply in `build/soong`, then build with zram/swap and `GOMEMLIMIT=30GiB GOGC=50`. Soong's analysis of the A17 tree otherwise needs ~64 GB. Not needed on machines with enough memory.
