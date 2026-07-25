# Out-of-tree patches

These are changes degas needs in AOSP/LineageOS projects that a device tree
cannot express. They live here so they are reviewable and reproducible, but
**they are not applied by the build** - `repo sync` will silently drop them, and
then the symptom comes back looking like a device bug. Re-apply after every sync:

```sh
cd "$ANDROID_BUILD_TOP"
git -C frameworks/base apply device/xiaomi/degas/patches/frameworks_base/*.patch
git -C build/soong  apply device/xiaomi/degas/patches/build_soong/*.patch
```

| Patch | Project | Why |
| --- | --- | --- |
| `frameworks_base/0001-SystemUI-register-non-ONE_SHOT-doze-sensors-via-listener.patch` | `frameworks/base` | DozeSensors only registers doze sensors through `requestTriggerSensor`, which silently refuses anything that is not `REPORTING_MODE_ONE_SHOT`. degas' UDFPS long-press sensor is `SPECIAL_TRIGGER`, so fingerprint unlock is dead in AOD. Not degas-specific - worth upstreaming. |
| `build_soong/0001-check_boot_jars-allow-com.xiaomi.patch` | `build/soong` | The MediaTek IMS jars degas must put on the bootclasspath contain `com.xiaomi.ims`, which is not in the boot jar package allow list. `com.mediatek.*` is already there for the same reason. |
