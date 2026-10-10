#!/usr/bin/env python3
"""Reassembles VBSHT screenshot chunks from a logcat dump into PNG files."""
import base64, os, re, sys

src, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)
name, chunks = None, []
count = 0
for line in open(src, errors="replace"):
    m = re.search(r"VBSHT: BEGIN (\S+)", line)
    if m:
        name, chunks = m.group(1), []
        continue
    m = re.search(r"VBSHT: D (\S+)", line)
    if m and name:
        chunks.append(m.group(1))
        continue
    m = re.search(r"VBSHT: END (\S+)", line)
    if m and name == m.group(1):
        try:
            data = base64.b64decode("".join(chunks))
            with open(os.path.join(outdir, name + ".png"), "wb") as f:
                f.write(data)
            count += 1
        except Exception as e:
            print("bad shot", name, e)
        name, chunks = None, []
print(f"{count} screenshots written to {outdir}")
