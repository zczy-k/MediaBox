package com.github.tvbox.osc.util.thunder;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.xunlei.downloadlib.XLDownloadManager;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.android.XLUtil;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;
import com.xunlei.downloadlib.parameter.TorrentInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.github.tvbox.osc.util.AppContextHolder;

public class Thunder {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        Context app = AppContextHolder.context();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    private static String cacheRoot = "";
    private static String localPath = "";
    private static String name = "";
    private static String task_url = "";
    private static long currentTask = 0L;
    private static ArrayList<TorrentFileInfo> torrentFileInfoArrayList = null;
    private static ExecutorService threadPool = null;


    private static void init(Context context) {
        // fake deviceId and Mac(2026-09-15 由独立 SP `rand_thunder_id` 迁入 KV,见 HawkConfig.THUNDER_IMEI/THUNDER_MAC)
        String imei = KV.get(HawkConfig.THUNDER_IMEI, "");
        String mac = KV.get(HawkConfig.THUNDER_MAC, "");
        if (TextUtils.isEmpty(imei)) {
            imei = randomImei();
            KV.put(HawkConfig.THUNDER_IMEI, imei);
        }
        if (TextUtils.isEmpty(mac)) {
            mac = randomMac();
            KV.put(HawkConfig.THUNDER_MAC, mac);
        }

        XLUtil.mIMEI = imei;
        XLUtil.isGetIMEI = true;
        XLUtil.mMAC = mac;
        XLUtil.isGetMAC = true;
        String cd3 = "cee25055f125a2fde0";
        String base64Decode = "axzNjAwMQ^^yb==0^852^083dbcff^";
        String substring = base64Decode.substring(1);
        String substring2 = cd3.substring(0, cd3.length() - 1);
        String cd = substring + substring2;
        XLTaskHelper.init(context, cd, "21.01.07.800002");
        cacheRoot = context.getCacheDir().getAbsolutePath() + File.separator + "thunder";
    }

    public static void stop(Boolean bool) {
        if (currentTask > 0) {
            XLTaskHelper.instance().stopTask(currentTask);
            currentTask = 0L;
        }
        if(bool){
            torrentFileInfoArrayList = null;
            // del cache file
            File cache = new File(task_url.isEmpty()?cacheRoot:localPath);
            recursiveDelete(cache);
            if (!cache.exists())
                cache.mkdirs();
            if (threadPool != null) {
                try {
                    threadPool.shutdownNow();
                    threadPool = null;
                } catch (Throwable th) {
                    LOG.e("Thunder", "thread pool shutdown failed", th);
                }
            }
        }
    }

    public interface ThunderCallback {

        void status(int code, String info);

        void list(Map<Integer, String> urlMap);

        void play(String url);
    }

    /**
     * 兜底建池:{@link #threadPool} 在 {@link #parse} 里建立,而 {@link #stop(boolean) stop(true)}
     * 会把它置 null。但取流请求可能早于 parse 到达(历史遗留线路表 / 旧缓存详情 / 推送的地址,
     * 见 {@code PlaybackController} 与 {@code PushDetailResolver.checkThunder})。没有这一步,
     * {@code threadPool.execute} 会 NPE —— 调用方是播放线程,直接崩进程。
     */
    private static void ensurePool() {
        if (threadPool == null) {
            init(AppContextHolder.context());
            threadPool = Executors.newSingleThreadExecutor();
        }
    }

    private static ArrayList<String> playList = null;
    private static ArrayList<String> ed2kList = null;
    public static void parse(Context context, Movie.Video.UrlBean urlBean, ThunderCallback callback) {
        init(context);
        stop(true);
        threadPool = Executors.newSingleThreadExecutor();
        torrentFileInfoArrayList=new ArrayList<>();
        playList=new ArrayList<>();
        ed2kList=new ArrayList<>();
        Map<Integer, String> urlMap = new HashMap<>();
        threadPool.execute(new ParseTask(urlBean, urlMap, callback));
    }

    private static final class ParseTask implements Runnable {

