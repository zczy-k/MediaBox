package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

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

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 下层(util/data/server/catvod)读 Context 一律走这里,不再反向依赖 App 类
        AppContextHolder.install(this);
        // 启动看门狗(2026-09-21):装崩溃记录器。必须最早装 —— 第三方爬虫可能在
        // Application.onCreate 之后的任意时刻于自己的线程上闪退,晚了就记不到。
        com.github.tvbox.osc.util.BootGuard.install();
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
        new Thread(() -> {
            FileUtils.purgeExoCacheIfPending();
            FileUtils.cleanPlayerCache();
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
