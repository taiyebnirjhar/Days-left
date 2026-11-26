#!/usr/bin/env python3
"""truth.json -> truth.tsv (one element per line: kind, spoken, start, end, text), which the Java test reads."""
import json, sys, os
d = sys.argv[1]
t = json.load(open(os.path.join(d, "truth.json")))
with open(os.path.join(d, "truth.tsv"), "w", encoding="utf-8") as f:
    for e in t["elements"]:
        f.write("%s\t%d\t%.3f\t%.3f\t%s\n" % (e["kind"], 1 if e["spoken"] else 0, e["start"], e["end"], e["text"].replace("\t", " ").replace("\n", " ")))
