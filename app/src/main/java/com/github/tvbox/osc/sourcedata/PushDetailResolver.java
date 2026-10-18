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
        // 2026-10-18:**磁力/种子通路恢复**(此前 2026-10-16 由 d719b108 整条停用)。
        //
        // 恢复的前提是三个缺陷已修(缺一不可,原停用理由正是它们):
        //   ① 失败时把裸 magnet 塞回列表 ⇒ 已改为丢弃并记 `dropped-unplayable`;
        //   ② `case 1/4/2` 合并处理导致"下载中"也交地址(边下边播)⇒ 已拆开,只认 case 2;
        //   ③ 无进度回调 ⇒ `status(code>=0, ...)` 现在会回报"正在下载 Ns"。
        // 另把播放等待窗口从 30s 放宽到 180s —— 30s 对"下载完才能播"必然超时。
        //
        // 注意:字幕那条「迅雷字幕」是另一条通路(直挂字幕文件,不走种子下载),不受影响。
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder = false;
                thunderLoop:
                for (int idx = 0; idx < video.urlBean.infoList.size(); idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    if (urlInfo == null || urlInfo.beanList == null) continue;
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if (infoBean != null && infoBean.url != null && Thunder.isSupportUrl(infoBean.url)) {
                            hasThunder = true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                // 进度(修复 ③):非错误码,只记日志,不污染首集名
                                LOG.i("echo-thunder progress " + info);
                            } else {
                                // 这个回调在线程池线程上跑,越界/空指针会直接崩进程:源结构不完整时只放弃改首集名
                                Movie.Video.UrlBean.UrlInfo first = video.urlBean.infoList.isEmpty() ? null : video.urlBean.infoList.get(0);
                                if (first != null && first.beanList != null && !first.beanList.isEmpty()) {
                                    first.beanList.get(0).name = info;
                                }
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                if (key < 0 || key >= video.urlBean.infoList.size()) continue;
                                Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(key);
                                if (urlInfo == null) continue;
                                String playList = urlMap.get(key);
                                urlInfo.urls = playList;
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                // ⚠️ 2026-10-18:空串 = "这一路磁力全部不可播",必须是**零条线路**。
                                // 不能往下走 split:那样会造出 url 为空的假线路(点了必报错)。
                                if (TextUtils.isEmpty(playList)) {
                                    urlInfo.beanList = infoBeanList;
                                    continue;
                                }
                                String[] str = playList.split("#");
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$", 2);
                                        if (ss.length >= 2) {
                                            infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                        } else if (ss.length == 1) {
                                            infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                        }
                                    }
                                }
                                urlInfo.beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {
                            // 详情侧不接管起播:地址由播放侧按线路点击时现取(见 PlaybackController)
                        }
                    });
                }
            }
        }
        if (!thunderParse && index == 0) {
            detailResult.postValue(data);
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
