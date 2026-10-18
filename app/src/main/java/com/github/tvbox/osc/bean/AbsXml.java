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

    /**
     * 「详情被摘除磁力后已无任何可播线路」标记(2026-10-18)。
     *
     * <p>磁力在本 App 恒不可播(迅雷任务恒停 TASK_IDLE,见 {@code MagnetSourcePolicy}),
     * 详情侧会把磁力条目摘掉。**若摘完一条不剩**,该源对这部片就等于"交不出内容",
     * 与"源真没这部片"走同一条换源链 —— 但用户看到的空态文案不该一样:
     * 前者要让用户明白"是这个来源只给磁力,已自动跳过",否则会反复重试同一部片。
     *
     * <p>为什么不用 {@link #msg} 携带文案:{@code DetailViewModel.isSourceErrorMsg}
     * 把"非空且非哨兵值"的 msg 一律判成**源侧报错** ⇒ 会弹 toast 并**自动关闭详情页**。
     * 本情形是"我们主动跳过",不是源报错,必须留在页内换源。故用独立布尔标记,
     * 由页面在**空态文案**处按其取词(两条通路互不干扰)。
     */
    public boolean magnetOnlyDropped;

    @XStreamAlias("list")
    public Movie movie;

    @XStreamAlias("msg")
    public String msg;
}
