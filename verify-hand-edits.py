#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Guards the one blob in vendor/xiaomi/degas that carries a hand edit which
# extract-files.py cannot reproduce.
#
# Everything else survives a re-extract on its own: blob_fixups live in
# extract-files.py, and FIX_SONAME / DISABLE_CHECKELF / PRESIGNED live in
# proprietary-files.txt, all of which are checked in. Audited 2026-07-26 by
# hashing all 2987 shipped blobs against the stock dump - 53 differed, 42 are
# blob_fixup output, 5 are FIX_SONAME, 1 (the displayconfig XML) differs only in
# comments, and 1 is the file below.
#
# Run it after every `./extract-files.py`, before building.

import hashlib
import sys
import zipfile
from os import path

VENDOR = path.join(
    path.dirname(path.abspath(__file__)),
    '..', '..', '..', 'vendor', 'xiaomi', 'degas', 'proprietary',
)

# ImsService.apk carries a smali edit that exports the receiver Android 14+
# refuses to register unexported. Without it com.mediatek.ims crash-loops and
# takes the boot-time notification shade with it. A re-extract silently
# restores the stock dex.
IMS_SERVICE = 'system_ext/priv-app/ImsService/ImsService.apk'
IMS_PATCHED_DEX_SHA1 = '2e3d60274d53035279019cfebfe1884467997e2e'
IMS_STOCK_DEX_SHA1 = '137fa86d1d7a84bf7295b736b157cadae656069a'


def main() -> int:
    apk = path.join(VENDOR, IMS_SERVICE)

    if not path.isfile(apk):
        print(f'FAIL: {IMS_SERVICE} is missing')
        return 1

    with zipfile.ZipFile(apk) as z:
        got = hashlib.sha1(z.read('classes.dex')).hexdigest()

    if got == IMS_PATCHED_DEX_SHA1:
        print(f'OK: {IMS_SERVICE} still carries the receiver-export patch')
        return 0

    if got == IMS_STOCK_DEX_SHA1:
        print(
            f'FAIL: {IMS_SERVICE} was reverted to the stock dex by a '
            're-extract.\n'
            '      Re-apply the receiver-export smali patch, or the device '
            'will boot with\n'
            '      com.mediatek.ims in a crash loop.'
        )
        return 1

    print(
        f'FAIL: {IMS_SERVICE} classes.dex is neither the stock nor the '
        f'patched one\n'
        f'      (sha1 {got}). Someone changed it - update this script if that '
        'was deliberate.'
    )
    return 1


if __name__ == '__main__':
    sys.exit(main())
