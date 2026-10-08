package com.github.tvbox.osc.event;

/**
 * EventBus 事件类型注册表。
 * 2026-09-13 清理:删除 6 个**全工程零使用**的常量 —— TYPE_PUSH_URL(9)、TYPE_EPG_URL_CHANGE(10)、
 * TYPE_SETTING_SEARCH_TV(11)、TYPE_FILTER_CHANGE(13)、TYPE_LIVE_API_URL_CHANGE(14)、
 * TYPE_HOME_SOURCE_CHANGE(15)(对应功能早已移除,既无 post 也无订阅者)。
 * ⚠️ 保留常量的**数值不要改**:EventBus 按 int 分发,且 9/10/11/13/14/15 已成为空洞,新增事件请用新编号。
 */
public class RefreshEvent {
    public static final int TYPE_REFRESH = 0;
    public static final int TYPE_HISTORY_REFRESH = 1;
    public static final int TYPE_SEARCH_RESULT = 6;
    public static final int TYPE_API_URL_CHANGE = 8;
    public static final int TYPE_SUBTITLE_SIZE_CHANGE = 12;
    public static final int TYPE_SET_DANMU_SETTINGS = 18;
    public static final int TYPE_DANMU_REFRESH = 19;
    public static final int TYPE_PLAY_QUALITY = 20;
    public static final int TYPE_COLLECT_REFRESH = 21;
    /** 播放头真的推进过(即"看过"),由 PlaybackProgress 每集发一次,观看历史据此落库 */
    public static final int TYPE_PLAYBACK_STARTED = 22;
    /** 收藏页栅格列数变更(设置页"收藏页布局"落 KV 后广播,收藏页据此立即换列数) */
    public static final int TYPE_COLLECT_LAYOUT_CHANGE = 23;
    /**
     * 某条线路的**实测画质**已写入 {@code VideoQualityMemory}。
     * 详情页据此重算「线路N · 1080P」标签。
     *
     * <p>**两个发送方**（v1.0.45 起）:
     * <ol>
     *   <li>{@code ResolvedUrlQualityProbe} —— 爬虫型线路在"解析反正要跑"时顺手测;</li>
     *   <li>{@code MusicSessionDelegate.maybeRememberMeasuredQuality} —— 内核实测(起播后
     *       拿到真实宽高)。**这条以前漏发**,导致分辨率 2 秒就拿到了、界面却要等
     *       PlaybackProgress 的 insertVod()(30 秒级节流)才刷新。</li>
     * </ol>
     * 无 obj:关心的是"记忆变了"这个事实本身,具体值由订阅方自己去记忆里读。
     */
    public static final int TYPE_LINE_QUALITY_MEASURED = 24;
    /**
     * 某部影片已被确认"当前无资源"(见 util/AvailabilityMemory),由详情页在**终态空**时发出。
     * 搜索页/首页据此把已加载的那张海报摘掉(方案 B 第二层)。
     *
     * <p>为什么用广播而不是把列表页的回调注入详情页:两者是**各自独立的 ViewModel**,
     * 详情页被回收后回调就丢了,而"用户点空 → 返回列表看到海报还在"恰恰是最需要生效的场景。
     * 两边本来都在 EventBus 上,多一个事件类型比维护反向依赖便宜得多。
     */
    public static final int TYPE_VOD_UNAVAILABLE = 25;

    /**
     * 「被屏蔽的源」集合发生变化(自动封禁触发 / 设置页解除 / 开关切换)。
     *
     * <p>首页据此**本地**重算源清单与卡片可见性(不重新取数:封禁是本地状态变化,
     * 没必要为了它重跑一遍首页请求)。为什么不复用 {@link #TYPE_API_URL_CHANGE}:
     * 那个的语义是"换了点播源地址",订阅方(收藏页/历史页)会跟着做整轮重载 —— 这里只是过滤条件变了。
     *
     * <p>谁发:封禁的**调用方**(搜索/详情 ViewModel、设置页),而不是台账本身 ——
     * 与 {@link #TYPE_VOD_UNAVAILABLE} 同款(标记在 AvailabilityMemory,广播在使用的 ViewModel)。
     */
    public static final int TYPE_SOURCE_BLOCK_CHANGE = 26;
    public int type;
    public Object obj;

    public RefreshEvent(int type) {
        this.type = type;
    }

    public RefreshEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }
}
