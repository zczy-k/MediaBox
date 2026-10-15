package com.github.tvbox.osc.util;

import com.github.tvbox.osc.util.LOG;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.server.ControlManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {

    public static boolean writeSimple(byte[] data, File dst) {
        try {
            if (dst.exists())
                dst.delete();
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst));
            bos.write(data);
            bos.close();
            return true;
        } catch (IOException e) {
            LOG.e("FileUtils", e);
        }
        return false;
    }

    public static byte[] readSimple(File src) {
        try {
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src));
            int len = bis.available();
            byte[] data = new byte[len];
            bis.read(data);
            bis.close();
            return data;
        } catch (IOException e) {
            LOG.e("FileUtils", e);
        }
        return null;
    }

    public static void copyFile(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            is.close();
            os.close();
        }
    }

    public static void recursiveDelete(File file) {
        if (!file.exists())
            return;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                recursiveDelete(f);
            }
        }
        file.delete();
    }

    public static String readFileToString(String path, String charsetName) {
        // 定义返回结果
        String jsonString = "";

        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(path)), charsetName));// 读取文件
            String thisLine = null;
            while ((thisLine = in.readLine()) != null) {
                jsonString += thisLine;
            }
            in.close();
        } catch (IOException e) {
            LOG.e("FileUtils", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException el) {
                    LOG.d("FileUtils", "close reader failed");
                }
            }
        }
        // 返回拼接好的JSON String
        return jsonString;
    }

    public static String getRootPath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static File getLocal(String path) {
        return new File(path.replace("file:/", getRootPath()));
    }

    public static File getCacheDir() {
        return AppContextHolder.context().getCacheDir();
    }

    public static String getCachePath() {
        return getCacheDir().getAbsolutePath();
    }
    public static String getFilePath() {
        return AppContextHolder.context().getFilesDir().getAbsolutePath();
    }

    /** 持久层目录名:放在 files 下、系统"清除缓存"与手机管家清理都碰不到的那几样下载产物 */
    public static final String DURABLE_DIR_NAME = "durable";

    /**
     * 下载产物的持久目录 {@code files/durable/}(爬虫 jar 包、字幕落盘、导入字幕副本)。
     *
     * <p>为什么不在 {@code cache/} 下:系统设置的「清除缓存」和手机管家的"清理垃圾"只扫 cache 目录,
     * 而这几样删掉的代价与普通缓存完全不是一回事 ——
     * <ul>
     *   <li>jar 包是从订阅配置里的地址下载的,**源站一改地址或下架就永远下不回来**,
     *       表现是"这个源打不开":它是下载产物,不是缓存;</li>
     *   <li>导入字幕的副本被清掉后,那条"这部片用这个字幕"的决定就悬空
     *       (日志里的 {@code local subtitle gone, fallback} 正是这个)。</li>
     * </ul>
     * 放 files 下的语义是用户要的那种:**只有卸载 App 或应用管理里"删除数据"才会一起没掉**,
     * 清手机垃圾不会误删。图片/WebView/HTTP 缓存仍留在 cache —— 那些被清了无所谓,本来就该让管家能清。
     *
     * <p>⚠️ 反过来,系统也不会替我们回收这里:占用全靠各自的上界封着
     * (字幕 LRU 200 条见 {@link SubtitleCacheJanitor},jar 见 {@code pruneSpiderJarCache})。
     * 「修复工具」仍会显式清它 —— 显示的数字必须等于按钮真能清掉的东西。
     */
    public static File getDurableDir() {
        File dir = new File(getFilePath(), DURABLE_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 把历史上留在 {@code cache/} 的 jar、{@code zimu/}、导入副本 {@code subtitle_*}
     * 一次性搬进 {@link #getDurableDir()}。
     *
     * <p>必须**在任何源加载与字幕解析之前**跑完:JarLoader 一开始下载就会在旧目录建文件,搬晚了等于白搬。
     * 同分区 {@code renameTo} 只改目录项(几个文件毫秒级),所以在启动主线程早期同步执行,不放后台。
     * 目标已存在(搬过一次 / 全新安装)就什么都不做 ⇒ 幂等。搬不动只记日志:各写入方首次使用时
     * 会自建目录,不该因为搬迁失败而让功能不可用。
     *
     * @return 本次移动的条目数
     */
    public static int migrateLegacyCachesToDurable() {
        int moved = 0;
        try {
            File cache = getCacheDir();
            File durable = new File(getFilePath(), DURABLE_DIR_NAME);
            if (!durable.exists()) durable.mkdirs();
            for (String name : new String[]{"jar", "zimu"}) {
                File from = new File(cache, name);
                File to = new File(durable, name);
                if (!from.exists() || to.exists()) continue;
                if (from.renameTo(to)) moved++;
                else LOG.i("echo-durable move-failed: " + name);
            }
            File[] copies = cache.listFiles();
            if (copies != null) {
                for (File one : copies) {
                    if (!one.isFile() || !one.getName().startsWith("subtitle_")) continue;
                    File to = new File(durable, one.getName());
                    if (to.exists()) continue;
                    if (one.renameTo(to)) moved++;
                    else LOG.i("echo-durable copy-move-failed: " + one.getName());
                }
            }
            if (moved > 0) LOG.i("echo-durable migrated=" + moved + " -> " + durable.getAbsolutePath());
        } catch (Throwable th) {
            LOG.e("FileUtils", th);
        }
        return moved;
    }

    public static void cleanDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        for(File one : files) {
            try {
                deleteFile(one);
            } catch (Exception e) {
                LOG.e("FileUtils", e);
            }
        }
    }

    public static boolean isWeekAgo(File file)
    {
        long oneWeekMillis = 3L * 24 * 60 * 60 * 1000;
        long timeDiff = System.currentTimeMillis() - file.lastModified();
        return timeDiff > oneWeekMillis;
    }

    /**
     * 递归删除:文件直接删,目录先删空其内容。
     * ⚠️ 不能把 canWrite() 当删除前提(2026-09-12 修复 Bug):爬虫 jar 释放的加固库是 400 只读
     * (实测 cache/danMugo_v8.so 13,441,840B + cache/.ftyfnw3nr9fd9ykv,两者合计正好是设置页显示的 12.9MB),
     * canWrite() 为 false 时会被直接跳过,于是「清除缓存」怎么点都清不掉、大小恒定不变。
     * 删除只要求**父目录**可写,与文件自身只读无关,故这里先尝试解除只读再删。
     */
    public static void deleteFile(File file) {
        if (!file.exists()) return;
        if (file.isFile()) {
            deleteSingle(file);
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null || files.length == 0) {
                deleteSingle(file);
                return;
            }
            for(File one : files) {
                deleteFile(one);
            }
        }
        return;
    }

    /**
     * 递归强删整个目录树:先删子项(文件/子目录),最后删目录本身。
     * 与 {@link #deleteFile} 的"只清内容、保留各级目录"语义不同,本方法连各级子目录一并删除,
     * 供 {@link #purgeExoCacheIfPending} 彻底删除 exo-video-cache 目录树——
     * 否则多级子目录需多次启动才能逐层清空(表现为 purge-incomplete 逐次递减 10→7→3→done)。
     */
    private static void deleteFileTree(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File one : files) {
                    deleteFileTree(one);
                }
            }
        }
        // 此处 file 已是文件或已清空的空目录:解除只读再删
        deleteSingle(file);
    }

    /** 删除单个文件/空目录;只读项先解除只读再删,仍失败则留日志便于排查 */
    private static void deleteSingle(File file) {
        if (!file.canWrite()) file.setWritable(true);
        if (!file.delete()) {
            LOG.i("clearCache: cannot delete " + file.getAbsolutePath());
        }
    }

    /**
     * 启动自检:清掉私有目录里"假的原生库",避免爬虫把应用拖进开机必崩的死循环(2026-09-21)。
     *
     * <p>实测:第三方爬虫在静态初始化里从云存储下载 {@code libwexproxy.so},远端对象已删除时 CDN 返回
     * 313 字节 XML 报错,爬虫把报错原文当 .so 落盘再 {@code System.load} ⇒ {@code bad ELF magic}。
     * 坏文件留在原地 ⇒ 每次冷启动都崩一次,用户连换源都进不去;而那段初始化跑在爬虫自己的线程上,
     * 接不住异常,只能在**装载之前**清掉。
     *
     * <p>判据只认"ELF 魔数不符"(合法库必以 {@code 0x7F 'E' 'L' 'F'} 开头),名字只认 {@code *.so}
     * 与爬虫临时名 {@code .lib*};**.wexstring 之类的非库资源不碰**,也绝不按大小/时间去猜。
     * 扫描范围 = 私有 files 目录树,失败不抛。
     *
     * @return 删掉的坏文件个数
     */
    public static int repairBogusNativeLibs() {
        try {
            return repairBogusNativeLibs(new File(getFilePath()));
        } catch (Throwable e) {
            LOG.i("native-lib-repair failed: " + e.getMessage());
            return 0;
        }
    }

    /** 扫描指定目录树并清掉假原生库,返回删除个数(公开重载是为了能在临时目录上单测) */
    public static int repairBogusNativeLibs(File root) {
        try {
            return repairBogusNativeLibs(root, 0);
        } catch (Throwable e) {
            LOG.i("native-lib-repair failed: " + e.getMessage());
            return 0;
        }
    }

    /** ELF 魔数:0x7F 后跟 ASCII 的 "ELF" */
    private static final byte[] ELF_MAGIC = {0x7F, 'E', 'L', 'F'};

    /** 目录深度上限:爬虫目录层级很浅(实测 1 层),限制深度避免在大目录上白跑 */
    private static final int REPAIR_MAX_DEPTH = 3;

    private static int repairBogusNativeLibs(File dir, int depth) {
        if (dir == null || depth > REPAIR_MAX_DEPTH) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int repaired = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                repaired += repairBogusNativeLibs(file, depth + 1);
                continue;
            }
            if (!looksLikeNativeLib(file.getName())) continue;
            if (hasElfMagic(file)) continue;
            // 大小要在删之前取:删完 length() 恒为 0(实测这个值就是 313,直接指向 CDN 报错页)
            long size = file.length();
            deleteSingle(file);
            if (!file.exists()) {
                repaired++;
                LOG.i("native-lib-repair removed bogus " + file.getAbsolutePath() + " size=" + size);
            }
        }
        return repaired;
    }

    /** 名字像原生库才检查:{@code *.so} 或爬虫的临时名 {@code .lib*} */
    private static boolean looksLikeNativeLib(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".so") || lower.startsWith(".lib");
    }

    /** 读前 4 字节比对 ELF 魔数;读不到(不存在/无权限)一律当作"不是合法库"留给调用方决定 */
    private static boolean hasElfMagic(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[ELF_MAGIC.length];
            if (in.read(magic) != ELF_MAGIC.length) return false;
            for (int i = 0; i < ELF_MAGIC.length; i++) {
                if (magic[i] != ELF_MAGIC[i]) return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void cleanPlayerCache() {
        String thunderCachePath = getCachePath() + "/thunder/";
        File thunderCacheDir = new File(thunderCachePath);
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir);
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
    }

    /**
     * 外置缓存里属于**用户数据**、清理时必须保留的一级目录:config/ = 老的本地源配置副本
     * (2026-09-16 起新副本改放外置 files,见 {@link #getExternalFilesPath()};clan:// 地址直接指向该文件,
     * 删掉等于把订阅源弄丢)。⚠️ 系统「清除缓存」仍会删它(历史遗留:用户数据本不该放在 cacheDir)。
     */
    private static final String EXTERNAL_CACHE_KEEP_DIR = "config";

    /**
     * 应用缓存占用(内部缓存 + 外部缓存 + {@code files/csp},不含 {@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据)。
     *
     * <p>口径必须跟「清除缓存」按钮**实际删的东西**一致:它除了两处缓存目录,还删
     * {@code files/csp}(爬虫的 jar/so,真机实测 2~8MB)。以前只统计 cache 目录,
     * 于是"显示 8MB、其实清掉 15MB"—— 数字与行为不一致,用户没法拿它做判断。
     *
     * <p>其余口径:Exo 视频缓存目录 exo-video-cache 位于外部缓存,只统计内部缓存会漏掉绝大部分占用;
     * 外部缓存不可用时 getExternalCacheDir() 返回 null,此时不叠加,避免与内部缓存重复计数。
     * 目录递归统计是耗时 IO,须在后台线程调用。
     */
    public static long getCacheSize() {
        long size = directorySize(getCacheDir(), null);
        File externalCacheDir = AppContextHolder.context().getExternalCacheDir();
        if (externalCacheDir != null && !externalCacheDir.getAbsolutePath().equals(getCachePath())) {
            size += directorySize(externalCacheDir, EXTERNAL_CACHE_KEEP_DIR);
        }
        size += directorySize(new File(getFilePath() + "/csp/"), null);
        // 持久层(jar / 字幕落盘 / 导入副本)也算进来:它在 files 下、系统清不到,
        // 但「修复工具」会显式删它 —— 显示的数字必须等于按钮真能清掉的东西。
        size += directorySize(new File(getFilePath() + "/" + DURABLE_DIR_NAME + "/"), null);
        return size;
    }

    /** [skipChildName] 只在顶层生效:跳过该名字的一级子项(用于排除外置缓存里的用户数据) */
    private static long directorySize(File dir, String skipChildName) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File one : files) {
            if (skipChildName != null && skipChildName.equals(one.getName())) continue;
            size += one.isDirectory() ? directorySize(one, null) : one.length();
        }
        return size;
    }

    /** 字节数 → 展示文本(空缓存显示 0KB);与统计一致,须在后台线程调用 */
    public static String formatCacheSize(long bytes) {
        if (bytes <= 0) return "0KB";
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024) + "KB";
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024);
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    /** Exo 边播缓存目录名:与 ExoMediaSourceHelper 的共享 SimpleCache 目录保持一致 */
    private static final String EXO_CACHE_DIR_NAME = "exo-video-cache";
    /** 「清除缓存」写下的待清理标记:由 {@link #purgeExoCacheIfPending()} 在下次启动早期执行 */
    private static final String PENDING_EXO_CLEAR_FLAG = "clear_exo_cache.pending";

    /**
     * 清理应用缓存(内部 + 外部,含 exo-video-cache)。2026-09-14 调整:**改为直接删除 exo-video-cache**
     * —— 对齐 fongmi(FileUtil.clearCache → Path.clear(Path.cache) 递归强删整个 cacheDir 含 exo 子目录)。
     * 进程级共享 SimpleCache 常驻,目录被删会"内存索引/磁盘失配",但 CacheDataSource 已设
     * FLAG_IGNORE_CACHE_ON_ERROR 兜底(不崩溃),下次播放缓存 miss 回源,SimpleCache 自愈(移除不存在的 span)。
     * 故点「清除缓存」后数字立即归零,不再延迟到下次启动(此前 2026-09-13 的"写待清理标记 + 下次启动 purge"
     * 方案被本次替换;{@link #purgeExoCacheIfPending()} 保留以清理历史遗留标记)。
     * 用 {@link #deleteFileTree} 递归强删整个目录树(含各级子目录),避免 deleteFile "只清内容保留目录"留下空子目录。
     * 与系统设置里的「清除缓存」同语义:{@code cache/} 下的东西一律删。
     * ⚠️ 2026-10-15 起**已下载的爬虫 jar / 字幕落盘 / 导入副本不在这里删** —— 它们挪到了
     * {@code files/durable}(手机管家扫不到),由 {@link #clearSpiderCacheFiles()} 显式清,
     * 两处加起来才等于设置页显示的那个数字。
     * {@link #EXTERNAL_CACHE_KEEP_DIR} 用户数据(config 订阅源)保留不动。
     * 耗时 IO,须在后台线程调用。
     */
    public static void clearCache() {
        // ① 内部缓存:逐项强删(含回落到内部的 exo-video-cache,不再跳过)
        File cacheDir = getCacheDir();
        File[] innerFiles = cacheDir.listFiles();
        if (innerFiles != null) {
            for (File one : innerFiles) {
                try {
                    deleteFileTree(one);
                } catch (Exception e) {
                    LOG.e("FileUtils", e);
                }
            }
        }
        // ② 外部缓存:逐项强删,跳过 config(用户订阅源数据)
        File externalCacheDir = AppContextHolder.context().getExternalCacheDir();
        if (externalCacheDir == null) return;
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File one : files) {
            if (EXTERNAL_CACHE_KEEP_DIR.equals(one.getName())) continue;
            try {
                deleteFileTree(one);
            } catch (Exception e) {
                LOG.e("FileUtils", e);
            }
        }
    }

    /**
     * 「清除缓存」遗留的 Exo 视频缓存清理(2026-09-13):在 App 启动早期调用,
     * **必须早于 ExoMediaSourceHelper.getSharedCache 的首次创建**(SimpleCache 尚未持有目录/索引时删除才安全)。
     * 无待清理标记时仅一次文件存在性检查,零开销;有标记时删除 exo-video-cache 目录(含索引),
     * 内容清空才清除标记,否则保留标记待下次启动重试(删除中途进程被杀也保留标记,下次继续)。
     * 目录删除为耗时 IO,须在后台线程调用。
     */
    public static void purgeExoCacheIfPending() {
        File flag = pendingExoClearFlag();
        if (!flag.exists()) return;
        File exoDir = new File(getExternalCachePath(), EXO_CACHE_DIR_NAME);
        LOG.i("echo-exo-cache-purge-start: " + exoDir.getAbsolutePath());
        try {
            // 强删整个目录树(含各级子目录):deleteFile 只清内容、保留各级目录,会留空子目录导致
            // purge-incomplete 逐次递减(10→7→3→done),需多次启动才清干净
            deleteFileTree(exoDir);
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
        File[] remaining = exoDir.exists() ? exoDir.listFiles() : null;
        if (remaining == null || remaining.length == 0) {
            if (exoDir.exists()) exoDir.delete(); // 兜底:理论上 deleteFileTree 已删,防御性再删一次
            flag.delete(); // 确认清理完成后才清标记:删除失败/进程中途被杀都保留标记,下次启动继续
            LOG.i("echo-exo-cache-purge-done");
        } else {
            // 详情取证:逐项打剩余项的类型/可写/大小/路径,定位强删后仍删不掉的根因(疑似只读/被占用)
            for (File r : remaining) {
                LOG.i("echo-exo-cache-remain: type=" + (r.isDirectory() ? "DIR" : "FILE")
                        + " writable=" + r.canWrite()
                        + " size=" + r.length()
                        + " path=" + r.getAbsolutePath());
            }
            // 少量文件删除失败:保留标记,下次启动重试
            LOG.i("echo-exo-cache-purge-incomplete: " + remaining.length);
        }
    }

    /** 「清除缓存」遗留的待清理标记文件(见 {@link #clearCache()} 的说明) */
    private static File pendingExoClearFlag() {
        return new File(getFilePath(), PENDING_EXO_CLEAR_FLAG);
    }

    public static void clearSpiderCacheFiles() {
        cleanDirectory(new File(getFilePath() + "/csp/"));
        // 2026-10-15:爬虫 jar 与字幕落盘/导入副本从 cache 挪进 files/durable(系统"清除缓存"与
        // 手机管家都碰不到它),所以「修复工具」要显式清 —— 少清一样就等于"点了却没清干净"。
        cleanDirectory(new File(getFilePath() + "/" + DURABLE_DIR_NAME + "/"));
        cleanDirectory(new File(getCachePath() + "/py/"));
        cleanDirectory(new File(getCachePath() + "/catvod_jsapi/"));
        clearJsModuleCache();
    }

    /**
     * 清掉 KV 里**可再生**的两类缓存(2026-10-14):此前「清除缓存」只删文件目录,
     * KV(MMKV)里的缓存一个都不动 —— 用户看到目录数字归零,实际 KV 里的搜索命中缓存与
     * JS 运行时缓存(爬虫每次跑都会写一份编译产物)一直躺着,而且**随源数量只增不减**。
     *
     * <p>只清"丢了能自己长回来"的:
     * <ul>
     *   <li>{@code search_cache_*}:搜索命中缓存,下次搜同一词自然会再写;</li>
     *   <li>{@code jsRuntime_*}:JS 运行时代码缓存,下次加载该源重新编译。</li>
     * </ul>
     *
     * <p>⚠️ **绝不清** {@code cache_*}:那是爬虫自己写的业务数据,不少 spider 拿它存
     * 登录态/token/分页游标,清掉会让那些源直接不可用(用户得重新登录)。这正是"清除缓存"
     * 与"清除数据"必须分开的理由。
     */
    public static void clearRebuildableKvCaches() {
        int removed = 0;
        // 删哪些前缀由 KvDataKind 的登记表决定(白名单):新加一份台账忘了登记,后果是"不会被清",
        // 而不是"用户攒的资产被清掉"。拼音索引/线路健康也有登记,但它们的存储归各自类管,
        // 由设置页调 TitleIndexMemory.clear() / LineHealthMemory.clear() 落地。
        for (String prefix : KvDataKind.rebuildablePrefixes()) {
            try {
                for (String key : KV.keys(prefix)) {
                    KV.delete(key);
                    removed++;
                }
            } catch (Exception e) {
                LOG.e("FileUtils", e);
            }
        }
        LOG.i("clearCache kv removed=" + removed);
    }

    /** 爬虫 jar 目录保留上限:文件名 = MD5(jarUrl),同 URL 不累积,但订阅更替会只增不减。 */
    private static final int CSP_JAR_MAX_KEEP = 20;

    /** 按修改时间保留最近的 jar,其余删除(启动后台 + 手动清理共用)。 */
    public static void pruneSpiderJarCache() {
        pruneSpiderJarCache(CSP_JAR_MAX_KEEP);
    }

    static void pruneSpiderJarCache(int maxKeep) {
        if (maxKeep < 1) return;
        try {
            File dir = new File(getFilePath() + "/csp/");
            File[] files = dir.listFiles();
            if (files == null || files.length <= maxKeep) return;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = maxKeep; i < files.length; i++) {
                try {
                    if (files[i].isFile()) files[i].delete();
                } catch (Exception e) {
                    LOG.e("FileUtils", e);
                }
            }
            LOG.i("clearCache: pruned csp jars keep=" + maxKeep + " total=" + files.length);
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
    }

    private static void clearJsModuleCache() {
        File externalCacheDir = new File(getExternalCachePath());
        File[] files = externalCacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file != null && file.getName().startsWith("qjscache_")) {
                deleteFile(file);
            }
        }
    }

    public static String read(String path) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(getLocal(path))));
            StringBuilder sb = new StringBuilder();
            String text;
            while ((text = br.readLine()) != null) sb.append(text).append("\n");
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String getFileName(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        return fileName;
    }

    public static String getFileNameWithoutExt(String filePath){
        if(TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if(p != -1){
            fileName = fileName.substring(p + 1);
        }
        p = fileName.indexOf('.');
        if(p != -1){
            fileName = fileName.substring(0, p);
        }
        return fileName;
    }


    public static boolean hasExtension(String path) {
        int lastDotIndex = path.lastIndexOf(".");
        int lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
        // 如果路径中有点号，并且点号在最后一个斜杠之后，认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length() - 1;
    }

    public static void saveCache(File cache,String json){
        try {
            File cacheDir = cache.getParentFile();
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            if (cache.exists())
                cache.delete();
            FileOutputStream fos = new FileOutputStream(cache);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable th) {
            LOG.e("FileUtils", th);
        }
    }





    //JS  工具方法
    private static final Pattern URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    public static String loadModule(String name) {
        String rel = null;
        try {
            if (name.contains("gbk.js")) {
                name = "gbk.js";
            } else if (name.contains("模板.js")) { // i18n: keep(R4:模板.js 文件名约定)
                name = "模板.js"; // i18n: keep(R4:模板.js 文件名约定)
            } else if (name.contains("cat.js")) {
                name = "cat.js";
            }
            LOG.i("echo-loadModule "+name);
            Matcher m = URL_JOIN.matcher(name);
            if (m.find()) {
                String cache = getCache(MD5.encode(name));
                rel= cache;
                if (StringUtils.isEmpty(cache)) {
                    String netStr = get(name);
                    if (!TextUtils.isEmpty(netStr)) {
                        setCache(604800, MD5.encode(name), netStr);
                    }
                    rel= netStr;
                }
            } else if (name.startsWith("assets://")) {
                rel= getAsOpen(name.substring(9));
            } else if (isAsFile(name, "js/lib")) {
                rel=getAsOpen("js/lib/" + name);
            } else if (name.startsWith("file://")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("file:///", "")
                        .replace("file://", ""));
            } else if (name.startsWith("clan://localhost/")) {
                rel=get(ControlManager.get()
                        .getAddress(true) + "file/" + name.replace("clan://localhost/", ""));
            } else if (name.startsWith("clan://")) {
                String substring = name.substring(7);
                int indexOf = substring.indexOf(47);
                rel=get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1));
            }
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
        return rel;
    }


    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS 线程并发读写,改并发容器
    private static final Map<String, Set<String>> cachedDirFiles = new ConcurrentHashMap<>();
    public static boolean isAsFile(String name,String dir) {
        // 1. 先从缓存里取目录列表
        Set<String> files = cachedDirFiles.get(dir);
        if (files == null) {
            LOG.i("echo-读取AssetsList");
            try {
                String[] list = AppContextHolder.context().getAssets().list(dir);
                files = new HashSet<>(Arrays.asList(list));
            } catch (IOException e) {
                files = Collections.emptySet();
            }
            cachedDirFiles.put(dir, files);
        }
        // 2. 内存查找
        return files.contains(name.trim());
    }

    public static String getAsOpen(String name) {
        try {
            InputStream is = AppContextHolder.context().getAssets().open(name);
            byte[] data = new byte[is.available()];
            is.read(data);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
        return "";
    }

    public static String getCache(String name) {
        try {
            String code = "";
            File file = open(name);
            if (file.exists()) {
                code = new String(readSimple(file));
            }
            if (TextUtils.isEmpty(code)) {
                return "";
            }
            JsonObject asJsonObject = (new Gson().fromJson(code, JsonObject.class)).getAsJsonObject();
            if (((long) asJsonObject.get("expires").getAsInt()) <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name));
            }
            return asJsonObject.get("data").getAsString();
        } catch (Exception e4) {
            return "";
        }
    }

    public static void setCache(int time, String name, String data) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("expires", (int) (time + (System.currentTimeMillis() / 1000)));
            jSONObject.put("data", data);
            writeSimple(jSONObject.toString().getBytes(), open(name));
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
    }

    public static void setCacheByte(String name, byte[] data) {
        try {
            writeSimple(byteMerger("//DRPY".getBytes(), Base64.encode(data, Base64.URL_SAFE)), open("B_" + name));
        } catch (Exception e) {
            LOG.e("FileUtils", e);
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2){
        byte[] bt3 = new byte[bt1.length+bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public static String get(String str) {
        return get(str, null);
    }

    public static String get(String str, Map<String, String> headerMap) {
        if (headerMap == null) {
            headerMap=new HashMap<>();
            headerMap.put("User-Agent",str.startsWith("https://gitcode.net/") ? UA.random() : "okhttp/3.15");
        }
        return OkHttp.string(str, headerMap);
    }

    public static File open(String str) {
        return new File(getExternalCachePath() + "/qjscache_" + str + ".js");
    }
    public static String getExternalCachePath() {
        File externalCacheDir = AppContextHolder.context().getExternalCacheDir();
        if (externalCacheDir == null){
            return getCachePath();
        }
        return externalCacheDir.getAbsolutePath();
    }

    /**
     * 外置私有 files 目录:本地源配置副本等用户数据的落点(2026-09-16 起由外置 cache 迁来,避免被
     * 系统「清除缓存」删掉后只能静默回落到 filesDir 旧快照);外置不可用时回落内部 files 目录。
     */
    public static String getExternalFilesPath() {
        File externalFilesDir = AppContextHolder.context().getExternalFilesDir(null);
        if (externalFilesDir == null) {
            return getFilePath();
        }
        return externalFilesDir.getAbsolutePath();
    }
}
