/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/mediatek/hardware/audio/BnMtkAudio.h>

#include <cstdint>

namespace aidl::vendor::mediatek::hardware::audio {

using ::aidl::vendor::mediatek::hardware::audio::IAudioParameterChangedCallback;
using ::aidl::vendor::mediatek::hardware::audio::Result;

class MtkAudio : public BnMtkAudio {
  public:
    MtkAudio();
    virtual ~MtkAudio();

    ndk::ScopedAStatus setAudioParameterChangedCallback(
            const std::shared_ptr<IAudioParameterChangedCallback>& callback, int32_t cbkKey,
            Result* pResult) override;

    ndk::ScopedAStatus clearAudioParameterChangedCallback(int32_t cbkKey, Result* pResult) override;

  private:
    // Same trap as in SoundTriggerHw.h — read the comment there. The object is
    // allocated from this declaration by ndk::SharedRefBase::make<MtkAudio>(),
    // but constructed by the prebuilt
    // vendor/lib64/hw/vendor.mediatek.hardware.audio-impl.so, whose constructor
    // builds members in place at this+80 and this+96 while
    // sizeof(BnMtkAudio) is 80. No crash has been traced to this one, but the
    // overflow is the same kind and it is silent.
    alignas(8) uint8_t mPrebuiltStorage[512];
};

} // namespace aidl::vendor::mediatek::hardware::audio
