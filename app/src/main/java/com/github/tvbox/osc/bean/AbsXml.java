package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("rss")
public class AbsXml implements Serializable {
    public String sourceKey;
    public String searchToken;
    /** 详情代次(V4):发起详情请求时代入,回包原样带回;非详情通道为 null */
    public Integer detailToken;
    /**
     * 「没拿到可判定的应答」标记(2026-10-14):站点响应了但**解析失败**、或响应体为空。
     *
     * <p>与"源答话说没有这部剧"必须分开:后者是有效应答(该算源活着),前者不是。
     * 旧实现两者都投空 AbsXml ⇒ 搜索页统一判 Empty ⇒ recordAnswered 给权重 +20 并清零连续超时,
     * 于是一个每次崩的源被越搜排名越靠前(审查发现)。见 {@code SourceResultParser.postEmptySearchResult}。
     */
    public boolean parseFailed;

    @XStreamAlias("list")
    public Movie movie;

    @XStreamAlias("msg")
    public String msg;
}
