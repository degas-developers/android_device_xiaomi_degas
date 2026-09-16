# Out-of-tree patches

These are changes degas needs in AOSP/LineageOS projects that a device tree
cannot express. They live here so they are reviewable and reproducible, but
**they are not applied by the build** - `repo sync` will silently drop them, and
then the symptom comes back looking like a device bug. Re-apply after every sync:

```sh
cd "$ANDROID_BUILD_TOP"
git -C build/soong           apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/build_soong/*.patch
git -C frameworks/native     apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_native/*.patch
git -C packages/apps/Launcher3 apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/packages_apps_Launcher3/*.patch
git -C frameworks/base       apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_base/0002-*.patch \
                                   "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/frameworks_base/0003-*.patch
# frameworks_base/0001-* is obsolete - "*.patch" would take it too, so name the live ones
git -C vendor/lineage        apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/vendor_lineage/*.patch
git -C hardware/mediatek     apply "$ANDROID_BUILD_TOP"/device/xiaomi/degas/patches/hardware_mediatek/*.patch
```

Note `git -C <project> apply` resolves the patch path **relative to that
project**, so the paths above are absolute on purpose - a repo-relative path
fails with "can't open patch". Verify they are live before building:

```sh
git -C build/soong       status --porcelain   # M scripts/check_boot_jars/package_allowed_list.txt
git -C frameworks/native status --porcelain   # M services/surfaceflinger/SurfaceFlinger.cpp (+ Scheduler/Scheduler.cpp)
git -C packages/apps/Launcher3 status --porcelain  # M src/com/android/launcher3/states/SpringLoadedState.java
git -C frameworks/base   status --porcelain   # M services/core/.../BatteryStatsImpl.java (+ packages/SystemUI/.../UdfpsControllerOverlay.kt)
git -C vendor/lineage    status --porcelain   # ?? release/aconfig/bp4a/com.android.systemui/flashlight_strength_flag_values.textproto
git -C hardware/mediatek status --porcelain   # M hidl/audio/Android.bp
```

The vendor/lineage one adds an untracked file rather than modifying one, so
`git apply` leaves it as `??` - a `git status` that shows nothing means it did
not apply.

