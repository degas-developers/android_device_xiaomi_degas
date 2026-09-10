# Device tree for Xiaomi 14T (degas)

LineageOS device tree for the Xiaomi 14T, codename **degas**
(MediaTek Dimensity 8300-Ultra, MT6897).

| Basic     | Spec sheet                        |
| --------- | --------------------------------- |
| SoC       | MediaTek Dimensity 8300-Ultra (MT6897) |
| GPU       | Mali-G615 MC6                     |
| Display   | 6.67" AMOLED, 1220x2712, 144 Hz   |
| Android   | LineageOS 23 (based on stock Android 16, OS3.0.4.0.WNEMIXM) |

## Building

Add [`degas.xml`](degas.xml) to `.repo/local_manifests/` of a LineageOS 23.2
checkout and run `repo sync`:

```sh
mkdir -p .repo/local_manifests
cp device/xiaomi/degas/degas.xml .repo/local_manifests/
repo sync
source build/envsetup.sh
breakfast degas userdebug
mka bacon
```

Read the comments in that manifest before editing it: several of the projects it
would seem to need are **already declared** by the LineageOS base manifest, and
re-declaring them makes `repo sync` abort with `duplicate path`.

Three repositories besides this one are involved:

| Repository | What for |
| --- | --- |
| `android_device_xiaomi_degas-kernel` | prebuilt GKI kernel, modules and base device tree |
| `android_external_wpa_supplicant_8` | one commit for WPA3/SAE on the MediaTek `gen4m` driver |
| `android_vendor_xiaomi_degas` | proprietary blobs — **not published**, see below |

### Vendor blobs

The vendor tree is **not published**. Generate it from your own device instead —
that is what `extract-files.py` is for:

```sh
./extract-files.py <path to an extracted stock firmware dump>
./extract-files.py            # or, with no argument, pull from a connected device over adb
```

Use the stock build this tree targets (`OS3.0.4.0.WNEMIXM`); a different build
may ship different blobs and different sonames. After every extraction run
`./verify-hand-edits.py` — one blob carries a hand edit that a re-extract
silently reverts, and the script fails loudly if that happened. Do **not** run
`--regenerate_makefiles`: it discards checked-in edits.

## Credits

Based on the [duchamp device tree](https://github.com/mt6897-devs/device_xiaomi_duchamp)
by [mt6897-devs](https://github.com/mt6897-devs) (same MT6897 platform).

The upstream git history is preserved: commits inherited from duchamp are
prefixed `duchamp:` and keep their original authors.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Parts of this tree are not covered by that license — the GPL-2.0 kernel UAPI
header in `udfps/`, and the configuration extracted from Xiaomi stock firmware.
See [NOTICE](NOTICE) for the full list, the attribution required by Apache-2.0
section 4, and the record of modifications made to the upstream tree.

This is an unofficial community device tree. It comes with no warranty, and is
not endorsed by Xiaomi, MediaTek, Google or the LineageOS project.
