# Out-of-tree patches

These are changes degas needs in AOSP/LineageOS projects that a device tree
cannot express. They live here so they are reviewable and reproducible, but
**they are not applied by the build** - `repo sync` will silently drop them, and
then the symptom comes back looking like a device bug. Re-apply after every sync:

```sh
cd "$ANDROID_BUILD_TOP"
git -C frameworks/base   apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_base/*.patch
git -C frameworks/native apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_native/*.patch
git -C build/soong       apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/build_soong/*.patch
```

Note `git -C <project> apply` resolves the patch path **relative to that
project**, so the paths above are absolute on purpose - a repo-relative path
fails with "can't open patch". Verify they are live before building:

```sh
git -C frameworks/base   status --porcelain   # M packages/SystemUI/.../DozeSensors.java
git -C frameworks/native status --porcelain   # M services/surfaceflinger/Scheduler/Scheduler.cpp
git -C build/soong       status --porcelain   # M scripts/check_boot_jars/package_allowed_list.txt
```

| Patch | Project | Why |
| --- | --- | --- |
| `frameworks_native/0001-Scheduler-do-not-resync-hw-vsync-on-a-powered-off-display.patch` | `frameworks/native` | About ten seconds after the screen goes off SurfaceFlinger drops the display to its default 60 Hz mode. Applying a mode change calls `Scheduler::resyncToHardwareVsyncLocked()`, which clears the `Disallowed` hardware-VSYNC state and starts a period confirmation - but the panel is already dark and emits no VSYNC, so the confirmation never completes and SF spins at **18 % of a core for as long as the screen stays off**. The loop in `resyncToHardwareVsync()` already skips powered-off displays; the direct callers in `SurfaceFlinger.cpp` (the mode-change path among them) do not. Not degas-specific. |
| `build_soong/0001-check_boot_jars-allow-com.xiaomi.patch` | `build/soong` | The MediaTek IMS jars degas must put on the bootclasspath contain `com.xiaomi.ims`, which is not in the boot jar package allow list. `com.mediatek.*` is already there for the same reason. **Required - the build fails without it.** |
| `frameworks_base/0001-SystemUI-register-non-ONE_SHOT-doze-sensors-via-listener.patch` | `frameworks/base` | **OBSOLETE, kept for reference.** DozeSensors only registers doze sensors through `requestTriggerSensor`, which refuses anything that is not `REPORTING_MODE_ONE_SHOT`. degas' UDFPS long-press sensor is now supplied by our own `sensors.udfps.degas` sub-HAL and declared ONE_SHOT, so stock SystemUI subscribes by itself and this patch is no longer needed. |
