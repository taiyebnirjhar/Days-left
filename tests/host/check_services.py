#!/usr/bin/env python3
"""Static guard for a crash that really happened (ForegroundServiceDidNotStartInTimeException).

Every service that is started with startForegroundService() must call startForeground() in onStartCommand BEFORE any `return`,
including the returns for requests it ignores (stop, already running). Exits non-zero if one does not.
"""
import glob, re, sys

bad = 0
for path in sorted(glob.glob("app/src/main/java/com/taiyeb/daysleft/*.java")):
    src = open(path, encoding="utf-8").read()
    m = re.search(r"public int onStartCommand\([^)]*\)\s*\{", src)
    if not m: continue
    # body of onStartCommand: from the brace to its matching brace
    depth, i = 1, m.end()
    while depth and i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0); i += 1
    body = src[m.end():i - 1]
    # keep code only (drop comments and strings) so a comment that says "return" cannot confuse us
    code = re.sub(r"//[^\n]*", "", body)
    code = re.sub(r'"(\\.|[^"\\])*"', '""', code)
    fg = code.find("startForeground(")
    ret = code.find("return")
    name = path.split("/")[-1]
    if "startForegroundService" not in "".join(open(p, encoding="utf-8").read() for p in glob.glob("app/src/main/java/com/taiyeb/daysleft/*.java") if name[:-5] in open(p, encoding="utf-8").read()):
        continue
    if fg < 0:
        print(f"FAIL {name}: onStartCommand never calls startForeground()"); bad += 1
    elif 0 <= ret < fg:
        print(f"FAIL {name}: onStartCommand can return before startForeground() (a start request would crash the app)"); bad += 1
    else:
        print(f"ok   {name}: startForeground() comes before every return")
sys.exit(1 if bad else 0)
