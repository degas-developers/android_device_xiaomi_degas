#!/usr/bin/env python3
"""Post-process the generated vendor makefiles.

extract-utils names a module after the file, so the per-sensor camera tuning
databases all collide ("module ISP_param.db already defined"). Soong installs by
filename + sub_dir, not by module name, so the modules can simply be renamed
uniquely. This has to be re-run after every ./extract-files.py - it is the one
fix-up that cannot be expressed in proprietary-files.txt.
"""
import re
import sys
from pathlib import Path

VENDOR = Path(__file__).resolve().parents[3] / 'vendor' / 'xiaomi' / 'degas'
BP = VENDOR / 'Android.bp'
MK = VENDOR / 'degas-vendor.mk'
DUPES = ('ISP_param.db', 'ISP_mapping.db')

bp = BP.read_text()
blocks = re.findall(r'sh_binary \{.*?\n\}\n', bp, re.S)
renames = []
for block in blocks:
    name = re.search(r'name: "([^"]+)"', block)
    sub = re.search(r'sub_dir: "([^"]+)"', block)
    if not name or not sub or name.group(1) not in DUPES:
        continue
    # .../tuning_DB/<sensor>/mt6897 -> <sensor>
    tag = sub.group(1).split('/')[-2]
    new = '%s_%s' % (name.group(1).replace('.', '_'), tag)
    bp = bp.replace(block, block.replace('name: "%s"' % name.group(1),
                                         'name: "%s"' % new, 1), 1)
    renames.append((name.group(1), new))

if not renames:
    print('nothing to rename (already fixed?)')
    sys.exit(0)

BP.write_text(bp)

mk = MK.read_text().splitlines(keepends=True)
queue = list(renames)
out = []
for line in mk:
    stripped = line.strip().rstrip('\\').strip()
    if queue and stripped == queue[0][0]:
        out.append(line.replace(stripped, queue.pop(0)[1], 1))
    else:
        out.append(line)
MK.write_text(''.join(out))

print('renamed %d modules; %d mk entries left unmatched' % (len(renames), len(queue)))