| Patch | Project | Why |
| --- | --- | --- |
| `frameworks_native/0002-SurfaceFlinger-do-not-mode-set-a-powered-off-display.patch` | `frameworks/native` | **The one that matters.** `initiateDisplayModeChanges()` drives a mode set into the panel with no power-mode check. About ten seconds after the screen goes off SurfaceFlinger drops the display to 60 Hz; the MediaTek composer answers that request on a dark panel by emitting vsync callbacks **forever** - measured at **143 async binder transactions/s** (144 Hz) from `composer@3.2-service` into SF, each one scheduling a vsync callback and waking a full commit/composite pass. Cost: **18 % of a core for as long as the screen stays off**. The patch drops the mode request while `!display->isPoweredOn()`; the mode is re-evaluated from policy at power-on. `isPoweredOn()` is `mPowerMode != OFF`, so **doze/AOD still mode-sets normally** (verified). |
| `packages_apps_Launcher3/0001-Launcher3-do-not-blur-the-wallpaper-during-a-drag.patch` | `packages/apps/Launcher3` | SPRING_LOADED (any icon or widget drag) asks for `DEPTH_15_PERCENT`, which `mapDepthToBlur()` turns into a **45px background blur** - and any non-zero blur radius forces client composition of the launcher window plus the wallpaper. Measured: `clientCompositionFrames` 98.5 %, RenderEngine 4.7-5.0 ms of a 6.94 ms budget, `presentToPresent` outliers of 200-1000 ms, janky frames 57.4 % vs 1.03 % with the blur off. Zeroes the depth **only for the drag state** - all-apps, overview and hint keep theirs, so the app drawer still looks the way it should. A `max_depth_blur_radius_enhanced` RRO cannot do this: it is the single multiplier for every state. |
| `build_soong/0001-check_boot_jars-allow-com.xiaomi.patch` | `build/soong` | The MediaTek IMS jars degas must put on the bootclasspath contain `com.xiaomi.ims`, which is not in the boot jar package allow list. `com.mediatek.*` is already there for the same reason. **Required - the build fails without it.** |
| `frameworks_native/0001-Scheduler-do-not-resync-hw-vsync-on-a-powered-off-display.patch` | `frameworks/native` | **Ineffective for the bug it was written for - a candidate to drop.** It stops `Scheduler::resyncToHardwareVsyncLocked()` from re-enabling hardware vsync on a powered-off display, which does fix a genuinely wrong state (`hwVsyncState` stuck `Enabled` with `mPeriodConfirmationInProgress=1` and `No Last HW vsync`), but on its own it changed the CPU cost by **nothing**. Kept only because the verified configuration includes it. Harmless: screen-on path re-checked at 144 Hz / `Disabled` / `powerMode=On`. |
| `vendor_lineage/0001-Enable-the-SystemUI-flashlight-strength-slider.patch` | `vendor/lineage` | Long-pressing the QS flashlight tile should open a brightness slider, and SystemUI ships the code for it, but `com.android.systemui.flashlight_strength` is a **read-only** aconfig flag that defaults to disabled: it is fused out at compile time, does not appear in `aflags list`, and `aflags enable` answers "no aconfig flag". The only place to flip it is a release-config value set, and LineageOS keeps its own in `vendor/lineage/release/aconfig/bp4a/com.android.systemui/` (the `Android.bp` there globs `*_flag_values.textproto`, so the new file needs no build-file change). degas can drive it: both flash-bearing cameras report `android.flash.info.strengthMaximumLevel = 100`, which is the characteristic `CameraManager.turnOnTorchWithStrengthLevel()` reads. Note the *other* pair, `android.flash.torchStrengthMaxLevel = 1`, is the per-capture-request key and is unrelated. |
| `frameworks_base/0003-udfps-hold-refresh-rate-while-overlay-shown.patch` | `frameworks/base` | Touching the fingerprint sensor brightens the **whole** screen whenever the display mode changes while local HBM is lit (measured: no mode change = spot only; pinned to 120 Hz, the per-touch UDFPS vote switched 120 -> 144 under the finger and every touch flashed). AOSP votes for the highest rate on every finger down at `PRIORITY_UDFPS`, and the panel runs LHBM at 120 Hz anyway. Stock does it per session: `libmisurfaceflinger` `FodMonitorThread::updateLHBMFodFlag()` holds `fod_monitor_default_fps` = 120 and skips the idle timer from `ENROLL_START`/`AUTH_START` to the matching stop. The patch adds `config_udfpsRefreshRate` (0 = AOSP behaviour); when set, `DisplayModeDirector` votes that rate and `UdfpsController` holds the vote from overlay shown to overlay hidden, and no longer votes per touch. degas sets 120 in `DegasFrameworksOverlay`. Replaces the old peak-refresh-rate overlay patch and the `RefreshRateHealService` it relied on. **Symptom if it goes missing:** full-screen flash on fingerprint touches at 60/90/120 Hz and in rapid repeated touches at 144 Hz, and apps lag after a fingerprint unlock at 144 Hz (DRM at 144, panel left at 120). |
| `hardware_mediatek/0001-drop-duplicate-mediatek-audio-service.patch` | `hardware/mediatek` | Upstream `hardware/mediatek` gained `android.hardware.audio.service.mediatek`, which degas already carries in `audio/` with the boot-crash fix, soundtrigger3 V3 and `vendor.mediatek.hardware.audio-impl`. Both in scope and soong fails with *"found in multiple namespaces"*. Drops upstream's definition; `device.mk` installs ours. **Required - the build fails without it.** |
| `frameworks_base/0002-BatteryStats-ignore-implausible-charge-time-from-health-HAL.patch` | `frameworks/base` | **Cosmetic, but shipped on purpose.** `computeChargeTimeRemaining()` believes the health HAL the moment its figure is not negative, and MediaTek's health service passes `/sys/class/power_supply/battery/time_to_full_now` straight through from the gauge. On degas that node does the arithmetic of a **4.4 mA** charge while the battery is taking **2030 mA**, so a phone on a 67 W charger is reported as *"full in 36 days"* (measured: node 2934120 s, falling 35400 s per percent). The patch caps what the HAL may claim at 12 h; past that the step tracker in the same class takes over, which measured **74 s per percent** here, i.e. about 1 h 40 min from 18 %. Symptom if it goes missing: the absurd estimate is back in Settings. Charging speed is unaffected either way. |
| `frameworks_base/0001-SystemUI-register-non-ONE_SHOT-doze-sensors-via-listener.patch` | `frameworks/base` | **OBSOLETE, kept for reference.** DozeSensors only registers doze sensors through `requestTriggerSensor`, which refuses anything that is not `REPORTING_MODE_ONE_SHOT`. degas' UDFPS long-press sensor is now supplied by our own `sensors.udfps.degas` sub-HAL and declared ONE_SHOT, so stock SystemUI subscribes by itself and this patch is no longer needed. |
