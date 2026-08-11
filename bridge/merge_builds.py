"""Merge the locally built TelegramEera plugin into the builds branch.

Run from the builds-branch checkout (/tmp/cskybuilds):
    python merge_builds.py <path-to-telegrameera-cs3> <path-to-generated-plugins-json>
"""
import hashlib
import json
import sys

cs3_path, local_json_path = sys.argv[1], sys.argv[2]

# 1. copy the .cs3 next to this repo root (same dir as plugins.json)
import shutil

shutil.copy(cs3_path, "TelegramEera.cs3")

# 2. load remote + local plugin lists
remote = json.load(open("plugins.json"))
local = json.load(open(local_json_path))

# 3. append any plugins not already present
names = {p["name"] for p in remote}
added = [p for p in local if p["name"] not in names]
merged = remote + added
json.dump(merged, open("plugins.json", "w"), indent=4)

print("total plugins now:", len(merged))
print("has TelegramEera:", any(p["name"] == "TelegramEera" for p in merged))
