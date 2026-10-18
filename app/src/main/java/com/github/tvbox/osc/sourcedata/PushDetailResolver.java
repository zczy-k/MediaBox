package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;
import android.util.Base64;

import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 详情通道的后处理:push:// 线路当场解析成真实线路、磁力链接交给迅雷解析。
 *
 * <p>两者都会改写详情数据并把结果回投 detailResult,所以由详情/解析通道共用同一个实例。
 */
final class PushDetailResolver {
    private final Gson gson;
    private final MutableLiveData<AbsXml> detailResult;

    PushDetailResolver(Gson gson, MutableLiveData<AbsXml> detailResult) {
        this.gson = gson;
        this.detailResult = detailResult;
    }

    AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        LOG.e("SourceViewModel", e);
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                ExecutorService threadPool = Executors.newSingleThreadExecutor();
                                String finalPushUrl = pushUrl;
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            SourceHelper.siteGet(sb)
                                                    .tag("detail")
                                                    .params("ac","detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
                                                                    resData[0] = absJson.toAbsXml();
                                                                    SourceHelper.absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    LOG.e("SourceViewModel", e);
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                             //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {}.getType());
                                                        resData[0] = absJson.toAbsXml();
                                                        SourceHelper.absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        LOG.e("SourceViewModel", e);
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                LOG.e("SourceViewModel", th);
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    LOG.e("SourceViewModel", e);
                                } finally {
                                    threadPool.shutdown();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = str(R.string.player_parse_failed_prefix, infoBean.name);
                            }
                        }
                    }
                }
            }
        }
        return data;
    }


    void checkThunder(AbsXml data, int index) {
        // 2026-10-16:**磁力/种子通路整条停用**。理由不是"实现不好",是通路本身给不出可用体验:
        // 迅雷 SDK 只给 3 秒取种子元数据(Thunder.java 的 30×100ms 轮询),而 DHT 拿到一份 .torrent
        // 常常要 10~60 秒 ⇒ 云库没命中的(冷门、老片、刚出)几乎必败;更糟的是失败那一路
        // `if (!isParse) playList.add(name + "$" + url)` 会把**裸 magnet 当可播线路**塞回列表,
        // 用户点下去必然报错。手机在 NAT 后、无端口映射、后台被限流,退回 P2P 也补不回这个体验。
        // 现在的做法:不再调 Thunder.parse,把磁力条目**摘掉** —— 该源还有普通线路就照常播;
        // 全是磁力就走既有"空详情"处理(记一次源失败 → 换源),而不是留一条打不开的假线路。
        // 注意字幕那条「迅雷字幕」是另一条通路(直挂字幕文件,不走种子下载),不受影响。
        dropMagnetLines(data);
        if (index == 0) {
            detailResult.postValue(data);
        }
    }

    /**
     * 摘掉详情里所有磁力/迅雷/种子条目,并同步重建该线路的 urls 字符串。
     *
     * <p>两个字段都要改:剧集列表读 beanList,而线路映射(seriesMap)是按 urls 字符串建的 ——
     * 只清一边会留下"看得见却点不到"或"点得到却已失效"的不一致。
     */
    private void dropMagnetLines(AbsXml data) {
        if (data == null || data.movie == null || data.movie.videoList == null) return;
        int dropped = 0;
        for (Movie.Video video : data.movie.videoList) {
            if (video == null || video.urlBean == null || video.urlBean.infoList == null) continue;
            for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                if (urlInfo == null || urlInfo.beanList == null) continue;
                boolean changed = false;
                Iterator<Movie.Video.UrlBean.UrlInfo.InfoBean> it = urlInfo.beanList.iterator();
                while (it.hasNext()) {
                    Movie.Video.UrlBean.UrlInfo.InfoBean bean = it.next();
                    if (bean != null && bean.url != null && Thunder.isSupportUrl(bean.url)) {
                        it.remove();
                        changed = true;
                        dropped++;
                    }
                }
                if (!changed) continue;
                List<String> kept = new ArrayList<>();
                for (Movie.Video.UrlBean.UrlInfo.InfoBean bean : urlInfo.beanList) {
                    if (bean != null) kept.add(bean.name + "$" + bean.url);
                }
                urlInfo.urls = TextUtils.join("#", kept);
            }
        }
        if (dropped > 0) {
            LOG.i("echo-thunder disabled reason=magnet-lines dropped=" + dropped);
        }
    }


    /**
     * 资源文案;走 LanguageManager(Application 的 base 切语言不会重挂,直接 app.getString 会停旧语言);
     * App 未就绪(单测/极早调用)返回空串,不抛异常。
     */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }
}
