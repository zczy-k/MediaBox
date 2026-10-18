package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;

import org.json.JSONObject;

/**
 * 站点取数门面:对外只暴露通道 + 入口方法,取数实现按职责分在同包 Loader 里。
 *
 * <p>门面自己只保留跨 Loader 共享的东西:7 个结果通道;homeContent/extend 缓存归
 * {@link SourceRuntimeState},换源清理因此仍有唯一出口(线程池仍在 {@link SourceHelper})。
 *
 * @author pj567
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> actionResult;
    public MutableLiveData<JSONObject> playResult;
    /** 下一集预解析专用通道（预载方案,与 playResult 独立 seq 防串扰,规格 §5.2） */
    public MutableLiveData<JSONObject> preloadResult;

    private final Gson gson;
    private final PushDetailResolver pushDetailResolver;
    private final SourceResultParser resultParser;
    private final SortLoader sortLoader;
    private final ListLoader listLoader;
    private final DetailLoader detailLoader;
    private final SearchLoader searchLoader;
    private final PlayLoader playLoader;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        actionResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        preloadResult = new MutableLiveData<>();
        gson = new Gson();
        pushDetailResolver = new PushDetailResolver(gson, detailResult);
        resultParser = new SourceResultParser(gson, searchResult, detailResult, pushDetailResolver);
        listLoader = new ListLoader(gson, SourceRuntimeState.extendCache, listResult, resultParser);
        sortLoader = new SortLoader(gson, SourceRuntimeState.extendCache, SourceRuntimeState.sortCache, sortResult, listLoader, resultParser);
        detailLoader = new DetailLoader(gson, SourceRuntimeState.extendCache, detailResult, resultParser);
        searchLoader = new SearchLoader(gson, SourceRuntimeState.extendCache, searchResult, resultParser);
        playLoader = new PlayLoader(gson, SourceRuntimeState.extendCache, playResult, preloadResult);
    }

    public void getSort(final String sourceKey) {
        sortLoader.getSort(sourceKey);
    }

    public void getSort(final String sourceKey, final boolean withRec) {
        sortLoader.getSort(sourceKey, withRec);
    }

    public void getList(MovieSort.SortData sortData, int page) {
        listLoader.getList(sortData, page);
    }

    public void getDetail(String sourceKey, String urlid) {
        detailLoader.getDetail(sourceKey, urlid);
    }

    public void getDetail(String sourceKey, String urlid, boolean fallback) {
        detailLoader.getDetail(sourceKey, urlid, fallback);
    }

    /**
     * V4:详情回包带代次(原"换实例"隔离迟到回包的替代)。`requestToken=null` 表示不判代次(老调用点)。
     */
    public void getDetail(String sourceKey, String urlid, boolean fallback, Integer requestToken) {
        detailLoader.getDetail(sourceKey, urlid, fallback, requestToken);
    }

    public void action(String sourceKey, String action) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || action == null) {
            actionResult.postValue(null);
            return;
        }
        if (sourceBean.getType() == 3) {
            SourceHelper.SPIDER_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        String json = sp.action(action);
                        actionResult.postValue(TextUtils.isEmpty(json) ? null : new JSONObject(json));
                    } catch (Throwable th) {
                        LOG.e("SourceViewModel", th);
                        actionResult.postValue(null);
                    }
                }
            });
        } else {
            actionResult.postValue(null);
        }
    }

    public void getSearch(String sourceKey, String wd) {
        searchLoader.getSearch(sourceKey, wd);
    }

    public void getSearch(String sourceKey, String wd, String searchToken) {
        searchLoader.getSearch(sourceKey, wd, searchToken);
    }

    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        playLoader.getPlay(sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    /** 下一集预解析（预载方案）:结果走 preloadResult 通道,seq 独立于真实播放请求,互不作废 */
    public void getPlayForPreload(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        playLoader.getPlayForPreload(sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    public void cancelPlayRequest() {
        playLoader.cancelPlayRequest();
    }

    /** 磁力链接交给迅雷解析改写,结果回投 detailResult */
    public void checkThunder(AbsXml data, int index) {
        pushDetailResolver.checkThunder(data, index);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
