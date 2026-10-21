#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把仍在使用的 960 视口旧填充图标批量重绘为 24dp 线稿（2dp 描边 / 圆头圆角）。

设计总案铁律：全站图标统一线稿，填充图标在浅色底上显"重"，与线稿混排会乱。

用法：python tools/rebrand_icons_v2.py
"""
import io
import os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "app", "src", "main", "res", "drawable")

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- MediaBox · 设计总案线稿图标(24dp / 2dp 描边 / 圆头) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
'''
TAIL = '</vector>\n'


def line(*paths, w="2"):
    return "".join(
        '    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"\n'
        '        android:strokeWidth="%s" android:strokeLineCap="round" android:strokeLineJoin="round"\n'
        '        android:pathData="%s" />\n' % (w, p)
        for p in paths
    )


def solid(*paths):
    """需要实心的小元素(选中点、播放三角等)——仍用填充,但只用于极小面积"""
    return "".join(
        '    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />\n' % p for p in paths
    )


# ── 图标清单:名字 → path 组 ─────────────────────────────────────────
ICONS = {
    # 播放器控制
    "player_ic_play": line("M8 5l11 7-11 7z"),
    "player_ic_pause": line("M9 5v14", "M15 5v14", w="2.4"),
    "player_ic_prev": line("M18 6v12l-9-6z", "M7 5v14"),
    "player_ic_next": line("M6 6v12l9-6z", "M17 5v14"),
    "player_ic_back": line("M15 5l-7 7 7 7"),
    "player_ic_volume": line("M4 9h3l4-4v14l-4-4H4z", "M15 9a4.5 4.5 0 010 6"),
    "player_ic_brightness": line(
        "M12 8a4 4 0 100 8 4 4 0 000-8z",
        "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6L7 7M17 17l1.4 1.4M18.4 5.6L17 7M7 17l-1.4 1.4",
    ),
    "player_ic_expand": line("M4 9V4h5M20 15v5h-5M20 9V4h-5M4 15v5h5"),
    "player_ic_rotate": line("M4 12a8 8 0 1114 5", "M18 17l-4-1 1-4"),
    "player_ic_menu_audio": line("M9 18V6l10-2v12", "M6 21a3 3 0 100-6 3 3 0 000 6z", "M16 19a3 3 0 100-6 3 3 0 000 6z"),
    "player_ic_menu_episodes": line("M4 7h16M4 12h16M4 17h10"),
    "player_ic_menu_refresh": line("M20 12a8 8 0 11-3-6.2", "M20 4v4h-4"),
    "player_ic_menu_subtitle": line("M3 6h18v12H3z", "M7 14h4M13 14h4"),
    "player_ic_menu_video": line("M3 7h13v10H3z", "M16 11l5-3v8l-5-3"),
    "player_ic_params": line(
        "M12 9a3 3 0 100 6 3 3 0 000-6z",
        "M4 12h5M15 12h5M12 4v5M12 15v5",
    ),
    "player_ic_params_player": line("M8 5l11 7-11 7z", "M3 5v14"),
    "player_ic_params_decode": line("M4 6h16v12H4z", "M8 10h3v4H8z", "M14 10h3v4h-3z"),
    "player_ic_params_picture": line("M3 5h18v14H3z", "M3 15l5-5 4 4 3-3 6 6"),
    "player_ic_params_playback": line("M4 12h4l3-7 4 14 3-7h2"),
    "player_ic_params_scale": line("M4 9V4h5M20 15v5h-5M4 4l6 6M20 20l-6-6"),
    "player_ic_params_speed": line("M12 14l4-4", "M12 3a9 9 0 100 18 9 9 0 000-18z"),
    "player_ic_params_time": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M12 7v5l3 2"),
    "player_ic_params_time_start": line("M6 5v14", "M18 5l-9 7 9 7z"),
    "player_ic_params_time_end": line("M18 5v14", "M6 5l9 7-9 7z"),
    "player_ic_params_compare": line("M12 4v16", "M7 8L3 12l4 4", "M17 8l4 4-4 4"),
    "player_ic_params_preset": line("M4 7h16M4 12h16M4 17h16", "M9 5v4M15 10v4M9 15v4"),
    "player_ic_params_reset": line("M4 12a8 8 0 1114 5", "M4 8v4h4"),
    # 状态与杂项
    "icon_lock": line("M6 11h12v9H6z", "M9 11V8a3 3 0 016 0v3"),
    "icon_unlock": line("M6 11h12v9H6z", "M9 11V8a3 3 0 015.7-1.3"),
    "ic_battery_1": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h2v4H5z"),
    "ic_battery_2": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h4v4H5z"),
    "ic_battery_3": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h6v4H5z"),
    "ic_battery_4": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h8v4H5z"),
    "ic_battery_5": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h10v4H5z"),
    "ic_battery_6": line("M3 8h16v8H3z", "M21 11v2") + solid("M5 10h12v4H5z"),
    "ic_battery_charging": line("M3 8h16v8H3z", "M21 11v2", "M11 9l-2 4h3l-1 4 3-5h-3z"),
    "ic_detail_line": line("M4 7h16M4 12h16M4 17h16", "M7 5v4M12 10v4M9 15v4"),
    "ic_detail_quality": line("M12 3l2.7 5.6 6.3.9-4.6 4.4 1.1 6.1L12 17.2 6.5 20l1.1-6.1L3 9.5l6.3-.9z"),
    "ic_detail_recommend": line("M4 6h9M4 12h9M4 18h6", "M17 8l2 2 4-4"),
    "ic_layout_horizontal": line("M3 5h18v5H3z", "M3 14h18v5H3z"),
    "ic_layout_vertical": line("M3 5h8v14H3z", "M13 5h8v14h-8z"),
    "ic_music_page": line("M9 18V6l10-2v12", "M6 21a3 3 0 100-6 3 3 0 000 6z", "M16 19a3 3 0 100-6 3 3 0 000 6z"),
    "ic_music_queue": line("M4 7h12M4 12h12M4 17h8", "M18 10v7", "M15 19a3 3 0 100-6 3 3 0 000 6z"),
    "ic_notification_music": line("M9 18V6l10-2v12", "M6 21a3 3 0 100-6 3 3 0 000 6z", "M16 19a3 3 0 100-6 3 3 0 000 6z"),
    "ic_order_play": line("M4 7h10M4 12h10M4 17h6", "M18 8v9M15 14l3 3 3-3"),
    "ic_play_aac": line("M4 12a8 8 0 0114 5", "M18 17l-4-1 1-4", "M9 9v6M12 11v2"),
    "ic_play_anime4k": line("M3 5h18v14H3z", "M8 9l4 3-4 3z", "M16 9v6"),
    "ic_play_cache": line("M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z", "M4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7"),
    "ic_play_prewarm": line("M12 3c1 3-2 4-2 7a4 4 0 008 0c0-1.5-1-3-1-3s3 2 3 6a8 8 0 11-16 0c0-5 6-7 8-10z"),
    "ic_play_scale": line("M4 9V4h5M20 15v5h-5M4 4l6 6M20 20l-6-6"),
    "ic_play_tunnel": line("M4 12a8 8 0 0116 0", "M7 12a5 5 0 0110 0", "M10 12a2 2 0 014 0"),
    "ic_pref_collect_columns": line("M4 5h6v6H4zM14 5h6v6h-6zM4 15h6v4H4zM14 15h6v4h-6z"),
    "ic_pref_gesture": line("M9 11V6a2 2 0 014 0v5", "M13 11V9a2 2 0 014 0v6a6 6 0 01-6 6h-1a5 5 0 01-5-5v-3a2 2 0 014 0"),
    "ic_pref_history_merge": line("M4 8h10M4 16h10", "M17 5v14", "M14 12h6"),
    "ic_pref_incognito": line("M4 10h16", "M7 10a5 5 0 0110 0", "M8 14a2 2 0 100 4 2 2 0 000-4z", "M16 14a2 2 0 100 4 2 2 0 000-4z"),
    "ic_pref_language": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M3 12h18", "M12 3c3 3.5 3 14 0 18", "M12 3c-3 3.5-3 14 0 18"),
    "ic_preload_next": line("M6 5l9 7-9 7z", "M18 5v14"),
    "ic_repeat": line("M4 9h13a3 3 0 013 3", "M17 6l3 3-3 3", "M20 15H7a3 3 0 01-3-3", "M7 18l-3-3 3-3"),
    "ic_repeat_one": line("M4 9h13a3 3 0 013 3", "M17 6l3 3-3 3", "M20 15H7a3 3 0 01-3-3", "M7 18l-3-3 3-3", "M12 9v6"),
    "ic_settings_about": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M12 11v6M12 8v.5"),
    "ic_subscribe_add": line("M4 7h9M4 12h9M4 17h6", "M18 12v6M15 15h6"),
    "ic_subscribe_source": line("M4 5h16v14H4z", "M4 9h16", "M8 13h8"),
    "ic_switch_repo": line("M4 8h13", "M14 5l3 3-3 3", "M20 16H7", "M10 13l-3 3 3 3"),
    # ── 第二批:剩余旧图(名字与预期不同的也在这里补齐) ──
    "ic_player_expand": line("M4 9V4h5M20 15v5h-5M20 9V4h-5M4 15v5h5"),
    "ic_player_rotate": line("M4 12a8 8 0 1114 5", "M18 17l-4-1 1-4"),
    "ic_play_decode": line("M4 6h16v12H4z", "M8 10h3v4H8z", "M14 10h3v4h-3z"),
    "ic_play_kernel": line("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5M12 12v9M12 12L4 7.5"),
    "ic_play_render": line("M3 5h18v14H3z", "M3 15l5-5 4 4 3-3 6 6"),
    "ic_pref_auto_switch_line": line("M4 8h13", "M14 5l3 3-3 3", "M20 16H7", "M10 13l-3 3 3 3"),
    "ic_pref_buffer_time": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M12 7v5l3 2"),
    "ic_pref_danmu": line("M3 6h18v10H3z", "M6 10h4M12 10h3M6 13h6"),
    "ic_pref_danmu_api": line("M3 6h18v10H3z", "M6 10h4M12 10h3M6 13h6", "M17 16v3M15 18h4"),
    "ic_pref_long_press_speed": line("M12 14l4-4", "M12 3a9 9 0 100 18 9 9 0 000-18z"),
    "ic_pref_nav_live_hidden": line("M3 5h18v14H3z", "M8 12a2 2 0 104 0 2 2 0 00-4 0z", "M3 3l18 18"),
    "ic_pref_search_threads": line("M11 4a7 7 0 100 14 7 7 0 000-14z", "M20 20l-3.6-3.6"),
    "ic_preload_duration": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M12 7v5l3 2", "M18 5l2-2"),
    "ic_settings_doh": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M3 12h18", "M12 3c3 3.5 3 14 0 18"),
    "ic_settings_history": line("M12 3a9 9 0 100 18 9 9 0 000-18z", "M12 7v5l3 2"),
    "player_ic_menu_danmu": line("M3 6h18v10H3z", "M6 10h4M12 10h3M6 13h6"),
}


def main():
    n = 0
    for name, body in ICONS.items():
        path = os.path.join(ROOT, name + ".xml")
        if not os.path.exists(path):
            print("skip(missing):", name)
            continue
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(HEAD + body + TAIL)
        n += 1
    print("redrew %d icons" % n)


if __name__ == "__main__":
    main()