        private final Movie.Video.UrlBean urlBean;
        private final Map<Integer, String> urlMap;
        private final ThunderCallback callback;
        /** 解析失败(拿不到地址)因而被丢弃的条目数 —— 只用于日志,证明"假线路"没有被塞回列表 */
        private int droppedUnplayable = 0;

        ParseTask(Movie.Video.UrlBean urlBean, Map<Integer, String> urlMap, ThunderCallback callback) {
            this.urlBean = urlBean;
            this.urlMap = urlMap;
            this.callback = callback;
        }

        @Override
        public void run() {
            for (int idx=0;idx<urlBean.infoList.size();idx++) {
                Movie.Video.UrlBean.UrlInfo urlInfo = urlBean.infoList.get(idx);
                // ⚠️ 2026-10-18:`beanList` 为 null 必须跳过,不能直接 for-each。
                // 前置扫描(checkThunder)有这道护栏,这里原先没有 —— 一旦某个 urlInfo 的
                // beanList 为 null,整条 ParseTask 抛 NPE 直接死掉,**list / status 两个回调
                // 都不会发生** ⇒ 详情侧不替换 beanList ⇒ 裸 magnet 留在 UI 上(点了必报错)。
                if (urlInfo != null && urlInfo.beanList != null) {
                    String url="";
                    // 这一路(urlInfo)是否含磁力/种子类条目 —— 用于结尾判断"整路都是磁力且全不可播"
                    boolean idxHadThunder = false;
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        boolean isParse=false;
                        url=infoBean.url;
                        if (isMagnet(url) || isThunder(url) || isTorrent(url)) {
                            idxHadThunder = true;
                            // thunder:// 先解成 http,后续统一当磁力/种子处理
                            // (下面 isMagnet(url) 用的是解出的地址,故必须先赋值回 url)
                            //
                            // ⚠️ 2026-10-18:原实现是两行 ——
                            //   `if(isThunder(url)) url = parserThunderUrl(url);`
                            //   `String link = isThunder(url) ? parserThunderUrl(url) : url;`
                            // 第二行的 `isThunder(url)` 在第一行赋值后**必然为 false**(url 已是 http),
                            // 于是 link 恒等于 url —— 是条走不进去的死分支,留着会让人以为"要再解析一次"。
                            if (isThunder(url)) url = XLDownloadManager.getInstance().parserThunderUrl(url);
                            String link = url;
                            Uri p = Uri.parse(link);
                            if (p == null) {
                                continue;
                            }
                            String fileName = XLTaskHelper.instance().getFileName(link);
                            File cache = new File(cacheRoot + File.separator + fileName);
                            try {
                                if (currentTask > 0) {
                                    XLTaskHelper.instance().stopTask(currentTask);
                                    currentTask = 0L;
                                }
                                currentTask = isMagnet(url) ?
                                        XLTaskHelper.instance().addMagentTask(url, cacheRoot, fileName) :
                                        XLTaskHelper.instance().addThunderTask(url, cacheRoot, fileName);
                            } catch (Exception exception) {
                                LOG.e("Thunder", exception);
                                currentTask = 0;
                            }
                            if (currentTask <= 0) {
                                continue;
                            }
                            int count = 30;
                            outerLoop:
                            while (true) {
                                count--;
                                if (count <= 0) {
                                    break;
                                }
                                XLTaskInfo taskInfo = XLTaskHelper.instance().getTaskInfo(currentTask);
                                if(taskInfo!=null){
                                    switch (taskInfo.mTaskStatus) {
                                        case 2: {
                                            try {
                                                TorrentInfo torrentInfo = XLTaskHelper.instance().getTorrentInfo(cache.getAbsolutePath());
                                                if (torrentInfo == null || TextUtils.isEmpty(torrentInfo.mInfoHash)) {

                                                } else {
                                                    TorrentFileInfo[] mSubFileInfo = torrentInfo.mSubFileInfo;
                                                    if (mSubFileInfo != null) {
                                                        for (TorrentFileInfo sub : mSubFileInfo) {
                                                            if (isMedia(sub.mFileName) && sub.mFileSize > 1048576L * 30) {
                                                                sub.torrentPath = cache.getAbsolutePath();
                                                                playList.add(sub.mFileName + "$tvbox-torrent:" + torrentFileInfoArrayList.size());
                                                                torrentFileInfoArrayList.add(sub);
                                                            }
                                                        }
                                                        isParse=true;
                                                        break outerLoop;
                                                    }
                                                }
                                            } catch (Throwable throwable) {
                                                LOG.e("Thunder", throwable);
                                            }
                                        }
                                        case 3: {
                                            break outerLoop;
                                        }
                                        default: {
                                        }
                                    }
                                }
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    LOG.e("Thunder", e);
                                }
                            }
                        }else {
                            url=infoBean.url;
                            if(isThunder(url))url=XLDownloadManager.getInstance().parserThunderUrl(url);
                            if(isNetworkDownloadTask(url)){
                                task_url=url;
                                if(TextUtils.isEmpty(task_url)){
                                    continue;
                                }
                                name = XLTaskHelper.instance().getFileName(task_url);
                                playList.add(name + "$tvbox-oth:" + ed2kList.size());
                                ed2kList.add(task_url);
                                isParse=true;
                            }
                        }
                        if (!isParse) {
                            // ⚠️ 2026-10-18 修复(原实现是无条件 `playList.add(infoBean.name + "$" + infoBean.url)`)
                            //
                            // 要区分两类"没解析成功":
                            //  · **普通线路**(http/m3u8 等):本来就能直接播,必须原样保留 ——
                            //    checkThunder 只要在 urlBean 里发现**一条**磁力就把整包交给 parse,
                            //    同一包里的普通线路会走到这里。丢弃它们会**凭空弄丢可用线路**。
                            //  · **磁力/种子类**(magnet / thunder: / .torrent)却没解析出可播地址:
                            //    这类正是 d719b108 点名的"点了必报错的假线路",**不能再塞回列表**,
                            //    只计数供日志核对("不可播的条目不出现在 UI",而不是"出现但打不开")。
                            if (isMagnet(infoBean.url) || isThunder(infoBean.url) || isTorrent(infoBean.url)) {
                                droppedUnplayable++;
                            } else {
                                playList.add(infoBean.name + "$" + infoBean.url);
                            }
                        }
                    }
                    if (playList.size() > 0) {
                        urlMap.put(idx,TextUtils.join("#", playList));
                        playList.clear();
                    } else if (idxHadThunder) {
                        // ⚠️ 2026-10-18:整路都是磁力且**全部解析失败** —— 这里必须显式回一个空串,
                        // 否则该 idx 根本不进 urlMap,消费方(PushDetailResolver.checkThunder / SourceHelper)
                        // 会 `continue` 跳过替换,于是**裸 magnet 原样留在列表里**,又变回"点了必报错的假线路"。
                        // 空串会被消费方解析成空 beanList ⇒ 这一路从 UI 消失(而不是留下打不开的条目)。
                        urlMap.put(idx, "");
                    }
                }
            }

