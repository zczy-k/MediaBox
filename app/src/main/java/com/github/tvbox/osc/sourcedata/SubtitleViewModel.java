package com.github.tvbox.osc.sourcedata;

import com.github.tvbox.osc.util.LOG;
import android.text.TextUtils;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.SubtitleData;
import com.github.tvbox.osc.util.SubtitleFilePicker;
import com.github.tvbox.osc.util.SubtitleSearchQuery;
import com.github.tvbox.osc.util.SubtitleSources;
import com.github.tvbox.osc.util.OkGoHelper;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SubtitleViewModel extends ViewModel {

    /** 字幕直链回调（Step 6：从 SearchSubtitleDialog 迁出，供 Compose 版 SubtitleSearchSheet 调用） */
    public interface SubtitleLoader {
        void loadSubtitle(Subtitle subtitle);
    }

    /** 发布页文件列表回调（记忆还原路径用；error = 网络/解析失败） */
    private interface FilesCallback {
        void onFiles(List<Subtitle> files, boolean error);
    }

    public MutableLiveData<SubtitleData> searchResult;

    public SubtitleViewModel() {
        searchResult = new MutableLiveData<>();
    }

    public void searchResult(String title, int page) {
        searchResultFromAssrt(title, page);
    }

    public void getSearchResultSubtitleUrls(Subtitle subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle);
    }

    public void getSubtitleUrl(Subtitle subtitle, SubtitleLoader subtitleLoader) {
        String url = subtitle == null ? null : subtitle.getUrl();
        if (url != null && url.startsWith("xunlei:")) {
            subtitle.setUrl(url.substring("xunlei:".length()));
            subtitleLoader.loadSubtitle(subtitle);
            return;
        }
        if (url != null && url.startsWith(DIRECT_PREFIX)) {
            subtitle.setUrl(url.substring(DIRECT_PREFIX.length()));
            subtitleLoader.loadSubtitle(subtitle);
            return;
        }
        if (url != null && url.startsWith("subtitlecat:")) {
            getSubtitleUrlFromSubtitleCat(subtitle, subtitleLoader);
            return;
        }
        getSubtitleUrlFromAssrt(subtitle, subtitleLoader, null);
    }

    /**
     * 记忆还原路径:在指定发布页里挑出"本集"的文件并解析出直链。
     *
     * <p>不走 {@link #searchResult}:那是面板的列表数据,播放层写进去会与用户正在浏览的面板互相覆盖。
     * 挑文件规则见 {@link com.github.tvbox.osc.util.SubtitleFilePicker};挑不出(不确定是本集)回调 {@code onFailed}。
     */
    public void pickEpisodeSubtitle(String releaseUrl, String episodeName, String fileNameHint,
                                    SubtitleLoader onPicked, Runnable onFailed) {
        if (TextUtils.isEmpty(releaseUrl) || onPicked == null) {
            if (onFailed != null) onFailed.run();
            return;
        }
        Subtitle release = new Subtitle();
        release.setUrl(releaseUrl);
        getSearchResultSubtitleUrlsFromAssrt(release, new FilesCallback() {
            @Override
            public void onFiles(List<Subtitle> files, boolean error) {
                if (error || files == null || files.isEmpty()) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                List<String> names = new ArrayList<>();
                for (Subtitle item : files) names.add(item.getName());
                int index = SubtitleFilePicker.pick(names, episodeName, fileNameHint);
                if (index < 0) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                getSubtitleUrlFromAssrt(files.get(index), onPicked, onFailed);
            }
        });
    }

    private void setSearchListData(List<Subtitle> data, boolean isNew, boolean isZip) {
        try {
            SubtitleData subtitleData = new SubtitleData();
            subtitleData.setSubtitleList(data);
            subtitleData.setIsNew(isNew);
            subtitleData.setIsZip(isZip);
            searchResult.postValue(subtitleData);
        } catch (Throwable e) {
            LOG.e("SubtitleViewModel", e);
            searchResult.postValue(null);
        }
    }

    private volatile int pagesTotal = -1;
    private volatile int searchSequence = 0;

    private static final class SubtitleSearchBatch {
        final int sequence;
        final int page;
        final String originalTitle;
        final Map<String, Subtitle> results = new LinkedHashMap<>();
        int remaining;
        int successfulRequests;
        int pagesTotal = -1;

        SubtitleSearchBatch(int sequence, int page, String originalTitle, int count) {
            this.sequence = sequence;
            this.page = page;
            this.originalTitle = originalTitle;
            this.remaining = count;
        }
    }

    private void searchResultFromAssrt(String title, int page) {
        try {
            if (page == 1) {
                pagesTotal = -1;
                searchSequence++;
            } else if (pagesTotal > 0 && page > pagesTotal) {
                setSearchListData(new ArrayList<>(), false, true);
                return;
            }
            final int sequence = searchSequence;
            // 内置源按用户在设置里的开关参与;自定义源始终参与。逐个源独立失败,全部回包后合并成一页。
            List<String> assrtQueries = SubtitleSources.INSTANCE.isEnabled(SubtitleSources.KEY_ASSRT)
                    ? SubtitleSearchQuery.buildQueries(title) : new ArrayList<>();
            boolean subtitleCatOn = SubtitleSources.INSTANCE.isEnabled(SubtitleSources.KEY_SUBTITLECAT);
            boolean xunleiOn = SubtitleSources.INSTANCE.isEnabled(SubtitleSources.KEY_XUNLEI);
            List<SubtitleSources.Custom> customs = SubtitleSources.INSTANCE.custom();
            int requestCount = assrtQueries.size() + (subtitleCatOn ? 1 : 0) + (xunleiOn ? 1 : 0) + customs.size();
            if (requestCount == 0) {
                setSearchListData(new ArrayList<>(), page <= 1, true);
                return;
            }

            SubtitleSearchBatch batch = new SubtitleSearchBatch(sequence, page, title, requestCount);
            String searchApiUrl = "https://secure.assrt.net/sub/";
            for (String query : assrtQueries) {
                OkGo.<String>get(searchApiUrl)
                        .params("searchword", query)
                        .params("sort", "rank")
                        .params("page", page)
                        .params("no_redir", "1")
                        .execute(new AbsCallback<String>() {
                            @Override
                            public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                                try {
                                    collectSearchVariant(batch, query, response.body(), true);
                                } catch (Throwable th) {
                                    LOG.e("SubtitleViewModel", th);
                                    collectSearchVariant(batch, query, null, false);
                                }
                            }

                            @Override
                            public String convertResponse(Response response) throws Throwable {
                                return response.body().string();
                            }

                            @Override
                            public void onError(com.lzy.okgo.model.Response<String> response) {
                                super.onError(response);
                                collectSearchVariant(batch, query, null, false);
                            }
                        });
            }
            String providerTitle = SubtitleSearchQuery.providerTitle(title);
            if (subtitleCatOn) searchSubtitleCat(batch, providerTitle);
            if (xunleiOn) searchXunlei(batch, providerTitle);
            for (SubtitleSources.Custom custom : customs) {
                searchCustomSource(batch, custom, providerTitle);
            }
        } catch (Exception e) {
            LOG.e("SubtitleViewModel", e);
            setSearchListData(null, page <= 1, true);
        }
    }

    private void collectSearchVariant(SubtitleSearchBatch batch, String query, String body, boolean success) {
        List<Subtitle> found = new ArrayList<>();
        int resultPages = -1;
        if (success && !TextUtils.isEmpty(body)) {
            try {
                Document doc = Jsoup.parse(body);
                Elements items = doc.select(".resultcard .sublist_box_title a.introtitle");
                for (Element item : items) {
                    String subtitleTitle = item.attr("title");
                    if (TextUtils.isEmpty(subtitleTitle)) subtitleTitle = item.text();
                    String href = item.attr("href");
                    if (TextUtils.isEmpty(href) || !SubtitleSearchQuery.matches(subtitleTitle, query)) continue;
                    Subtitle one = new Subtitle();
                    one.setName(subtitleTitle.trim());
                    one.setUrl(href.startsWith("http") ? href : "https://assrt.net" + href);
                    one.setIsZip(true);
                    found.add(one);
                }
                Elements pages = doc.select(".pagelinkcard a");
                if (pages.size() > 0) {
                    String[] parts = pages.last().text().split("/", 2);
                    if (parts.length == 2) resultPages = Integer.parseInt(parts[1].trim());
                }
            } catch (Throwable th) {
                LOG.e("SubtitleViewModel", th);
                success = false;
            }
        }
        completeSearchRequest(batch, success, found, resultPages);
    }

    private void completeSearchRequest(SubtitleSearchBatch batch, boolean success,
                                       List<Subtitle> found, int resultPages) {
        synchronized (batch) {
            if (success) batch.successfulRequests++;
            for (Subtitle subtitle : found) {
                if (subtitle != null && !TextUtils.isEmpty(subtitle.getUrl())) {
                    batch.results.put(subtitle.getUrl(), subtitle);
                }
            }
            if (resultPages > 0) batch.pagesTotal = Math.max(batch.pagesTotal, resultPages);
            batch.remaining--;
            if (batch.remaining != 0 || batch.sequence != searchSequence) return;
            if (batch.successfulRequests == 0) {
                setSearchListData(null, batch.page <= 1, true);
                return;
            }
            List<Subtitle> merged = new ArrayList<>(batch.results.values());
            Collections.sort(merged, (left, right) -> Integer.compare(
                    SubtitleSearchQuery.relevance(right.getName(), batch.originalTitle),
                    SubtitleSearchQuery.relevance(left.getName(), batch.originalTitle)));
            if (batch.pagesTotal > 0) pagesTotal = Math.max(pagesTotal, batch.pagesTotal);
            setSearchListData(merged, batch.page <= 1, true);
        }
    }

    /**
     * SubtitleCat and Xunlei provider integration follows the public protocol documented by
     * https://github.com/736368217/PotplayerJavChineseSubs_subtitlecat (MIT License;
     * Copyright (c) 2024 Exhen & Contributors); implemented independently for MediaBox's Subtitle model.
     */
    private static final String SUBTITLECAT_PREFIX = "subtitlecat:";
    private static final String XUNLEI_PREFIX = "xunlei:";
    /** 自定义源返回的是字幕文件直链,标记后跳过 assrt 的 302 解析直接加载 */
    private static final String DIRECT_PREFIX = "direct:";
    private static final String SUBTITLECAT_SITE = "https://www.subtitlecat.com";
    private static final String XUNLEI_API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle";

    private void searchSubtitleCat(SubtitleSearchBatch batch, String title) {
        if (TextUtils.isEmpty(title)) {
            completeSearchRequest(batch, false, new ArrayList<>(), -1);
            return;
        }
        String url = SUBTITLECAT_SITE + "/index.php?search=" + android.net.Uri.encode(title);
        OkGo.<String>get(url)
                .headers("User-Agent", "MediaBox Subtitle Search/1.0")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        try {
                            Document doc = Jsoup.parse(response.body(), SUBTITLECAT_SITE + "/");
                            Elements links = doc.select("a[href^=subs/]");
                            List<Subtitle> found = new ArrayList<>();
                            for (Element link : links) {
                                String name = link.text().trim();
                                String detailUrl = link.absUrl("href");
                                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(detailUrl)) continue;
                                if (!SubtitleSearchQuery.matches(name, title)) continue;
                                Subtitle subtitle = new Subtitle();
                                subtitle.setName(name + "（SubtitleCat）");
                                subtitle.setUrl(SUBTITLECAT_PREFIX + detailUrl);
                                subtitle.setIsZip(false);
                                found.add(subtitle);
                                if (found.size() >= 20) break;
                            }
                            completeSearchRequest(batch, true, found, -1);
                        } catch (Throwable th) {
                            LOG.e("SubtitleViewModel", th);
                            completeSearchRequest(batch, false, new ArrayList<>(), -1);
                        }
                    }

                    @Override
                    public String convertResponse(Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<String> response) {
                        super.onError(response);
                        completeSearchRequest(batch, false, new ArrayList<>(), -1);
                    }
                });
    }

    private void searchXunlei(SubtitleSearchBatch batch, String title) {
        if (TextUtils.isEmpty(title)) {
            completeSearchRequest(batch, false, new ArrayList<>(), -1);
            return;
        }
        String url = XUNLEI_API + "?gcid=&cid=&name=" + android.net.Uri.encode(title);
        OkGo.<String>get(url)
                .headers("User-Agent", "MediaBox Subtitle Search/1.0")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        try {
                            org.json.JSONArray data = new org.json.JSONObject(response.body()).optJSONArray("data");
                            List<Subtitle> found = new ArrayList<>();
                            if (data != null) {
                                for (int i = 0; i < data.length() && i < 20; i++) {
                                    org.json.JSONObject item = data.optJSONObject(i);
                                    if (item == null) continue;
                                    String downloadUrl = item.optString("url", "").trim();
                                    String format = item.optString("ext", "").trim();
                                    if (format.startsWith(".")) format = format.substring(1);
                                    if (TextUtils.isEmpty(downloadUrl) || !isSupportedSubtitleFile("subtitle." + format)) continue;
                                    String name = item.optString("name", "").trim();
                                    if (TextUtils.isEmpty(name)) name = title;
                                    Subtitle subtitle = new Subtitle();
                                    subtitle.setName(name + "（迅雷）");
                                    subtitle.setUrl(XUNLEI_PREFIX + downloadUrl);
                                    subtitle.setIsZip(false);
                                    found.add(subtitle);
                                }
                            }
                            completeSearchRequest(batch, true, found, -1);
                        } catch (Throwable th) {
                            LOG.e("SubtitleViewModel", th);
                            completeSearchRequest(batch, false, new ArrayList<>(), -1);
                        }
                    }

                    @Override
                    public String convertResponse(Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<String> response) {
                        super.onError(response);
                        completeSearchRequest(batch, false, new ArrayList<>(), -1);
                    }
                });
    }

    /** 自定义源:URL 模板里把 {kw} 换成编码后的关键词,响应按标准 JSON 解析出直链。 */
    private void searchCustomSource(SubtitleSearchBatch batch, SubtitleSources.Custom custom, String title) {
        String template = custom.getUrlTemplate();
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(template)) {
            completeSearchRequest(batch, false, new ArrayList<>(), -1);
            return;
        }
        String url = template.replace("{kw}", android.net.Uri.encode(title));
        OkGo.<String>get(url)
                .headers("User-Agent", "MediaBox Subtitle Search/1.0")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        try {
                            completeSearchRequest(batch, true, parseCustomResults(custom, response.body()), -1);
                        } catch (Throwable th) {
                            LOG.e("SubtitleViewModel", th);
                            completeSearchRequest(batch, false, new ArrayList<>(), -1);
                        }
                    }

                    @Override
                    public String convertResponse(Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<String> response) {
                        super.onError(response);
                        completeSearchRequest(batch, false, new ArrayList<>(), -1);
                    }
                });
    }

    /** 兼容常见的三种形状:顶层数组、{list:[…]}、{data:[…]} / {subtitles:[…]};字段名也做别名兜底。 */
    private List<Subtitle> parseCustomResults(SubtitleSources.Custom custom, String body) {
        List<Subtitle> found = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return found;
        org.json.JSONArray array = null;
        try {
            String text = body.trim();
            if (text.startsWith("[")) {
                array = new org.json.JSONArray(text);
            } else if (text.startsWith("{")) {
                org.json.JSONObject obj = new org.json.JSONObject(text);
                array = obj.optJSONArray("list");
                if (array == null) array = obj.optJSONArray("data");
                if (array == null) array = obj.optJSONArray("subtitles");
            }
        } catch (Throwable th) {
            LOG.e("SubtitleViewModel", th);
        }
        if (array == null) return found;
        for (int i = 0; i < array.length() && found.size() < 20; i++) {
            org.json.JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String url = firstNonEmpty(item, "url", "link", "download");
            if (TextUtils.isEmpty(url)) continue;
            String name = firstNonEmpty(item, "name", "title", "label");
            Subtitle subtitle = new Subtitle();
            subtitle.setName((name.isEmpty() ? custom.getName() : name) + "（" + custom.getName() + "）");
            subtitle.setUrl(DIRECT_PREFIX + url);
            subtitle.setIsZip(false);
            found.add(subtitle);
        }
        return found;
    }

    private static String firstNonEmpty(org.json.JSONObject obj, String... keys) {
        for (String key : keys) {
            String value = obj.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private void getSubtitleUrlFromSubtitleCat(Subtitle subtitle, SubtitleLoader subtitleLoader) {
        String detailUrl = subtitle.getUrl().substring(SUBTITLECAT_PREFIX.length());
        OkGo.<String>get(detailUrl)
                .headers("User-Agent", "MediaBox Subtitle Search/1.0")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        try {
                            Document doc = Jsoup.parse(response.body(), detailUrl);
                            String chinese = null;
                            String fallback = null;
                            for (Element link : doc.select("a[href]")) {
                                String href = link.absUrl("href");
                                String lower = href.toLowerCase(Locale.ROOT);
                                int query = lower.indexOf('?');
                                if (query >= 0) lower = lower.substring(0, query);
                                if (!lower.endsWith(".srt")) continue;
                                if (lower.contains("-zh-cn")) {
                                    chinese = href;
                                    break;
                                }
                                if (chinese == null && lower.contains("-zh-tw")) chinese = href;
                                if (fallback == null) fallback = href;
                            }
                            String resolved = chinese != null ? chinese : fallback;
                            if (resolved != null) {
                                subtitle.setUrl(resolved);
                                subtitleLoader.loadSubtitle(subtitle);
                            } else {
                                subtitle.setUrl(null);
                                subtitleLoader.loadSubtitle(subtitle);
                            }
                        } catch (Throwable th) {
                            LOG.e("SubtitleViewModel", th);
                            subtitle.setUrl(null);
                            subtitleLoader.loadSubtitle(subtitle);
                        }
                    }

                    @Override
                    public String convertResponse(Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<String> response) {
                        super.onError(response);
                        subtitle.setUrl(null);
                        subtitleLoader.loadSubtitle(subtitle);
                    }
                });
    }

    Pattern regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)");

    private void getSearchResultSubtitleUrlsFromAssrt(Subtitle subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle, new FilesCallback() {
            @Override
            public void onFiles(List<Subtitle> files, boolean error) {
                setSearchListData(files, true, error);
            }
        });
    }

    private void getSearchResultSubtitleUrlsFromAssrt(Subtitle subtitle, FilesCallback callback) {
        try {
            String url = subtitle.getUrl();
            OkGo.<String>get(url).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                    try {
                        String content = response.body();
                        List<Subtitle> data = new ArrayList<>();
                        Document doc = Jsoup.parse(content);
                        Elements items = doc.select("#detail-filelist .waves-effect");
                        if (items.size() > 0) {//压缩包里面的字幕
                            for (Element item : items) {
                                String onclick = item.attr("onclick");
                                if (TextUtils.isEmpty(onclick)) continue;
                                Matcher matcher = regexShooterFileOnclick.matcher(onclick);
                                if (matcher.find()) {
                                    String fileName = matcher.group(3);
                                    if (!isSupportedSubtitleFile(fileName)) continue;
                                    String url = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3));
                                    Subtitle one = new Subtitle();
                                    Element name = item.selectFirst("#filelist-name");
                                    one.setName(name == null ? fileName : name.text());
                                    one.setUrl(url);
                                    one.setIsZip(false);
                                    data.add(one);
                                }
                            }
                            callback.onFiles(data, false);
                        } else {//有的字幕 不一定是压缩包
                            Element item = doc.selectFirst(".download a#btn_download");
                            if (item == null) {
                                callback.onFiles(null, false);
                                return;
                            }
                            String href = item.attr("href");
                            if (TextUtils.isEmpty(href)) {
                                callback.onFiles(null, false);
                                return;
                            }
                            if (isSupportedSubtitleFile(href)) {
                                String url = "https://assrt.net" + href;
                                Subtitle one = new Subtitle();
                                String title = href.substring(href.lastIndexOf("/") + 1);
                                one.setName(URLDecoder.decode(title));
                                one.setUrl(url);
                                one.setIsZip(false);
                                data.add(one);
                                callback.onFiles(data, false);
                            } else {
                                callback.onFiles(null, false);
                            }
                        }
                    } catch (Throwable th) {
                        LOG.e("SubtitleViewModel", th);
                        callback.onFiles(null, true);
                    }
                }

                @Override
                public String convertResponse(Response response) throws Throwable {
                    return response.body().string();
                }

                @Override
                public void onError(com.lzy.okgo.model.Response<String> response) {
                    super.onError(response);
                    callback.onFiles(null, true);
                }
            });
        } catch (Exception e) {
            LOG.e("SubtitleViewModel", e);
            callback.onFiles(null, true);
        }
    }

    private boolean containsSearchWord(String subtitleTitle, String searchWord) {
        if (TextUtils.isEmpty(subtitleTitle) || TextUtils.isEmpty(searchWord)) return false;
        return subtitleTitle.toLowerCase(Locale.ROOT).contains(searchWord.toLowerCase(Locale.ROOT));
    }

    private boolean isSupportedSubtitleFile(String fileName) {
        if (TextUtils.isEmpty(fileName)) return false;
        String candidate = fileName.trim();
        int query = candidate.indexOf('?');
        if (query >= 0) candidate = candidate.substring(0, query);
        int fragment = candidate.indexOf('#');
        if (fragment >= 0) candidate = candidate.substring(0, fragment);
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf(92));
        if (slash >= 0) candidate = candidate.substring(slash + 1);
        try {
            candidate = URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            // Use the undecoded basename when a provider returns malformed escaping.
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return lower.endsWith(".srt")
                || lower.endsWith(".ass")
                || lower.endsWith(".ssa")
                || lower.endsWith(".stl")
                || lower.endsWith(".scc")
                || lower.endsWith(".ttml");
    }

    /**
     * 解析字幕直链(assrt 下载链是 302,直链在 Location 头里)。
     *
     * <p>{@code onFailed} 只有记忆还原路径传:拿不到直链必须能回落默认字幕链,否则该片会一直没字幕
     * (面板路径不传 —— 选不到字幕由用户自己重选,不需要回落)。
     */
    private void getSubtitleUrlFromAssrt(Subtitle subtitle, SubtitleLoader subtitleLoader, Runnable onFailed) {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        Request request = new Request.Builder()
                .url(subtitle.getUrl())
                .get()
                .addHeader("Referer", "https://secure.assrt.net")
                .addHeader("User-Agent", ua)
                .build();
        OkHttpClient base = OkGoHelper.getDefaultClient();
        OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder().proxySelector(OkGoHelper.proxySelector()).proxyAuthenticator(OkGoHelper.proxyAuthenticator());
        builder.readTimeout(15, TimeUnit.SECONDS);
        builder.writeTimeout(15, TimeUnit.SECONDS);
        builder.connectTimeout(15, TimeUnit.SECONDS);
        builder.followRedirects(false);
        builder.followSslRedirects(false);
        builder.retryOnConnectionFailure(true);
        OkHttpClient client = builder.build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOG.e("SubtitleViewModel", e);
                if (onFailed != null) onFailed.run();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String location = response.header("location");
                if (TextUtils.isEmpty(location)) {
                    if (onFailed != null) onFailed.run();
                    return;
                }
                subtitle.setUrl(location);
                subtitleLoader.loadSubtitle(subtitle);
            }
        });
    }

}
