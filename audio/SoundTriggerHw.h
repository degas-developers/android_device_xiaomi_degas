/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/soundtrigger3/BnSoundTriggerHw.h>

#include <cstdint>

namespace aidl::android::hardware::soundtrigger3 {

using ::aidl::android::media::soundtrigger::ModelParameter;
using ::aidl::android::media::soundtrigger::ModelParameterRange;
using ::aidl::android::media::soundtrigger::PhraseSoundModel;
using ::aidl::android::media::soundtrigger::Properties;
using ::aidl::android::media::soundtrigger::RecognitionConfig;
using ::aidl::android::media::soundtrigger::SoundModel;

class SoundTriggerHw : public BnSoundTriggerHw {
  public:
    SoundTriggerHw();
    virtual ~SoundTriggerHw();

    ndk::ScopedAStatus getProperties(Properties* _aidl_return) override;

    ndk::ScopedAStatus registerGlobalCallback(
            const std::shared_ptr<ISoundTriggerHwGlobalCallback>& callback) override;

    ndk::ScopedAStatus loadSoundModel(const SoundModel& soundModel,
                                      const std::shared_ptr<ISoundTriggerHwCallback>& callback,
                                      int32_t* _aidl_return) override;

    ndk::ScopedAStatus loadPhraseSoundModel(
            const PhraseSoundModel& soundModel,
            const std::shared_ptr<ISoundTriggerHwCallback>& callback,
            int32_t* _aidl_return) override;

    ndk::ScopedAStatus unloadSoundModel(int32_t modelHandle) override;

    ndk::ScopedAStatus startRecognition(int32_t modelHandle, int32_t deviceHandle, int32_t ioHandle,
                                        const RecognitionConfig& config) override;

    ndk::ScopedAStatus stopRecognition(int32_t modelHandle) override;

    ndk::ScopedAStatus forceRecognitionEvent(int32_t modelHandle) override;

    ndk::ScopedAStatus queryParameter(int32_t modelHandle, ModelParameter modelParam,
                                      std::optional<ModelParameterRange>* _aidl_return) override;

    ndk::ScopedAStatus getParameter(int32_t modelHandle, ModelParameter modelParam,
                                    int32_t* _aidl_return) override;

    ndk::ScopedAStatus setParameter(int32_t modelHandle, ModelParameter modelParam,
                                    int32_t value) override;

  private:
    // The implementation of this class lives in the prebuilt
    // vendor/lib64/hw/android.hardware.soundtrigger3-impl.so, but the object is
    // allocated here, by ndk::SharedRefBase::make<SoundTriggerHw>() in
    // service.cpp — so the allocation size comes from THIS declaration.
    //
    // Without the storage below the header declares no members at all, so
    // sizeof(SoundTriggerHw) == sizeof(BnSoundTriggerHw) == 80, while the
    // prebuilt constructor writes its own members straight past that:
    //
    //   this+80   const char* mModuleName
    //   this+88   sound_trigger_hw_device*
    //   this+96   (flag, set to 1)
    //   this+104  android::SortedVector<key_value_pair_t<int, SoundModelClient>>
    //   this+144  32 zeroed bytes
    //   this+176  pthread_mutex_t (40 bytes) -> the object ends at 216
    //
    // i.e. every construction overflows the heap block by 136 bytes. The
    // immediate victim is mModuleName: hw_get_module_by_class() dlopen()s the
    // vendor sound_trigger module right after it is stored, the allocator hands
    // that memory out again, and the "SoundTriggerHw(): mModuleName %s" log line
    // a few instructions later dereferences whatever landed there — SIGSEGV in
    // __strlen_aarch64, once per boot, until init restarts the service.
    //
    // Reserve well past 216 so the layout stays valid even if a member we
    // cannot see from here sits beyond the constructor's reach.
    alignas(8) uint8_t mPrebuiltStorage[512];
};

}  // namespace aidl::android::hardware::soundtrigger3
