package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppContextHolder;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubscriptionBucket;
import com.github.tvbox.osc.util.SubtitleCacheJanitor;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends Application {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    /**
     * 语言资源包裹必须最早做(attachBaseContext 早于 onCreate),而语言选择存在 KV 里
     * ⇒ 在这里提前 KV.init(幂等,onCreate 里那处保留不动)。
     */
    @Override
    protected void attachBaseContext(Context base) {
        KV.init(base);
        super.attachBaseContext(LanguageManager.INSTANCE.wrap(base));
    }

    /**
     * 进程级前后台判定(2026-10-17):App 整体退到后台就把正在播的内容**暂停**,页面与内核都留着 ——
     * 用户再进来仍停在播放界面,由他自己点继续。
     *
     * <p>为什么不用 Activity 的 onPause:那已经是现有暂停动作,但它只在页面还活着时管用。系统一旦
     * 在后台回收播放页,它就近距离变成 onDestroy → detach,而 detach 过去会出现"停一下又自己播回来"
     * (真机 2026-10-17 12:27:21 退出 → 12:27:41 出声)。进程级这条先按下暂停,销毁路径再兜一次。
     *
     * <p>ProcessLifecycleOwner 的 onStop 有约 700ms 去抖:详情页 → 音乐页/直播页这类页面间跳转
     * 不会被当成"退到后台",后台出声的既有语义(纯音频)因此不受影响。
     */
    private void installBackgroundPause() {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(LifecycleOwner owner) {
                com.github.tvbox.osc.player.PlaybackEngine engine =
                        com.github.tvbox.osc.player.PlaybackService.peek();
                if (engine != null) engine.pauseForBackground();
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 下层(util/data/server/catvod)读 Context 一律走这里,不再反向依赖 App 类
        AppContextHolder.install(this);
        // 源健康「L1 网络恢复平反」的网络监听(2026-10-13,P0-3):网络重新可用时,
        // 立即解除"纯网络类证据"支撑的封禁(误封的源不必等满 30 分钟)。幂等,注册一次。
        com.github.tvbox.osc.util.SourceHealthMemory.installNetworkWatcher();
        // 线路健康「L1 线路级恢复」的网络监听(2026-10-13,P0-3):网络恢复时复位"弱网期被打成可疑"的线路,
        // 让它们不必从队尾爬起。与源级各自独立注册,避免 util 反向依赖 player 的内部实现。
        com.github.tvbox.osc.player.LineHealthMemory.installNetworkWatcher();
        // 启动看门狗(2026-09-21):装崩溃记录器。必须最早装 —— 第三方爬虫可能在
        // Application.onCreate 之后的任意时刻于自己的线程上闪退,晚了就记不到。
        com.github.tvbox.osc.util.BootGuard.install();
        // 进程级前后台判定(2026-10-17):App 退到桌面即暂停播放,页面与内核都留着,回来由用户手动继续。
        installBackgroundPause();
        // 2026-10-15:把留在 cache 里的下载产物(爬虫 jar、字幕落盘、导入字幕副本)搬到 files/durable。
        // 必须**同步**且赶在任何源加载之前:JarLoader 一开始下载就会在旧目录建新文件,搬晚了等于白搬;
        // 而同分区 rename 只改目录项(几毫秒),不值得为它开一条线程再去讲究时序。
        FileUtils.migrateLegacyCachesToDurable();
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        // 共享缓存容量(第二期扩展):设置项 → player 模块(须在首次 getSharedCache 前注入,改动重启 App 生效)
        ExoMediaSourceHelper.setSharedCacheSizeBytes(
                Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT)) * 1024L * 1024L);
        QuickJSLoader.init();
        // Coil 单例:海报地址约定的请求头注入(Compose UI 图片管线)
        com.github.tvbox.osc.ui.components.VodImages.INSTANCE.init(this);
        // 迅雷缓存清理与 Exo 缓存清理合并到同一条后台线程:
        // cleanPlayerCache 是递归删目录(迅雷下载缓存可能上千个文件),原来直接跑在主线程上,
        // 与紧邻的 purgeExoCacheIfPending 处理方式不一致(后者作者已注释"目录删除属耗时 IO"并放后台)。
        // 两者都只是"启动时清理",不参与任何初始化时序,放后台无副作用。
        // ⚠️ purgeExoCacheIfPending 必须排在前面:它须尽量早于首次 getSharedCache(见其方法注释),
        //    把 cleanPlayerCache 放前面会平白推迟它。
        // ⚠️ 2026-10-22 尝试过在这里预热 Python 运行时,**已撤回**,原因记录在此免得再试:
        //   ① `Python.start(App.this)` 编译不过 —— 形参是 com.chaquo.python.Platform;
        //   ② 换成 `Python.getInstance()` 编译过了,但运行时抛 RuntimeException(未启动);
        //   ③ 正确写法 `Python.start(new AndroidPlatform(app))` 会与
        //      PythonLoader.setApplication 里的 `if (!Python.isStarted())` 形成竞态:
        //      两边同时看到"未启动"就各自 start,一旦撞上,setApplication 会抛
        //      RuntimeException ⇒ 爬虫加载失败 ⇒ 首页空白。
        //   预热失败只省 ~0.5s,换一个"首页可能空白"的风险不划算,故放弃。
        //   另外核实:那 500ms 其实是本地 HTTP(127.0.0.1:9978)取 .py 再落盘,
        //   不是 Python VM 启动 —— 想省它要改取文件的方式,属结构性改动,另行评估。
        new Thread(() -> {
            FileUtils.purgeExoCacheIfPending();
            FileUtils.cleanPlayerCache();
            // 爬虫 jar 只增不减(文件名 = MD5(url)):保留最近 20 个,封住上界。
            FileUtils.pruneSpiderJarCache();
            // 字幕落盘目录(files/durable/zimu/ 与导入副本 subtitle_*)原本是全 App 唯一无上限的一处:
            // 文件名取服务端下发的名字、不会互相覆盖,原本没有任何删除逻辑 ⇒ "看片越多占用越大"。
            // 按 LRU 裁剪到 SubtitleCacheJanitor.MAX_FILES 条,封住上界。
            // ⚠️ 这里不再是 cache:这些是"删了要重新下载/重新挑"的产物,挪去 files/durable 免得被
            // 手机管家当垃圾清掉(见 FileUtils.getDurableDir),上界就得由我们自己守住。
            SubtitleCacheJanitor.trim(FileUtils.getDurableDir());
            // 订阅桶:把老格式(未规范化地址)的台账搬到新桶名,并回收彻底弃用的仓。
            // 冷路径(扫全表键名),所以放这条后台线程,不放主线程。
            SubscriptionBucket.migrateAndReclaim();

        }, "startup-cache-purge").start();
    }

    private void initParams() {
        // KV 存储(2026-09-13 起为唯一实现,取代 Hawk):初始化必须在任何 KV 读写之前
        KV.init(this);
        KV.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2);
        } else {
            int playType = KV.get(HawkConfig.PLAY_TYPE, 2);
            // 0 为非法值、1 为已移除的 IJK 内核 —— 一并归一到 EXO,避免设置页选不出内核
            if (playType == 0 || playType == 1) {
                KV.put(HawkConfig.PLAY_TYPE, 2);
            }
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        // 原生库不可用(32 位设备/缺 so):构造函数里的 native 调用会抛 UnsatisfiedLinkError,
        // 那是 Error 不是 Exception,下面的 catch 接不住 —— 必须在这里先挡住。
        if (!P2PClass.available) return null;
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
