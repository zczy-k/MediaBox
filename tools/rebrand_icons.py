#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MediaBox 图标统一替换器 (第二轮 · 修正版)

为什么是「单色 fill」而不是描边:
  设置行图标走 SettingsIconBadge(SettingsGroup.kt:151-166), 内部
  Icon(tint = MaterialTheme.colorScheme.onPrimaryContainer) —— 颜色由主题接管,
  drawable 里的 fillColor 会被 tint 覆盖。描边(stroke)路径 tint 不生效, 深色主题下会隐形。
  所以全部图标统一: viewport 960x960 / 单 path / fillColor 可被 tint 覆盖。

设计语言 (MediaBox):
  - 圆角方框 + 居中播放三角 = 品牌符号
  - 统一 960 网格, 视觉重量居中(约 160~800)
  - 全部实心单 path, 无 group/translate hack

用法:  python tools/rebrand_icons.py [--dry]
"""
import os
import sys
import glob

RES = os.path.join("app", "src", "main", "res")
FILL = "@android:color/white"          # 被 tint 覆盖;无 tint 处也是安全色(同原 ic_tab_home)
DRY = "--dry" in sys.argv

TPL = '''<?xml version="1.0" encoding="utf-8"?>
<!-- MediaBox 图标 · {name} (tools/rebrand_icons.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <path
        android:fillColor="{fill}"
        android:pathData="{d}" />
</vector>
'''

# ---------------------------------------------------------------- shape 库
# 坐标系 960x960, 视觉中心 (480,480), 安全区 160~800
S = {
    # —— 品牌 / 播放 ——
    "play":       "M400,240L720,480L400,720Z",
    "play_box":   "M240,160h480q80,0 80,80v480q0,80 -80,80H240q-80,0 -80,-80V240q0,-80 80,-80Zm160,160v280l240,-140Z",
    "pause":      "M360,200h100v560H360ZM500,200h100v560H500Z",
    "stop":       "M280,280h400v400H280Z",
    "record":     "M480,480m-260,0a260,260 0 1,0 520,0a260,260 0 1,0 -520,0",
    "next":       "M280,240L560,480L280,720ZM620,200h80v560h-80Z",
    "prev":       "M680,240L400,480L680,720ZM260,200h80v560h-80Z",
    "skip":       "M240,240L520,480L240,720ZM560,240L840,480L560,720Z",
    "repeat":     "M200,360q0,-80 80,-80h400v-80l200,160 -200,160v-80H320q-40,0 -40,40v80ZM760,600q0,80 -80,80H280v80L80,600l200,-160v80h360q40,0 40,-40v-80Z",
    "repeat_one": "M200,360q0,-80 80,-80h400v-80l200,160 -200,160v-80H320q-40,0 -40,40v80ZM420,440h80v240h-80ZM340,520h240v-80H340Z",
    "order":      "M480,180l260,460H220ZM420,400h120v280H420Z",
    "fast":       "M480,200L300,720h80L400,520h160l20,200h80ZM420,420h120L480,260Z",

    # —— 媒体 / 音轨 ——
    "video":      "M120,260q0,-60 60,-60h420q60,0 60,60v440q0,60 -60,60H180q-60,0 -60,-60ZM660,420l180,-110v340L660,540Z",
    "audio":      "M400,720q-60,0 -100,-40t-40,-100q0,-60 40,-100t100,-40q30,0 55,11v-341l260,-60v350q0,60 -40,100t-100,40q-30,0 -55,-11v101q0,60 -40,100t-100,40Z",
    "audio_track":"M400,640q-50,0 -85,-35t-35,-85q0,-50 35,-85t85,-35q25,0 46,9v-269l220,-50v295q0,50 -35,85t-85,35q-25,0 -46,-9v89q0,50 -35,85t-85,35ZM620,120h60v240h-60ZM700,180h60v180h-60Z",
    "cast":       "M120,240q0,-60 60,-60h600q60,0 60,60v480q0,60 -60,60H600v-90h180V270H210v90H120ZM120,600h90q0,110 -90,110ZM120,450h90q0,160 -160,160ZM120,690v-90q240,0 240,240v90Z",
    "sub":        "M140,220h680q50,0 50,50v420q0,50 -50,50H140q-50,0 -50,-50V270q0,-50 50,-50Zm130,180h240v70H270Zm0,150h420v70H270Z",

    # —— 导航 ——
    "home":       "M480,140L120,440v380q0,40 40,40h200V620h240v240h200q40,0 40,-40V440ZM420,620v180H320V520q0,-40 40,-40h240q40,0 40,40v280H420Z",
    "back":       "M360,240L120,480l240,240v-160h480v-160H360Z",
    "forward":    "M600,240l240,240 -240,240V560H120V400h480Z",
    "up":         "M480,140L240,400h160v420h160V400h160Z",
    "down":       "M480,820L720,560H560V140H400v420H240Z",
    "menu":       "M160,320h640v90H160ZM160,435h640v90H160ZM160,550h640v90H160Z",
    "more":       "M480,480m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0M220,480m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0M740,480m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0",
    "more_vert":  "M480,220m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0M480,480m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0M480,740m-70,0a70,70 0 1,0 140,0a70,70 0 1,0 -140,0",
    "settings":   "M480,480m-110,0a110,110 0 1,0 220,0a110,110 0 1,0 -220,0M480,180q-40,90 -110,120 -70,-30 -110,-120l-80,50q40,80 30,160 -80,10 -160,-10l-30,90q90,50 160,30 0,90 30,160l90,30q30,-80 90,-110 80,20 140,-30l-30,-90q-80,40 -160,30 10,-80 -10,-160ZM380,120l200,0 20,110q40,20 60,40l110,-40 60,100 -90,70q0,30 0,60l90,70 -60,100 -110,-40q-30,30 -60,50l-20,110 -200,0 -20,-110q-40,-20 -60,-50l-110,40 -60,-100 90,-70q0,-30 0,-60l-90,-70 60,-100 110,40q30,-30 60,-50Z",

    # —— 内容 / 数据 ——
    "search":     "M480,480m-260,0a260,260 0 1,0 520,0a260,260 0 1,0 -520,0M660,660l200,200 -140,140 -200,-200Z",
    "hot":        "M480,120q60,120 0,220t0,200q0,-80 60,-140t60,-140q120,60 120,220 0,120 -90,200t-90,160 -90,-160 -90,-160 0,-80 90,-200t90,-200Z",
    "star":       "M480,140l96,240 260,20 -200,170 60,250 -216,-130 -216,130 60,-250 -200,-170 260,-20Z",
    "star_filled":"M480,140l96,240 260,20 -200,170 60,250 -216,-130 -216,130 60,-250 -200,-170 260,-20Z",
    "quality":    "M480,140l96,240 260,20 -200,170 60,250 -216,-130 -216,130 60,-250 -200,-170 260,-20ZM420,600h120v120H420Z",
    "favorite":   "M480,840q-30,-20 -230,-180T120,480q0,-140 100,-220t260,-80q160,0 260,80t100,220q0,100 -130,180T480,840Z",
    "history":    "M480,480m-300,0a300,300 0 1,0 600,0a300,300 0 1,0 -600,0M420,300h120v240H420ZM480,420l160,90 -20,40 -140,-80Z",
    "folder":     "M100,220h280l80,100h400q40,0 40,40v380q0,40 -40,40H100q-40,0 -40,-40V260q0,-40 40,-40Z",
    "file":       "M220,120h340l180,180v540H220ZM560,180v160h160ZM320,500h320v70H320ZM320,620h320v70H320Z",
    "upload":     "M480,120L280,340h140v280h120V340h140ZM180,700h600v100H180Z",
    "download":   "M180,180h600v100H180ZM480,720l200,-220H540V340H420v160H320Z",
    "refresh":    "M480,180q-160,0 -280,110 -110,110 -110,270 0,170 120,285 110,105 270,105 170,0 285,-115 105,-105 105,-275h-90q0,120 -80,205 -80,85 -220,85 -130,0 -215,-85 -80,-85 -80,-215 0,-125 85,-210 80,-85 215,-85 100,0 175,50l-110,110h340V160l-80,80q-90,-60 -215,-60Z",
    "link":       "M420,540h120v120H420ZM380,420h200q100,0 170,70 60,60 60,160v60h-90v-60q0,-60 -40,-100 -50,-50 -100,-50H380ZM580,420v-90h90v90ZM290,540v-60q0,-60 40,-100 50,-50 100,-50h100v90h-100q-30,0 -50,20 -30,20 -30,50v60h-90Z",
    "filter":     "M120,200h720L520,540v280l-160,-80V540Z",
    "sort":       "M300,140h120v680H300ZM540,220l240,240H540ZM540,620h240L540,860Z",
    "grid":       "M140,140h280v280H140ZM540,140h280v280H540ZM140,540h280v280H140ZM540,540h280v280H540Z",
    "list":       "M160,240h120v120H160ZM360,270h440v60H360ZM160,420h120v120H160ZM360,450h440v60H360ZM160,600h120v120H160ZM360,630h440v60H360Z",
    "layout_h":   "M120,200h720v180H120ZM120,440h720v180H120ZM120,680h720v120H120Z",
    "layout_v":   "M160,140h240v680H160ZM460,140h340v680H460Z",
    "user":       "M480,480m-160,0a160,160 0 1,0 320,0a160,160 0 1,0 -320,0M160,860q0,-200 160,-280 80,-40 160,-40 80,0 160,40 160,80 160,280Z",
    "add":        "M420,220h120v200h200v120H540v200H420V540H220V420H420Z",
    "clock":      "M480,480m-300,0a300,300 0 1,0 600,0a300,300 0 1,0 -600,0M420,280h120v230l160,90 -50,90 -230,-130Z",

    # —— 系统 / 状态 ——
    "check":      "M820,410L430,800 150,520l80,-80 200,200 310,-310Z",
    "close":      "M760,680L680,760 480,560 280,760 200,680 400,480 200,280 280,200 480,400 680,200 760,280 560,480Z",
    "delete":     "M280,260h400v-60q0,-40 -40,-40H320q-40,0 -40,40ZM180,320h600v80H180ZM260,460h90v340h-90ZM610,460h90v340h-90ZM380,180h200v80H380Z",
    "edit":       "M660,120l180,180 -420,420 -240,60 60,-240ZM600,240l120,120",
    "info":       "M480,480m-320,0a320,320 0 1,0 640,0a320,320 0 1,0 -640,0M420,380h120v300H420ZM420,260h120v120H420Z",
    "warning":    "M480,140l400,700H80ZM420,380h120v220H420ZM420,660h120v120H420Z",
    "lock":       "M480,480m-320,0a320,320 0 1,0 640,0a320,320 0 1,0 -640,0M420,400h120v160H420Z",
    "eye":        "M480,480m-320,0a320,320 0 1,0 640,0a320,320 0 1,0 -640,0M480,480m-110,0a110,110 0 1,0 220,0a110,110 0 1,0 -220,0",
    "theme":      "M480,480m-320,0a320,320 0 1,0 640,0a320,320 0 1,0 -640,0M480,160a320,320 0 0,1 0,640Z",
    "moon":       "M740,600q-120,60 -230,-20T340,330q20,-130 140,-210 200,-130 350,10 160,150 100,320 -90,150Z",
    "sun":        "M480,480m-180,0a180,180 0 1,0 360,0a180,180 0 1,0 -360,0M430,60h100v140H430ZM430,760h100v140H430ZM60,430h140v100H60ZM760,430h140v100H760ZM170,270l70,70 -100,100 -70,-70ZM820,270l-70,70 100,100 70,-70ZM170,690l-70,-70 100,-100 70,70ZM820,690l-70,-70 -100,100 70,70Z",
    "palette":    "M480,140q-290,0 -290,290 0,110 90,175t210,65q60,0 60,45t-60,45q-50,0 -50,60 0,90 -100,90 -300,0 -300,-320 0,-450 440,-450ZM240,420a60,60 0 1,0 120,0a60,60 0 1,0 -120,0ZM340,240a60,60 0 1,0 120,0a60,60 0 1,0 -120,0ZM620,240a60,60 0 1,0 120,0a60,60 0 1,0 -120,0Z",
    "glass":      "M480,120q-320,0 -320,300v300q0,140 140,140h360q140,0 140,-140V420q0,-300 -320,-300ZM480,220q220,0 220,220v220q0,60 -60,60H320q-60,0 -60,-60V440q0,-220 220,-220Z",
    "tune":       "M380,140h200v140H380ZM240,440h480v120H240ZM620,700h-200V560h200ZM140,280h120v140H140ZM700,560h120v140H700ZM280,560h120v140H280ZM560,280h120v140H560Z",
    "battery":    "M140,320h560q40,0 40,40v240q0,40 -40,40H140q-40,0 -40,-40V360q0,-40 40,-40Zm690,80v160h50V400Z",
    "battery_1":  "M140,320h560q40,0 40,40v240q0,40 -40,40H140q-40,0 -40,-40V360q0,-40 40,-40Zm690,80v160h50V400ZM180,540h80v60h-80Z",
    "battery_2":  "M140,320h560q40,0 40,40v240q0,40 -40,40H140q-40,0 -40,-40V360q0,-40 40,-40Zm690,80v160h50V400ZM180,480h80v120h-80Z",
    "battery_3":  "M140,320h560q40,0 40,40v240q0,40 -40,40H140q-40,0 -40,-40V360q0,-40 40,-40Zm690,80v160h50V400ZM180,420h80v180h-80Z",
    "battery_4":  "M140,320h560q40,0 40,40v240q0,40 -40,40H140q-40,0 -40,-40V360q0,-40 40,-40Zm690,80v160h50V400ZM180,360h80v240h-80Z",
    "battery_5":  "M100,300h620q40,0 40,40v280q0,40 -40,40H100q-40,0 -40,-40V340q0,-40 40,-40Zm720,110v140h60V410ZM120,380h580v200H120Z",
    "charge":     "M100,300h620q40,0 40,40v280q0,40 -40,40H100q-40,0 -40,-40V340q0,-40 40,-40Zm720,110v140h60V410ZM540,340L360,540h120l-60,140 180,-200H480Z",
    "brightness": "M480,480m-180,0a180,180 0 1,0 360,0a180,180 0 1,0 -360,0M420,40h120v130H420ZM420,790h120v130H420ZM40,420h130v120H40ZM790,420h130v120H790Z",
    "rotate":     "M480,180q-160,0 -270,110T100,560h90q0,-120 85,-205 80,-85 205,-85 130,0 215,85 80,85 80,205 0,120 -85,205 -80,85 -205,85v90q160,0 270,-110t110,-270h-90q0,-120 -85,-205 -80,-85 -205,-85 -130,0 -215,85 -80,85 -80,205 0,120 85,205 80,85 205,85Z",
    "expand":     "M120,120h300v90H210v210h-90ZM540,120h300v300h-90V210H540ZM120,540h90v210h210v90H120ZM750,540h90v300H540v-90h210Z",
    "pip":        "M120,180h720v600H120ZM420,480v180h300V480Z",
    "api":        "M340,120h280v200h220v320H620v200H340V640H120V320h220ZM380,180v600h200v-60h160V380H580v-60H380Z",
    "kernel":     "M480,480m-140,0a140,140 0 1,0 280,0a140,140 0 1,0 -280,0M440,40h80v160h-80ZM440,760h80v160h-80ZM40,440h160v80H40ZM760,440h160v80H760ZM160,160l60,60 -110,110 -60,-60ZM850,850l-60,-60 110,-110 60,60ZM800,160l-60,60 -110,-110 60,-60ZM110,850l60,-60 110,110 -60,60Z",
    "render":     "M120,220h720v520H120ZM200,660l160,-200 120,140 100,-110 180,170ZM260,380a50,50 0 1,0 100,0a50,50 0 1,0 -100,0Z",
    "scale":      "M120,440h720v80H120ZM440,120h80v720h-80ZM240,520l-90,90 90,90Z",
    "tunnel":     "M480,480m-300,0a300,300 0 1,0 600,0a300,300 0 1,0 -600,0M480,240q-140,0 -240,100 100,-200 240,-200 140,0 240,200 -100,-100 -240,-100Zm0,80q110,0 190,70 -90,150 -190,150 -100,0 -190,-150 80,-70 190,-70Z",
    "cache":      "M120,220h720v520H120ZM200,300h560v80H200ZM200,440h300v80H200ZM200,580h300v80H200Z",
    "live":       "M480,480m-140,0a140,140 0 1,0 280,0a140,140 0 1,0 -280,0M480,480m-260,0a260,260 0 1,0 520,0a260,260 0 1,0 -520,0M480,480m-360,0a360,360 0 1,0 720,0a360,360 0 1,0 -720,0",
    "incognito":   "M320,240h320q-60,90 -60,180t60,180H320q-70,0 -120,-50t-50,-130 0,-120 50,-180 50,-60 120,-60ZM640,240q60,90 60,180t-60,180h-50v-360ZM440,300h80v360h-80Z",
    "danmu":      "M120,220h720v400H600l-160,140 -60,-140H120ZM240,360h200v60H240ZM520,360h200v60H520Z",
    "github":     "M480,140q-260,0 -440,180t-180,440q0,200 140,340t340,140q200,0 340,-140t140,-340q0,-260 -180,-440T480,140ZM400,340v380q-140,-20 -230,-110 -50,-50 -70,-130h80v-40h-80q20,-80 70,-130 90,-90 230,-110v380ZM680,570q-40,80 -150,130v-70h-60v-80h60v-70h-90v40h-90v70h-40v80h40v60q-110,-50 -150,-130 40,-120 140,-170 -20,-20 -40,-50 0,0 30,10 30,10 20,-20 60,-20 20,20 40,30 40,60 0,60 -20,80 100,50 140,170Z",
}

# ------------------------------------------------------- 文件名 -> shape 映射
MAP = {
    "arrow_left": "back",
    "battery_1": "battery_1", "battery_2": "battery_2", "battery_3": "battery_3",
    "battery_4": "battery_4", "battery_5": "battery_5", "battery_6": "battery",
    "battery_charging": "charge", "brightness_auto": "brightness",
    "cache_size": "cache", "check": "check",
    "dark_mode": "moon", "light_mode": "sun",
    "delete": "delete", "edit": "edit", "filter": "filter",
    "detail_cast": "cast", "detail_episodes": "grid", "detail_line": "list",
    "detail_music_player": "audio", "detail_quality": "quality",
    "detail_recommend": "star", "detail_switch_source": "refresh",
    "empty_record": "record", "file_choose": "folder",
    "episode_grid_all": "grid", "episode_order_asc": "order", "episode_reverse": "prev",
    "hot_search": "hot", "layout_horizontal": "layout_h", "layout_vertical": "layout_v",
    "live_fab": "live", "more_vert": "more_vert",
    "music_page": "audio", "music_queue": "list", "notification_music": "audio",
    "order_play": "order", "play_aac": "audio_track", "play_anime4k": "kernel",
    "play_cache": "cache", "play_decode": "kernel", "play_kernel": "kernel",
    "play_prewarm": "fast", "play_render": "render", "play_scale": "scale",
    "play_tunnel": "tunnel", "player_expand": "expand", "player_rotate": "rotate",
    "pref_auto_switch_line": "tune", "pref_buffer_time": "clock",
    "pref_collect_columns": "grid", "pref_danmu": "danmu",
    "pref_danmu_api": "api", "pref_gesture": "expand",
    "pref_history_merge": "history", "pref_incognito": "incognito",
    "pref_language": "globe", "pref_long_press_speed": "fast",
    "pref_m3u8_purify": "tune", "pref_nav_animation": "palette",
    "pref_nav_live_hidden": "eye", "pref_search_threads": "list",
    "preload_duration": "clock", "preload_next": "next",
    "repeat": "repeat", "repeat_one": "repeat_one", "search_history": "history",
    "settings_about": "info", "settings_api": "api", "settings_doh": "tunnel",
    "settings_github": "github", "settings_history": "history",
    "settings_play": "play_box", "settings_preference": "tune",
    "settings_start": "home", "settings_theme": "palette",
    "subscribe_add": "add", "subscribe_source": "api", "switch_repo": "refresh",
    "tab_collect": "favorite", "tab_collect_filled": "favorite",
    "tab_history": "history", "tab_home": "home", "tab_settings": "settings",
    "theme_custom": "palette", "theme_liquid_glass": "glass",
}

# 没在 MAP 里但能在库里找到的兜底关键字(按顺序首次命中)
FALLBACK = [
    ("github", "github"), ("git", "github"),
    ("danmu", "danmu"), ("cache", "cache"), ("speed", "fast"),
    ("prewarm", "fast"), ("fast", "fast"), ("buffer", "clock"),
    ("duration", "clock"), ("history", "history"), ("recent", "history"),
    ("language", "globe"), ("incognito", "incognito"),
    ("gesture", "expand"), ("expand", "expand"), ("fullscreen", "expand"),
    ("rotate", "rotate"), ("scale", "scale"), ("render", "render"),
    ("tunnel", "tunnel"), ("doh", "tunnel"), ("vpn", "tunnel"),
    ("decode", "kernel"), ("kernel", "kernel"), ("anime", "kernel"),
    ("aac", "audio_track"), ("audio", "audio"), ("music", "audio"),
    ("sound", "audio"), ("video", "video"), ("cast", "cast"),
    ("quality", "quality"), ("episode", "grid"), ("grid", "grid"),
    ("column", "layout_v"), ("layout", "layout_h"),
    ("sub", "sub"), ("track", "audio_track"),
    ("search", "search"), ("hot", "hot"), ("sort", "order"), ("filter", "filter"),
    ("file", "file"), ("folder", "folder"), ("download", "download"),
    ("upload", "upload"), ("refresh", "refresh"), ("switch", "refresh"),
    ("repo", "refresh"), ("reverse", "prev"),
    ("theme", "palette"), ("skin", "palette"), ("glass", "glass"),
    ("custom", "palette"), ("nav", "palette"), ("animation", "palette"),
    ("battery", "battery"), ("brightness", "brightness"),
    ("play", "play"), ("live", "live"), ("record", "record"),
    ("repeat", "repeat"), ("order", "order"), ("queue", "list"),
    ("list", "list"), ("more", "more_vert"), ("preference", "tune"),
    ("pref", "tune"), ("setting", "settings"), ("about", "info"),
    ("delete", "delete"), ("edit", "edit"), ("check", "check"),
    ("close", "close"), ("add", "add"), ("home", "home"), ("tab", "home"),
    ("subscribe", "api"), ("api", "api"), ("config", "api"),
    ("start", "play"), ("dark", "moon"), ("light", "sun"),
]

# 已知但库里缺的 -> 补上
S["globe"] = ("M480,480m-320,0a320,320 0 1,0 640,0a320,320 0 1,0 -640,0"
              "M480,160a320,320 0 0,1 0,640 320,60 0 0,0 0,-640"
              "M480,160a320,320 0 0,0 0,640 -320,-60 0 0,0 0,640"
              "M190,380h580M190,580h580")


def pick(name: str):
    if name in MAP:
        return MAP[name]
    for kw, shape in FALLBACK:
        if kw in name and shape in S:
            return shape
    return None


def main():
    files = sorted(glob.glob(os.path.join(RES, "drawable*", "ic_*.xml")))
    ok, miss = 0, []
    for f in files:
        base = os.path.basename(f)
        stem = base[3:-4]                      # 去掉 ic_ 前缀与 .xml
        if stem in ("launcher_bg", "launcher_foreground"):
            continue                            # 品牌图标, 手工设计, 不动
        shape = pick(stem)
        if shape is None:
            miss.append(stem)
            continue
        if not DRY:
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(TPL.format(name=stem, fill=FILL, d=S[shape]))
        ok += 1

    print(("DRY-RUN " if DRY else "") + "replaced = %d / %d" % (ok, len(files) - 2))
    if miss:
        print("\n未匹配 %d 个(往 MAP 里补一行即可):" % len(miss))
        for m in miss:
            print("   ", m)
    else:
        print("全部命中。")


if __name__ == "__main__":
    main()