# Out-of-tree patches

These are changes degas needs in AOSP/LineageOS projects that a device tree
cannot express. They live here so they are reviewable and reproducible, but
**they are not applied by the build** - `repo sync` will silently drop them, and
then the symptom comes back looking like a device bug. Re-apply after every sync:

```sh
cd "$ANDROID_BUILD_TOP"
git -C build/soong       apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/build_soong/*.patch
git -C frameworks/native apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_native/*.patch
git -C frameworks/base   apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_base/*.patch   # obsolete, optional
```

Note `git -C <project> apply` resolves the patch path **relative to that
project**, so the paths above are absolute on purpose - a repo-relative path
fails with "can't open patch". Verify they are live before building:

```sh
git -C build/soong       status --porcelain   # M scripts/check_boot_jars/package_allowed_list.txt
git -C frameworks/native status --porcelain   # M services/surfaceflinger/SurfaceFlinger.cpp (+ Scheduler/Scheduler.cpp)
git -C frameworks/base   status --porcelain   # M packages/SystemUI/.../DozeSensors.java
```

| Patch | Project | Why |
| --- | --- | --- |
| `frameworks_native/0002-SurfaceFlinger-do-not-mode-set-a-powered-off-display.patch` | `frameworks/native` | **The one that matters.** `initiateDisplayModeChanges()` drives a mode set into the panel with no power-mode check. About ten seconds after the screen goes off SurfaceFlinger drops the display to 60 Hz; the MediaTek composer answers that request on a dark panel by emitting vsync callbacks **forever** - measured at **143 async binder transactions/s** (144 Hz) from `composer@3.2-service` into SF, each one scheduling a vsync callback and waking a full commit/composite pass. Cost: **18 % of a core for as long as the screen stays off**. The patch drops the mode request while `!display->isPoweredOn()`; the mode is re-evaluated from policy at power-on. `isPoweredOn()` is `mPowerMode != OFF`, so **doze/AOD still mode-sets normally** (verified). |
| `build_soong/0001-check_boot_jars-allow-com.xiaomi.patch` | `build/soong` | The MediaTek IMS jars degas must put on the bootclasspath contain `com.xiaomi.ims`, which is not in the boot jar package allow list. `com.mediatek.*` is already there for the same reason. **Required - the build fails without it.** |
| `frameworks_native/0001-Scheduler-do-not-resync-hw-vsync-on-a-powered-off-display.patch` | `frameworks/native` | **Ineffective for the bug it was written for - a candidate to drop.** It stops `Scheduler::resyncToHardwareVsyncLocked()` from re-enabling hardware vsync on a powered-off display, which does fix a genuinely wrong state (`hwVsyncState` stuck `Enabled` with `mPeriodConfirmationInProgress=1` and `No Last HW vsync`), but on its own it changed the CPU cost by **nothing**. Kept only because the verified configuration includes it. Harmless: screen-on path re-checked at 144 Hz / `Disabled` / `powerMode=On`. |
| `frameworks_base/0001-SystemUI-register-non-ONE_SHOT-doze-sensors-via-listener.patch` | `frameworks/base` | **OBSOLETE, kept for reference.** DozeSensors only registers doze sensors through `requestTriggerSensor`, which refuses anything that is not `REPORTING_MODE_ONE_SHOT`. degas' UDFPS long-press sensor is now supplied by our own `sensors.udfps.degas` sub-HAL and declared ONE_SHOT, so stock SystemUI subscribes by itself and this patch is no longer needed. |
