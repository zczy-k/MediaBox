#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MediaBox 标识符重命名器: AVBox* -> MediaBox*

范围: app/src/main/java/**/*.kt|*.java  (纯机械重命名, 不改任何行为)
- AVBoxTheme/AVBoxThemeLightPreview/AVBoxThemeDarkPreview -> MediaBoxTheme*
- AVBoxBottomSheet / AVBoxAlertDialog / AVBoxDialog / AVBoxOptionSheet
- AVBoxOptionMenu / AVBoxTypography
- 单独出现的 AVBox(注释/文案常量) -> MediaBox

同时把文件名里带 AVBox 的组件文件改名(git mv 交给调用方或脚本内 os.rename)。

用法: python tools/rebrand_identifiers.py [--dry]
"""
import os
import re
import sys
import glob

SRC = os.path.join("app", "src", "main", "java")
DRY = "--dry" in sys.argv

RENAMES = {
    "AVBoxThemeLightPreview": "MediaBoxThemeLightPreview",
    "AVBoxThemeDarkPreview": "MediaBoxThemeDarkPreview",
    "AVBoxTheme": "MediaBoxTheme",
    "AVBoxBottomSheet": "MediaBoxBottomSheet",
    "AVBoxAlertDialog": "MediaBoxAlertDialog",
    "AVBoxOptionSheet": "MediaBoxOptionSheet",
    "AVBoxOptionMenu": "MediaBoxOptionMenu",
    "AVBoxDialog": "MediaBoxDialog",
    "AVBoxTypography": "MediaBoxTypography",
}

# 顺序: 长名先, 最后裸 AVBox
ORDER = sorted(RENAMES, key=len, reverse=True) + ["AVBox"]
BARE = re.compile(r"\bAVBox\b")


def rename_file(path: str) -> str:
    d, base = os.path.split(path)
    stem, ext = os.path.splitext(base)
    new = stem
    for a, b in RENAMES.items():
        if new == a:
            new = b
            break
    if new == stem:
        return path
    return os.path.join(d, new + ext)


def main():
    files = [f for f in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True)]
    files += [f for f in glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)]
    touched, renamed = 0, []

    for f in files:
        with open(f, encoding="utf-8") as fh:
            src = fh.read()
        out = src
        for a in ORDER:
            if a == "AVBox":
                out = BARE.sub("MediaBox", out)
            else:
                out = out.replace(a, RENAMES.get(a, "MediaBox"))
        if out != src:
            touched += 1
            if not DRY:
                with open(f, "w", encoding="utf-8") as fh:
                    fh.write(out)

        nf = rename_file(f)
        if nf != f and not DRY:
            os.rename(f, nf)
        if nf != f:
            renamed.append((os.path.basename(f), os.path.basename(nf)))

    print(("DRY-RUN " if DRY else "") + "content touched = %d files" % touched)
    if renamed:
        print("renamed files = %d" % len(renamed))
        for a, b in renamed:
            print("   %-42s -> %s" % (a, b))
    left = 0
    for f in files:
        try:
            with open(f, encoding="utf-8") as fh:
                if "AVBox" in fh.read():
                    left += 1
        except OSError:
            pass
    print("remaining files containing 'AVBox' = %d" % left)


if __name__ == "__main__":
    main()