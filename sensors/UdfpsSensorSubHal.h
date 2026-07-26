/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <V2_0/SubHal.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {

using ::android::sp;
using ::android::hardware::Return;
using ::android::hardware::sensors::V1_0::Event;
using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SensorInfo;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_0::implementation::IHalProxyCallback;
using ::android::hardware::sensors::V2_0::implementation::ISensorsSubHal;

/*
 * A one-shot wake-up sensor that fires when a finger is pressed on the
 * in-display fingerprint sensor while the panel is in AOD.
 *
 * degas has no such sensor in hardware: the MediaTek SCP sub-HAL advertises
 * xiaomi.sensor.fod, but touch runs in Xiaomi's THP mode where frames are
 * processed in userspace, so the SCP never sees the press and that sensor never
 * fires. The press does reach the input subsystem though - the goodix driver
 * emits vendor key 0x152 before the matching touch stream - and that is what
 * this sub-HAL turns into a sensor event.
 *
 * The touch pipeline cannot be used directly for this: while the display is not
 * interactive AOSP drops events from non-waking devices, and marking the
 * touchscreen as waking makes it both wake on any touch and still not deliver
 * the event. Feeding config_dozeUdfpsLongPressSensorType instead lets
 * DozeTriggers call UdfpsController.onAodInterrupt(), which dispatches the
 * finger-down straight to the fingerprint HAL while the device stays dozing.
 */
class UdfpsSensorSubHal : public ISensorsSubHal {
  public:
    UdfpsSensorSubHal();
    ~UdfpsSensorSubHal();

    // ISensors
    Return<void> getSensorsList(getSensorsList_cb _hidl_cb) override;
    Return<Result> setOperationMode(OperationMode mode) override;
    Return<Result> activate(int32_t sensorHandle, bool enabled) override;
    Return<Result> batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                         int64_t maxReportLatencyNs) override;
    Return<Result> flush(int32_t sensorHandle) override;
    Return<Result> injectSensorData(const Event& event) override;
    Return<void> registerDirectChannel(const SharedMemInfo& mem,
                                       registerDirectChannel_cb _hidl_cb) override;
    Return<Result> unregisterDirectChannel(int32_t channelHandle) override;
    Return<void> configDirectReport(int32_t sensorHandle, int32_t channelHandle, RateLevel rate,
                                    configDirectReport_cb _hidl_cb) override;

    // ISensorsSubHal
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) override;
    const std::string getName() override { return "degas-udfps"; }
    Return<Result> initialize(const sp<IHalProxyCallback>& halProxyCallback) override;

  private:
    // Opens the goodix input device, looked up by name so a change in event
    // numbering does not break us. Returns -1 on failure.
    static int openTouchDevice();

    void startReader();
    void stopReader();
    void readerLoop(int fd);
    void reportPress();

    SensorInfo mSensorInfo{};

    // Whether a press should be reported. Cleared when one is, since the
    // framework re-arms a one-shot sensor itself.
    std::atomic<bool> mArmed{false};

    // Guards the reader thread's lifecycle. Never held while the thread could
    // be waiting on mCallbackMutex, so that stopReader()'s join cannot deadlock.
    std::mutex mThreadMutex;
    std::thread mThread;
    std::atomic<bool> mStop{false};

    // Guards mCallback, which the HalProxy replaces on every framework restart.
    std::mutex mCallbackMutex;
    sp<IHalProxyCallback> mCallback;
};

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_0
}  // namespace sensors
}  // namespace hardware
}  // namespace android
