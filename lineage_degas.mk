#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Brand the build with the maintainer's name. LINEAGE_BUILDTYPE is forced back to
# UNOFFICIAL by vendor/lineage/config/version.mk unless it is one of the official
# release types, so EXTRAVERSION is the supported hook - it lands in
# ro.lineage.display.version, which Settings shows as the LineageOS version.
LINEAGE_EXTRAVERSION := -Advnirr

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from device makefile.
$(call inherit-product, device/xiaomi/degas/device.mk)

# Inherit some common LineageOS stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_BRAND := Xiaomi
PRODUCT_DEVICE := degas
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_MODEL := 2406APNFAG
PRODUCT_NAME := lineage_degas

PRODUCT_CHARACTERISTICS := nosdcard
PRODUCT_GMS_CLIENTID_BASE := android-xiaomi

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="missi-user 16 BP2A.250605.031.A3 OS3.0.4.0.WNEMIXM release-keys" \
    BuildFingerprint=Xiaomi/degas_global/degas:16/BP2A.250605.031.A3/OS3.0.4.0.WNEMIXM:user/release-keys \
    DeviceProduct=degas_global
