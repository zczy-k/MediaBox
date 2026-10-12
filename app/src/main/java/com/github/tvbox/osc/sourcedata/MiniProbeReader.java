package com.github.tvbox.osc.sourcedata;

import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.TitleMatcher;
import com.google.gson.Gson;
import com.lzy.okgo.request.GetRequest;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * **迷你只读详情通道**(P1):只为"提前知道对端画质"取一次详情,**绝不投递任何 UI 通道**。
 *
 * <p>## 为什么要单独一条通道
 * 复用现有详情链路({@code SourceViewModel.getDetail})会走 {@code detailResult} 通道,必然触发
 * 页面状态机(清候选、改 vodInfo),把"后台探测"变成"用户在换源"。所以这里自己做**同步取数 + 纯解析**,
 * 结果只回给调用方,一个字节都不进页面的 LiveData。
 *
 * <p>## ⚠️ 为什么给解析器传三个**独立**的哑 LiveData,而不是传 null
 * {@link SourceResultParser} 的分投判据是身份比较 {@code if (searchResult == result)}。
 * 若把 {@code result} 传 null,而该解析器实例的 {@code searchResult} 字段**也是 null**,
 * 则 {@code null == null} 成立 ⇒ 详情回包会被当成**搜索结果**投到 EventBus 上,污染搜索面板。
 * 传三个各自独立、且都不等于 null 的哑实例,可以让身份判据全部落空:
 * 回包只会走 {@code result.postValue(...)} 落到一个**无人观察**的 LiveData 上,无任何副作用。
 *
 * <p>## 取数口径
 * 与 {@code DetailLoader.getDetailFromApi} **同源**:同 {@code siteGet}(带站点级 header)、
 * 同 {@code ac}=videolist/detail、同 {@code ids}、同 extend 解析。刻意不手搓 URL ——
 * extend 常是一大坨 JSON,手工拼接会漏掉 URL 编码。
 *
 * <p>## 线程
 * 同步阻塞({@code BoundedCall} 包一层真实超时)。**必须在后台线程调用**,不能在主线程。
 */
public final class MiniProbeReader {

    private MiniProbeReader() {
    }

    /**
     * 取回某源详情里与 {@code title} 同名的影片条目。
     *
     * @param bean     目标源(调用方已按 MiniProbeGate 过滤:type 3 / 索引型 / 冷 extend 不会进来)
     * @param vodId    该源里这部片的 id(来自搜索候选)
     * @param title    片名,用于在详情返回的列表里锁定正确条目
     * @param timeoutSeconds 请求超时(秒),内部转毫秒交给 BoundedCall
     * @param outFlag  出参:命中条目里**第一个可播线路的 flag**(便于调用方按线路探测)
     * @return 命中的影片;取不到 / 解析失败 / 不同名一律 null
     */
    public static Movie.Video fetchVideo(SourceBean bean, final String vodId, String title,
                                         long timeoutSeconds, String[] outFlag) {
        if (bean == null || vodId == null || vodId.isEmpty()) return null;

        final int type = bean.getType();
        // 与 DetailLoader 同口径:type 0 走 videolist,其余(type 1/4)走 detail
        final String ac = type == 0 ? "videolist" : "detail";
        final String sourceKey = bean.getKey();
        // 调用方已在后台线程 ⇒ 用 Direct 版本,避免再占 EXTEND_POOL 的一个线程
        final String extend = SourceHelper.getFixUrlDirect(
                SourceRuntimeState.extendCache, new Gson(), bean.getExt());

        String body = BoundedCall.call(new Callable<String>() {
            @Override
            public String call() throws Exception {
                GetRequest<String> request = SourceHelper.siteGet(bean)
                        .params("ac", ac)
                        .params("ids", vodId);
                if (extend != null && !extend.isEmpty()) {
                    request.params("extend", extend);
                }
                // ⚠️ OkGo 的 `execute()` 返回的是 **okhttp3.Response**,不是
                // `com.lzy.okgo.model.Response<String>`(那是**异步**回调 onSuccess 的参数类型)。
                // 本仓既有写法见 JsLoader:123 `Response response = OkGo.<File>get(jar).execute();`。
                // 踩坑记录:CI 报 "incompatible types: okhttp3.Response cannot be converted to
                // com.lzy.okgo.model.Response<String>" —— 两者同名,极易写错。
                okhttp3.Response response = request.execute();
                return response.body() != null ? response.body().string() : "";
            }
        }, Math.max(1L, timeoutSeconds) * 1000L, "xhint-fetch-" + sourceKey);

        if (body == null || body.trim().isEmpty()) {
            LOG.i("xhint-probe empty-body key=" + sourceKey);
            return null;
        }

        AbsXml data = parsePurely(type, body, sourceKey);
        if (data == null || data.movie == null || data.movie.videoList == null
                || data.movie.videoList.isEmpty()) {
            return null;
        }

        Movie.Video hit = pickByTitle(data.movie.videoList, title);
        if (hit == null || hit.urlBean == null || hit.urlBean.infoList == null
                || hit.urlBean.infoList.isEmpty()) {
            return null;
        }
        if (outFlag != null && outFlag.length > 0) {
            for (Movie.Video.UrlBean.UrlInfo info : hit.urlBean.infoList) {
                if (info != null && info.flag != null && !info.flag.isEmpty()
                        && info.beanList != null && !info.beanList.isEmpty()) {
                    outFlag[0] = info.flag;
                    break;
                }
            }
        }
        return hit;
    }

    /**
     * 纯解析:三个哑通道互不相同 ⇒ 身份判据全部落空 ⇒ 只 postValue 到无人观察的 LiveData。
     *
     * <p>pushDetailResolver 传 null 是安全的:它只在 {@code result == detailResult} 时被调用,
     * 而此处 detailResult 是另一个独立实例,该分支永不成立。
     */
    private static AbsXml parsePurely(int type, String body, String sourceKey) {
        try {
            MutableLiveData<AbsXml> searchSink = new MutableLiveData<>();
            MutableLiveData<AbsXml> detailSink = new MutableLiveData<>();
            MutableLiveData<AbsXml> resultSink = new MutableLiveData<>();
            SourceResultParser parser = new SourceResultParser(new Gson(), searchSink, detailSink, null);
            if (type == 0) {
                return parser.xml(resultSink, body, sourceKey, "", null);
            }
            return parser.json(resultSink, body, sourceKey, "", null);
        } catch (Throwable th) {
            LOG.i("xhint-probe parse-fail key=" + sourceKey + " err=" + th.getMessage());
            return null;
        }
    }

    /**
     * 在结果列表里按片名锁定条目。
     *
     * <p>先用 {@link TitleMatcher} 同名匹配(与详情页候选装配同一套口径);
     * 接口只回一条时不挑 —— 很多站点 {@code ids=} 精确查询本身就只回目标片。
     * 多于一条且都不匹配时返回 null:**宁可探不到,也不拿别的片的地址去测画质**
     * (测错片的画质会把提示指向错误结论,比没有提示更糟)。
     */
    private static Movie.Video pickByTitle(List<Movie.Video> list, String title) {
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        if (title == null || title.isEmpty()) return list.get(0);
        for (Movie.Video v : list) {
            if (v != null && TitleMatcher.INSTANCE.isSameTitle(v.name, title)) return v;
        }
        return null;
    }
}
