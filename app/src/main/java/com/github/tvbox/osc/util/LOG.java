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
     *   <li>{@code echo-searchplan} —— 搜索批次规划与收尾统计(每批多少源/命中/失败/超时/最慢源耗时)</li>
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
            // ↓ 2026-10-08:防滥用封禁(源健康台账)。计分/封禁/自动解封/被过滤掉几个源全走这个前缀 ——
            //   没有它就只能看到"少了个源",分不清是"屏蔽生效"还是"源列表本来就少"。
            "echo-srcban",
            // ↓ 2026-10-08:**搜索批次观测**。此前"搜索慢"只能靠体感判断 —— 既没有每源耗时,
            //   也没有"这个源是没命中还是挂了"的区分(全叫 Done)。echo-searchplan 落盘后,
            //   首轮多快出结果、失败/超时各占多少、最慢源多久才有回包,都能在文件里直接读。
            "echo-searchplan",
            // ↓ v1.0.41:**去广告链路此前完全不可见**。
            //   M3u8.purify 打的 "echo-fixAdM3u8 ..." 与 M3u8PurifyUseCase 打的
            //   "echo-m3u8..." 都没登记,于是被 fileLog 静默丢弃 ——
            //   真机排查「去广告弹窗还在不在」时,查到的记录数是 **0**,
            //   无法区分"没命中广告"与"日志被过滤"。这两个前缀是补这个盲区的。
            "echo-fixAdM3u8", "echo-m3u8", "echo-playM3u8",
            // ↓ 2026-10-09:S2 按源记忆("详情回包缺 list"→ 按索引型跳过详情请求)。
            //   v1.0.11-diag 真机测试时踩了本类注释警告的坑:前缀没登记,文件里查不到
            //   echo-dnolist,一度误判"record 没执行";实际代码在跑(行为已验证),纯日志被过滤。
            "echo-dnolist",
            // ↓ 2026-10-09:**源完整度优先级**(权威集数/起播跳过/播放中纠偏)。
            //   ⚠️ 本日第三次踩同一个坑:echo-completeness 没登记,真机 67 次详情成功
            //   却 0 条日志,再次误判"record 没执行" —— 实际是纯日志被过滤。
            //   教训升格为铁律:**LOG.i 的新前缀必须同步登记本数组**,提交前 grep 复核。
            "echo-completeness",
            // ↓ 2026-10-15:**订阅桶**(老桶搬迁 + 弃用仓回收)。
            //   没有它就无法回答"换了个写法,为什么源权重像被清空了" ——
            //   搬迁有没有发生、回收删了几本账,只能靠这条日志区分。
            "echo-bucket",
            // ↓ 2026-10-15:**下载产物挪出 cache**(jar / 字幕落盘 / 导入副本 → files/durable)。
            //   搬迁有没有发生、有没有条目搬不动,直接决定"源装不装得回来、字幕决定还悬不悬空"。
            "echo-durable",
            // ↓ 2026-10-15:**首帧耗时**(把地址交给播放器 → 真起播)。
            //   换线宽限目前是按"还在不在进数据"判,预算写死;要知道高分辨率线路到底要等多久、
            //   预算该不该自适应,只能先有这个数 —— 没有它就只能按线路标签猜。
            "echo-firstframe",
            // ↓ 2026-10-12:**线路有效性判定**(线路健康的失败/成功/淘汰)。
            //   没有它就无法回答"这条线为什么被排后" —— 是连续失败、取流慢、还是误判。
            "echo-line-health",
            // ↓ 2026-10-11:**跨源画质提示与迷你只读探测(P1)**。
            //   前缀没登记就是静默丢弃 —— 本类注释里已为此立过铁律(第三次踩坑的记录见上)。
            //   xhint-skip 判读口径:类型闸/封禁/冷却/额度各有一条,能直接回答"为什么没探这个源"。
            "xhint", "xhint-skip", "xhint-probe",
            // ↓ 2026-10-11:**拼音搜索**(本地标题索引的积累与解析)。
            //   没有它就无法回答"敲了拼音为什么没结果" —— 是索引还没积累(首次安装的固有限制),
            //   还是解析出了片名却没搜到(源站问题)。两者处置完全不同。
            "echo-pinyin",
            // ↓ 2026-10-13:**源与线路智能调度四期优化**的三个新埋点前缀。
            //   ⚠️ 本日**第四次**踩同一个坑:三个前缀全部漏登记,真机测试时
            //   echo-weight / echo-recover / echo-xsource 落盘 0 条 —— 一度误判"功能没跑",
            //   实际代码在跑(行为已验证)、纯日志被 fileLog 静默丢弃。教训再次确认铁律:
            //   **LOG.i 的新前缀必须同步登记本数组,提交前 grep 复核**。
            //   echo-weight:连续权重台账(record/floor/clear) —— 回答"权重模型是否生效、
            //     保底配额捞了哪几个源";echo-recover:四层恢复(L1 网络平反 / L2 探活) ——
            //     回答"误封的源何时被放回";echo-xsource:跨源升清决策(decide/skip) ——
            //     回答"为什么跨源切/没切"。
            //   ⚠️ 2026-10-13 **第五次**踩坑:新增 echo-fallback(换源链跳过已失败源 / 全候选失败终态)
            //     时又忘了登记 —— 提交前 grep 复核白名单,这条铁律要刻进流程。
            "echo-weight", "echo-recover", "echo-xsource", "echo-fallback"}
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
    /**
     * 落盘文件名。
     *
     * <p>⚠️ 与 [FILE_LOG_PREFIXES] 同理,必须随 FILE_LOG 折叠 —— 否则正式包里会留下
     * 字面量 `preload_debug.log`(v1.0.46 用 dexdump 逐 dex 复验时抓到,是当时**唯一**
     * 残留的调试痕迹:其余 echo-* 埋点串在正式包里全部为 0 命中)。
     *
     * <p>FILE_LOG=false 时 fileLog() 第一行就 return,本常量根本不会被读,
     * 折成空串语义不变;而 R8 常量折叠后连字面量一起消失。
     */
    private static final String FILE_LOG_NAME = FILE_LOG ? "preload_debug.log" : "";

    /** 落盘日志单文件上限(4MB):到线就轮转成 .1,见 {@link #rotateFileLog} */
    private static final long FILE_LOG_MAX_BYTES = 4L * 1024L * 1024L;
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
                // ⚠️ 取 context / 目录**必须在 try 里面**:启动早期 AppContextHolder 还没装配,
                // `context().getFilesDir()` 会 NPE;这行一旦逃出线程池就是 FATAL EXCEPTION 直接闪退
                // (v1.1.23 真机连崩 4 次:把取目录挪到 try 外面加的轮转,把"静默少一行日志"变成了"打不开")。
                try {
                    // 每行独立开关文件:保证进程被杀时已写入的内容不丢(排查场景量小,开销可接受)
                    File current = new File(AppContextHolder.context().getFilesDir(), FILE_LOG_NAME);
                    rotateFileLog(current);
                    try (FileWriter writer = new FileWriter(current, true)) {
                        writer.write(line + "\n");
                    }
                } catch (Throwable ignored) {
                    // 落盘失败即放弃:fileLog 自身兜底,不能再走 LOG 以免递归
                }
            });
        } catch (Throwable ignored) {
            // 同上:提交落盘任务失败即放弃,防递归
        }
    }

    /**
     * 落盘日志的大小闸:超过 {@link #FILE_LOG_MAX_BYTES} 就把当前文件挪成 {@code .1}(旧的 {@code .1} 丢掉),
     * 从空文件重新写。
     *
     * <p>原来只追加、没有上限:诊断包天天装,这个文件就一直长(真机实测已 2.4MB),
     * 而且它在 {@code files/} 下,「清除缓存」按设计不碰 {@code files/} —— 等于没人管。
     * 留一份 {@code .1} 而不是直接清空,是因为排查时最需要的往往就是"事故前最后几秒"。
     */
    private static void rotateFileLog(File current) {
        if (current.length() <= FILE_LOG_MAX_BYTES) return;
        File older = new File(current.getParentFile(), FILE_LOG_NAME + ".1");
        // 腾不出位置就继续往当前文件写 —— 宁可超限,也不要做出"删了旧的又挪不动"的半截轮转
        if (older.exists() && !older.delete()) return;
        //noinspection ResultOfMethodCallIgnored
        current.renameTo(older);
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
