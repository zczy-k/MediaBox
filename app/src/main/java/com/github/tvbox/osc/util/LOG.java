package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    private static final boolean FILE_LOG = BuildConfig.DEBUG;
    /**
     * 落盘白名单:只有前缀命中的日志才写文件。
     *
     * <p>存在的意义是**控制体积** —— 播放日志是每几百毫秒一条,全落盘一天能写几个 GB。
     * 但代价是"加了新日志却发现文件里没有",很容易误判成"代码没执行",故新增条目时必须同步加前缀。
     *
     * <p>🔴 **踩过两次的坑,新增诊断日志前务必先读这段**:忘记加前缀 → 日志被过滤 →
     * 文件里查不到 → 误判"代码没执行" → 白等一轮构建再去猜别的方向。
     * 第一次是 {@code echo-line-heights}(以为代码没跑,其实被过滤了整整两轮),
     * 第二次是更早的画质日志。
     *
     * <p>**自检口径**:新加的诊断日志如果真机里查不到,第一件事是核对前缀表,
     * 而不是怀疑代码没执行 —— 后者要重装+重跑一轮,成本高得多。
     *
     * <p>诊断包(diag)排查画质/换源/缓存时依赖以下前缀:
     * <ul>
     *   <li>{@code echo-quality} —— 画质优选决策(选哪条线、探测有没有成功、写入用哪个键)</li>
     *   <li>{@code echo-line-probe} —— 线路探测的目标筛选(哪些线路值得探)</li>
     *   <li>{@code echo-line-heights} —— 线路画质读取侧(记忆里到底读到了什么)</li>
     *   <li>{@code echo-unavailable} —— 无资源标记的写入与粗筛结果(诊断"海报为什么不见了")</li>
     *   <li>{@code echo-detail} —— 详情加载与看门狗(卡在哪一步)</li>
     *   <li>{@code SubtitleCacheJanitor} —— 字幕缓存 LRU 裁剪结果</li>
     *   <li>{@code echo-source} / {@code source-prewarm} —— 聚合搜索预热与候选数</li>
     * </ul>
     */
    private static final String[] FILE_LOG_PREFIXES = FILE_LOG
            ? new String[]{"echo-preload", "echo-setDataSource", "echo-play-cache", "echo-kv", "echo-progress", "echo-exo", "echo-music", "echo-lyric", "echo-sub", "echo-danmu", "echo-p2", "echo-p3", "echo-p4", "echo-p5", "clearCache", "echo--jar", "echo-local-src", "echo-setTrack", "echo-autoRetry", "echo-player", "echo-switch", "echo-goPlayUrl", "echo-history", "echo-render", "echo-picture", "echo-anime4k", "echo--list", "echo--getList", "echo--parse", "echo--getSort", "echo--sort", "echo-proxy",
            // ↓ 诊断包(v1.0.24 起):画质优选 / 详情看门狗 / 换源预热 / 缓存裁剪
            "echo-quality", "echo-detail", "echo-source", "source-prewarm", "SubtitleCacheJanitor", "echo-cacheTrim",
            // ↓ v1.0.26:线路实测画质探测(诊断「线路N」后面一直不显示分辨率)
            // ↓ v1.0.30:echo-line-heights 是**读取侧**,与写入侧的 echo-quality write-key 配对,
            //   两侧同看才能判断"记忆写进去了却读不回来"这类键不匹配问题。
            "echo-line-probe", "echo-line-heights", "echo-unavailable",
            // ↓ v1.0.41:**去广告链路此前完全不可见**。
            //   M3u8.purify 打的 "echo-fixAdM3u8 ..." 与 M3u8PurifyUseCase 打的
            //   "echo-m3u8..." 都没登记,于是被 fileLog 静默丢弃 ——
            //   真机排查「去广告弹窗还在不在」时,查到的记录数是 **0**,
            //   无法区分"没命中广告"与"日志被过滤"。这两个前缀是补这个盲区的。
            "echo-fixAdM3u8", "echo-m3u8", "echo-playM3u8"}
            // ⚠️ FILE_LOG=false(正式包)时折成空数组,让这批前缀**字面量也从 dex 里消失**。
            //
            // 背景:release 靠 proguard 的 -assumenosideeffects 已经把**调用点**全删了
            // (连参数里的字符串拼接一起消失),但本数组是 <clinit> 里的静态初始化,
            // R8 未判定为纯死代码 ⇒ 这批前缀串会**作为不可达的死数据留在 dex 里**。
            // 2026-09-19 用 dexdump 复验过这个残留(见 proguard-rules.pro 同处注释)。
            //
            // 之所以能这么折:FILE_LOG 就是 BuildConfig.DEBUG,是编译期常量,
            // R8 会把三元表达式常量折叠掉,false 分支的数组构造连同字面量一起成为死代码。
            // 语义上也无变化 —— FILE_LOG=false 时 fileLog() 第一行就 return,
            // 本数组根本不会被读。
            : new String[0];
    private static final String FILE_LOG_NAME = "preload_debug.log";
    private static ExecutorService fileLogExecutor;

    private static void fileLog(String level, String msg) {
        if (!FILE_LOG || msg == null) return;
        boolean match = false;
        for (String prefix : FILE_LOG_PREFIXES) {
            if (msg.startsWith(prefix)) {
                match = true;
                break;
            }
        }
        if (!match) return;
        final String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + level + " " + msg;
        synchronized (LOG.class) {
            if (fileLogExecutor == null) fileLogExecutor = Executors.newSingleThreadExecutor();
        }
        try {
            fileLogExecutor.execute(() -> {
                // 每行独立开关文件:保证进程被杀时已写入的内容不丢(排查场景量小,开销可接受)
                try (FileWriter writer = new FileWriter(new File(AppContextHolder.context().getFilesDir(), FILE_LOG_NAME), true)) {
                    writer.write(line + "\n");
                } catch (Throwable ignored) {
                    // 落盘失败即放弃:fileLog 自身兜底,不能再走 LOG 以免递归
                }
            });
        } catch (Throwable ignored) {
            // 同上:提交落盘任务失败即放弃,防递归
        }
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
        fileLog("E", String.valueOf(msg));
    }

    public static void i(String msg) {
        Log.i(TAG, "" + msg);
        fileLog("I", String.valueOf(msg));
    }

    /** 带模块标签的 debug 日志:"有意忽略"的 catch 用它记录忽略原因 */
    public static void d(String tag, String msg) {
        Log.d(TAG, tag + ": " + msg);
        fileLog("D", String.valueOf(msg));
    }

    /**
     * 带模块标签的错误日志(含异常栈):异常无法恢复但不应静默时使用。
     * tr 同步落 System.err —— 本机 vivo ROM 吞 Logcat 时仍可见(见 MEMORY 排查记录)。
     */
    public static void e(String tag, String msg, Throwable tr) {
        Log.e(TAG, tag + ": " + msg, tr);
        fileLog("E", String.valueOf(msg));
        if (tr != null) tr.printStackTrace();
    }

    public static void e(String tag, Throwable tr) {
        e(tag, tr == null ? "null" : tr.toString(), tr);
    }

    public static void longI(String prefix, String msg) {
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