            if (droppedUnplayable > 0) {
                LOG.i("echo-thunder parse dropped-unplayable=" + droppedUnplayable);
            }
            if (urlMap.size() > 0) {
                callback.list(urlMap);
            } else {
                callback.status(-1, str(R.string.thunder_error_parse));
            }
        }
    }


    public static boolean play(String url, ThunderCallback callback) {
        if (url.startsWith("tvbox-torrent:")) {
            // ⚠️ 2026-10-18:`threadPool` 由 parse 建立,但 stop(true) 会把它置 null。
            // 取流地址并不一定来自 parse —— 历史遗留线路表、旧缓存详情、推送进来的地址
            // 都会把 `tvbox-torrent:` / `tvbox-oth:` 直接递到这里。此时 `threadPool.execute`
            // 会 NPE,而调用方是播放线程 ⇒ 直接崩进程。原先只有 ed2k/ftp 分支有兜底。
            ensurePool();
            int idx = Integer.parseInt(url.substring(14));
            // 台账已被 stop(true) 清空、或索引越界 ⇒ 这是**过期地址**(旧详情的线路表),
            // 不能 get 出去崩掉:报无效地址,由播放侧走既有失败链。
            if (torrentFileInfoArrayList == null || idx < 0 || idx >= torrentFileInfoArrayList.size()) {
                callback.status(-1, str(R.string.thunder_error_invalid_url));
                return true;
            }
            TorrentFileInfo info = torrentFileInfoArrayList.get(idx);
            if (currentTask > 0) {
                XLTaskHelper.instance().stopTask(currentTask);
                currentTask = 0L;
            }
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    String torrentName = new File(info.torrentPath).getName();
                    String cache = cacheRoot + File.separator + torrentName.substring(0, torrentName.lastIndexOf("."));
                    currentTask = XLTaskHelper.instance().addTorrentTask(info.torrentPath, cache, info.mFileIndex);
                    if (currentTask < 0)
                        callback.status(-1, str(R.string.thunder_error_download));
                    // ⚠️ 2026-10-18:等待窗口从 30s 放宽到 180s(原 30 × sleep(1000))。
                    //
                    // 30 秒对"下载完才能播"这件事太短:种子即使能连上,把首个可播片段拉到
                    // 本地也要远超 30 秒。原窗口的后果是**必然超时**(用户看到的永远是
                    // thunder_error_timeout),这正是停用整条通路时的实测结论之一。
                    int count = 180;
                    int waited = 0;
                    while (true) {
                        count--;
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout));
                            break;
                        }
                        XLTaskInfo taskInfo = XLTaskHelper.instance().getBtSubTaskInfo(currentTask, info.mFileIndex).mTaskInfo;
                        // ⚠️ 2026-10-18 修复:原实现把 `case 1 / case 4 / case 2` **合并**处理,
                        // 三个状态都直接 `callback.play(getLoclUrl(...))` —— 于是"**下载中**"
                        // 也把本地地址交给播放器,表现为**边下边播、缓冲反复中断**。
                        // 这不是"下载完再播"该有的样子,且用户完全无法理解为什么一直卡。
                        //
                        // 现在只认 `case 2`(下载完成)才交地址;`case 1 / 4`(未开始/下载中)
                        // **继续等**,并每 5 秒回报一次进度(让界面有反馈,不再死寂)。
                        switch (taskInfo.mTaskStatus) {
                            case 3: {
                                callback.status(-1, errorInfo(taskInfo.mErrorCode));
                                return;
                            }
                            case 2: { // 下载完成:这才可以交给播放器
                                String pUrl = XLTaskHelper.instance().getLoclUrl(cache + File.separator + info.mFileName);
                                callback.play(pUrl);
                                return;
                            }
                            case 1:
                            case 4: { // 未开始 / 下载中:继续等,不交地址
                                waited++;
                                if (waited % 5 == 0) {
                                    // 进度(第一个参数 >= 0 是"非错误"的约定,调用方据此区分提示样式)
                                    callback.status(waited, "正在下载 " + waited + "s / " +
                                            (taskInfo.mDownloadSize > 0
                                                    ? (taskInfo.mDownloadSize / 1048576L) + "MB"
                                                    : "…"));
                                }
                                break;
                            }
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            LOG.e("Thunder", e);
                        }
                    }
                }
            });
            return true;
        }
        if (url.startsWith("tvbox-oth:")) {
            ensurePool();
            stop(false);
            int idx = Integer.parseInt(url.substring(10));
            // 同 torrent 分支:台账被 stop(true) 清空(idx=-1)或索引越界都是**过期地址**
            if (ed2kList == null || idx < 0 || idx >= ed2kList.size()) {
                callback.status(-1, str(R.string.thunder_error_invalid_url));
                return true;
            }
            task_url=ed2kList.get(idx);
            name = XLTaskHelper.instance().getFileName(task_url);
            localPath = (new File(cacheRoot+File.separator+"temp",FileUtils.getFileNameWithoutExt(name)))+"/";
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null);

            threadPool.execute(new Runnable() {
                @Override
                public void run() {

                    int count = 20;
                    while (true) {
                        count--;
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout));
                            break;
                        }
                        String playUrl=getPlayUrl();
                        if(playUrl != null && !playUrl.isEmpty()){
                            callback.play(playUrl);
                            return;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            LOG.e("Thunder", e);
                        }
                    }
                }
            });
            return true;
        }
        if (isEd2k(url) || isFtp(url)) {
            ensurePool();
            if (currentTask > 0) {
                XLTaskHelper.instance().stopTask(currentTask);
                currentTask = 0L;
            }
            task_url=url;
            name = XLTaskHelper.instance().getFileName(task_url);
            localPath = (new File(cacheRoot+File.separator+"temp",FileUtils.getFileNameWithoutExt(name)))+"/";
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null);

            threadPool.execute(new Runnable() {
                @Override
                public void run() {

                    int count = 20;
                    while (true) {
                        count--;
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout));
                            break;
                        }
                        String playUrl=getPlayUrl();
                        if(!TextUtils.isEmpty(playUrl)){
                            callback.play(playUrl);
                            return;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            LOG.e("Thunder", e);
                        }
                    }
                }
            });
            return true;
        }
        return false;
    }


    private static String errorInfo(int code) {
        switch (code) {
            case 9125:
                return str(R.string.thunder_error_name_too_long);
            case 111120:
                return str(R.string.thunder_error_path_too_long);
            case 111142:
                return str(R.string.thunder_error_file_too_small);
            case 111085:
                return str(R.string.thunder_error_no_space);
            case 111171:
                return str(R.string.thunder_error_network_refused);
            case 9301:
                return str(R.string.thunder_error_buffer);
            case 114001:
            case 114004:
            case 114005:
            case 114006:
            case 114007:
            case 114011:
            case 9304:
            case 111154:
                return str(R.string.thunder_error_copyright);
            case 114101:
                return str(R.string.thunder_error_invalid_url);
            default:
                return "ErrorCode=" + code;
        }
    }


    public static boolean isSupportUrl(String url) {
//        return isMagnet(url) || isThunder(url) || isEd2k(url) || isFtp(url);
        return isMagnet(url) || isThunder(url) || isTorrent(url);
    }

    private static boolean isMagnet(String url) {
        return url.toLowerCase().startsWith("magnet:");
    }

    private static boolean isThunder(String url) {
        return url.toLowerCase().startsWith("thunder");
    }

    private static boolean isTorrent(String url) {
        return url.toLowerCase().split(";")[0].endsWith(".torrent");
    }

    private static boolean isEd2k(String url) {
        return url.toLowerCase().startsWith("ed2k:");
    }

    public static boolean isFtp(String url) {
        return url.toLowerCase().startsWith("ftp://");
    }

    static void recursiveDelete(File file) {
        if (!file.exists())
            return;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                recursiveDelete(f);
            }
        }
        file.delete();
    }

    static ArrayList<String> formats = new ArrayList<>();

    static boolean isMedia(String name) {
        if (formats.size() == 0) {
            formats.add(".rmvb");
            formats.add(".avi");
            formats.add(".mkv");
            formats.add(".flv");
            formats.add(".mp4");
            formats.add(".rm");
            formats.add(".vob");
            formats.add(".wmv");
            formats.add(".mov");
            formats.add(".3gp");
            formats.add(".asf");
            formats.add("mpg");
            formats.add("mpeg");
            formats.add("mpe");
        }
        for (String f : formats) {
            if (name.toLowerCase().endsWith(f))
                return true;

        }
        return false;
    }

    static String randomImei() {
        return randomString("0123456", 15);
    }

    static String randomMac() {
        return randomString("ABCDEF0123456", 12).toUpperCase();
    }

    static String randomString(String base, int length) {
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }


    public static boolean isNetworkDownloadTask(String url){
        if(TextUtils.isEmpty(url)) return false;
        if(isFtp(url) || isEd2k(url)){
            return true;
        }else{
            return false;
        }
    }

    public static void stopTask(){
        if(currentTask != 0L){
            XLTaskHelper.instance().deleteTask(currentTask, task_url.isEmpty()?cacheRoot:localPath);
            currentTask = 0L;
        }
    }
    public static XLTaskInfo getTaskInfo(){
        return XLTaskHelper.instance().getTaskInfo(currentTask);
    }

    public static String getPlayUrl(){
            if(currentTask != 0L){
                if(isNetworkDownloadTask(task_url)){
                    return XLTaskHelper.instance().getLoclUrl(localPath + name);
                }
        }
        return null;
    }



}
