#!/vendor/bin/sh
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# The first backlight level the framework writes during boot never reaches the
# panel (no lcm_setbacklight_cmdq), yet leds-mtk caches it, and it drops every
# later write of the same value. The panel keeps bl_lvl = 0 until the level
# actually changes, and local HBM computed from 0 blanks the whole screen on a
# fingerprint touch. Step one level away and back so the panel learns it.

node=/sys/class/leds/lcd-backlight/brightness
level=$(cat $node) || exit 1

if [ "$level" -gt 1 ]; then
    echo $((level - 1)) > $node
else
    echo $((level + 1)) > $node
fi
echo $level > $node
