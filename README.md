# Device tree for Xiaomi 14T (degas)

LineageOS device tree for the Xiaomi 14T, codename **degas**
(MediaTek Dimensity 8300-Ultra, MT6897).

| Basic     | Spec sheet                        |
| --------- | --------------------------------- |
| SoC       | MediaTek Dimensity 8300-Ultra (MT6897) |
| GPU       | Mali-G615 MC6                     |
| Display   | 6.67" AMOLED, 1220x2712, 144 Hz   |
| Android   | LineageOS 23 (based on stock Android 16, OS3.0.4.0.WNEMIXM) |

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
