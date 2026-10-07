#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从诊断包的真机文件日志里，解析「线路N · 清晰度」标签的出现时序。

为什么要有这个脚本
------------------
「清晰度显示得晚」这件事光靠读代码判断不了：标签数据有两条来源
（详情就绪时读记忆 / 异步探测直连线路），哪条先命中取决于该片在
VideoQualityMemory 里的命中数，属于运行期数据。所以必须拿真机日志算。

数据口径（都取自 preload_debug.log 的毫秒时间戳）
------------------------------------------------
  t_open    echo-detail-open            打开卡片
  t_ready   echo-detail sync            详情就绪（紧接着 publishLineQualityHeights）
  t_label   echo-line-heights 里 raw 首次非空 —— 标签后缀的数据就绪时刻
                                         （即「线路N · 720P」里的 720P 可显示）
  t_probe   echo-line-probe 的 lines/remembered/missing/direct

用法
----
    python tools/analyze_label_timing.py <日志文件>

日志可由诊断包取出：
    adb shell run-as com.zczy.mediabox.diag cat files/preload_debug.log > devlog.txt
"""
import re
import sys
from collections import OrderedDict

TS = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d) ")
OPEN = re.compile(r"echo-detail-open .*key=(\S+) id=(\S+)")
SYNC = re.compile(r"echo-detail sync -> (\S+)")
HEIGHTS = re.compile(r"echo-line-heights site=(\S+) vod=(\S+) .*?raw=(\{[^}]*\})")
PROBE = re.compile(
    r"echo-line-probe .*?lines=(\d+) mode=(\S+) remembered=(\d+) missing=(\d+) direct=(\d+)"
)


def ms(m):
    """把 'MM-dd HH:MM:SS.mmm' 折成可比较的整数毫秒（同日即可，跨日不管）。"""
    return (((int(m.group(2)) * 24 + int(m.group(3))) * 60 + int(m.group(4))) * 60 + int(
        m.group(5)
    )) * 1000 + int(m.group(6))


def read_text(path):
    """
    ⚠️ 必须自动识别编码：`adb ... > file` 在 **PowerShell** 下会把 stdout 写成
    UTF-16LE(带 BOM `\\xff\\xfe`),而 Bash 下是 UTF-8。写死一种就会一行都解析不出来
    (表现为"共解析 0 次开片",极易被误判成日志本身没内容)。
    """
    with open(path, "rb") as fh:
        raw = fh.read()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return raw.decode("utf-16")
    if raw[:3] == b"\xef\xbb\xbf":
        return raw.decode("utf-8-sig")
    return raw.decode("utf-8", errors="replace")


def parse(path):
    """一次开片 = 一段会话，以 echo-detail-open 起、下一次 open 或文件结束止。"""
    sessions = []
    cur = None
    for line in read_text(path).splitlines():
        m = TS.match(line)
        if not m:
            continue
        t = ms(m)
        body = line[m.end():]
        o = OPEN.search(body)
        if o:
            cur = OrderedDict(
                t_open=t, key=o.group(1), vod=o.group(2), t_ready=None,
                t_label=None, label_raw=None, t_probe=None,
                lines=None, remembered=None, missing=None, direct=None,
            )
            sessions.append(cur)
            continue
        if cur is None:
            continue
        if cur["t_ready"] is None:
            s = SYNC.search(body)
            if s:
                cur["t_ready"] = t
                cur["sync_flag"] = s.group(1)
        p = PROBE.search(body)
        if p and cur["t_probe"] is None:
            cur["t_probe"] = t
            cur["lines"] = int(p.group(1))
            cur["remembered"] = int(p.group(3))
            cur["missing"] = int(p.group(4))
            cur["direct"] = int(p.group(5))
        h = HEIGHTS.search(body)
        if h and cur["t_label"] is None and h.group(3) != "{}":
            # raw 首次非空 = 标签后缀的数据就绪
            cur["t_label"] = t
            cur["label_raw"] = h.group(3)
    return sessions


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    sessions = parse(sys.argv[1])
    done = [s for s in sessions if s["t_ready"] is not None]

    print(f"共解析 {len(sessions)} 次开片，其中 {len(done)} 次拿到详情就绪\n")
    hdr = f"{'源':<14}{'线路':>4}{'记忆':>5}{'可探':>5}{'就绪':>9}{'就绪->标签':>11}{'开片->标签':>11}"
    print(hdr)
    print("-" * len(hdr))
    ready2label, open2label = [], []
    shown = 0
    for s in done:
        r2l = (s["t_label"] - s["t_ready"]) if s["t_label"] else None
        o2l = (s["t_label"] - s["t_open"]) if s["t_label"] else None
        if r2l is not None:
            shown += 1
            ready2label.append(r2l)
            open2label.append(o2l)
        print(
            f"{s['key']:<14}"
            f"{(s['lines'] if s['lines'] else 0):>4}"
            f"{(s['remembered'] if s['remembered'] is not None else -1):>5}"
            f"{(s['direct'] if s['direct'] is not None else -1):>5}"
            f"{(s['t_ready'] - s['t_open']):>8}ms"
            f"{(str(r2l) + 'ms') if r2l is not None else '未出现':>11}"
            f"{(str(o2l) + 'ms') if o2l is not None else '未出现':>11}"
        )

    print()
    print(f"★ 标签数据出现率: {shown}/{len(done)} = {shown * 100 // max(1, len(done))}%")
    print("  '未出现' 的含义:该次开片直到日志结束都没有任何一条线路拿到实测画质 ——")
    print("  即标签**始终只有序号、没有 · 720P**。这不是'晚',是'没有'。")
    if ready2label:
        ready2label.sort()
        open2label.sort()
        n = len(ready2label)
        print()
        print(f"出现时的样本数 {n}")
        print(f"详情就绪 -> 标签数据:  中位 {ready2label[n // 2]}ms   最小 {ready2label[0]}ms   最大 {ready2label[-1]}ms")
        print(f"开片     -> 标签数据:  中位 {open2label[n // 2]}ms   最小 {open2label[0]}ms   最大 {open2label[-1]}ms")
        print()
        print("解读:就绪->标签这一段若≈0ms 说明靠记忆命中;明显>0 说明记忆未命中、等探测/播放实测补齐。")
        print("      数十秒级的样本基本是**播放时才写入**的(用户真正点播了那条线路)。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
