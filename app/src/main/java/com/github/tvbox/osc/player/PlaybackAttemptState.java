package com.github.tvbox.osc.player;

import com.github.tvbox.osc.util.EpisodeMatcher;

import java.util.HashSet;
import java.util.Set;

/** 播放尝试/线路/解码/会话标记(纯字段,无 IO/视图/handler 依赖)。 */
final class PlaybackAttemptState {

    // ==================== 重试/尝试状态 ====================

    boolean allowSwitchPlayer = true;

    boolean hasAutoSwitchedPlayer = false;

    int autoSwitchedPlayerType = -1;

    /** 自动"硬解→软解"是否已用过;兼任"用户显式选过解码 ⇒ 本次不再自动回退"的阻断标记,只有确有自动态可回滚时才清除 */
    boolean hasAutoSwitchedDecode = false;

    /** 自动切软解前的 cfg.exo(仅回滚/落库剔除用;null = 不在自动软解态) */
    String autoSwitchedDecodeOld = null;

    /** 自动软解改的解码键(内核只剩 EXO):回滚与落库剔除按它还原 */
    String autoSwitchedDecodeKey = "exo";

    /** "起播后错误"自动重播是否已用过(每轮一次) */
    boolean hasRetriedAfterStart = false;

    /** 本次起播用的是预解析缓存结果(直链可能已过期):失败时允许一次"丢弃并重新取流" */
    boolean usedPreloadedResult = false;

    boolean hasRetriedSameUrlOnBoot = false;

    boolean playbackStarted = false;

    long playTimeoutBasePosition = 0;

    /**
     * 本次取流(取播放地址)的发起时刻({@link android.os.SystemClock#elapsedRealtime()});0 = 未在途。
     *
     * <p>用途:取流结果被接受时算出"这条线路取流等了多久",上报给线路健康台账
     * (见 {@code LineHealthPolicy} 的 resolveMs 与 {@code LineHealthRecorder.recordResolveMs})。
     * 起点在 {@code PlaybackController.startResolvePlayUrlTimeout()} 落笔、终点在结果被接受时消费清零,
     * 因此一次取流只上报一次:陈旧结果不会把已消费的起点再算一遍。
     */
    long resolveStartElapsed = 0L;

    /**
     * 本轮"把地址交给播放器"的时刻(首帧耗时埋点起点,2026-10-15)。
     *
     * <p>{@link #resolveStartElapsed} 量的是**取流拿地址**多久,不含内核 prepare 到第一帧那一段 ——
     * 而"高分辨率线路起播慢"恰恰慢在这段。没有这个数就只能按线路标签猜谁该多等;攒够样本后,
     * 宽限预算可以按这条线的历史首帧耗时自适应。消费式读取(见 {@code markPlaybackStarted})。
     */
    long playArmElapsed = 0L;

    /**
     * 本轮"等起播"的预算**截止时刻**(elapsedRealtime ms;0 = 本轮还没发预算)。
     *
     * <p>用绝对截止而不是"剩余次数":①预算可以按这条线路的历史首帧算出来(见
     * {@code LineHealthPolicy.startBudgetMs});②重复 arm 不再续命 —— 只在第一次发到时算截止时刻,
     * 之后谁再来 arm 都不覆盖。这正是原来用计数器时留下的漏洞:arm 有多个入口,每次发满额度
     * 就等于每次重播都续一次命,"封顶"变成"最长不确定"。
     */
    long switchLineDeadline = 0L;

    /**
     * 本次换线**没有线路本身的坏证据**(2026-10-15 分段归因)。两种来源:
     * ①宽限用尽才放弃、而它的缓冲一直在长 —— 是"我没等到",不是"它死了";
     * ②换线发生时地址根本还没交给内核(卡在站点不答话、本地链路、或我们自己的 m3u8 净化里),
     * 这条线路压根还没被试过。
     *
     * <p>真值时线路侧**只记时刻、不累计连续失败**:当成失败会二次伤害 ——
     * 一条本来好好的线会因为"这个站今天很慢"被越排越后、再也轮不到。
     * 一次性:被 {@code tryNextLine} 读走即清零,免得污染后面的换线。
     */
    boolean switchLineLineFaultUnproven = false;

    final Set<String> triedLineFlags = new HashSet<>();

    long lastRetryTime = 0;

    /** 用户手动点选线路:取流失败/超时不自动换线换源,直接报错停留(避免覆盖用户选择) */
    boolean userPickedLine = false;

    boolean allowAutoSwitchLine = true;

    // ==================== 切换意图(点一次生效一次) ====================

    /** 三态代替原先"reuse/release 两个独立 boolean":REBUILD 覆盖 REUSE,避免两个字段各自为政 */
    enum SwitchIntent { NONE, REUSE, REBUILD }

    SwitchIntent switchIntent = SwitchIntent.NONE;

    /** 换源点击即停:置位后抑制在途取流结果/超时/嗅探回调把已停的旧源拉起 */
    boolean switchStopPending;

    /** 换线/换源前记下的进度(键+毫秒):取流后写进新线路/新源的进度键 */
    String pendingInheritKey;

    long pendingInheritProgress;

    /**
     * 与 {@link #pendingInheritProgress} 配套的**集名**(进度键里含集名,靠它判断这次起播是不是同一集)。
     *
     * <p>为什么必须带集名:位置继承只在"换线/换源但没换集"时才成立。少了这道守卫,
     * "记了位置却没播成 → 用户随手点了下一集"会把上一集的位置写进新一集的键里
     * (表现:点下一集却从上一集中段开始播)。null = 无从判定,放行(与不传集名的调用等价)。
     */
    String pendingInheritEpisode;

    /** 与 {@link #pendingInheritEpisode} 配套的**集槽**(vod.playIndex):集名无从对比时用它兜底,-1 = 未记 */
    int pendingInheritIndex = -1;

    /**
     * 记下"接着看"的位置(换线/换源前唯一入口)。
     *
     * <p>位置取**播放器实时值**优先:内核已释放(自动重试把内核收走了)时实时值读作 0,
     * 回落到已落盘的进度 —— 两个都不为正说明本来就没看过,此时不记(免得把一个 0 值当继承源)。
     */
    void rememberProgressForSwitch(String key, long livePosition, long savedProgress,
                                   String episodeName, int episodeIndex) {
        if (key == null || key.isEmpty()) return;
        long position = livePosition > 0 ? livePosition : savedProgress;
        if (position <= 0) return;
        pendingInheritKey = key;
        pendingInheritProgress = position;
        pendingInheritEpisode = episodeName;
        pendingInheritIndex = episodeIndex;
    }

    /**
     * 待继承的位置是否适用于本次起播。
     *
     * <p>三道判据,从严到宽:
     * <ol>
     *   <li>集名能确认同集(如 `第01集` ↔ `01`)⇒ 放行;</li>
     *   <li>集名**确信是不同集**(两侧都能抽出集号且不同,如 `第1集` ↔ `第2集`)⇒ 拦下(这是换集,该从头播);</li>
     *   <li>其余(任一侧抽不到集号,如 `1` ↔ `HD` 这种跨源命名差异)⇒ 只看**集槽**是否还在同一格:
     *       同格放行、换格拦下。缺集名或集槽未知时一律放行(不因为拿不到信息就丢掉用户的位置)。</li>
     * </ol>
     */
    boolean pendingInheritAppliesTo(String targetEpisode, int targetIndex) {
        if (pendingInheritProgress <= 0 || pendingInheritKey == null || pendingInheritKey.isEmpty()) return false;
        String recorded = pendingInheritEpisode;
        boolean slotKept = pendingInheritIndex < 0 || pendingInheritIndex == targetIndex;
        if (recorded == null || recorded.isEmpty() || targetEpisode == null || targetEpisode.isEmpty()) {
            return slotKept;
        }
        if (EpisodeMatcher.isSameEpisode(recorded, targetEpisode)) return true;
        if (EpisodeMatcher.isDifferentEpisode(recorded, targetEpisode)) return false;
        return slotKept;
    }

    /** 清掉待继承:一次性语义 —— 不认的那一份也不能留到下一次起播 */
    void clearPendingInherit() {
        pendingInheritKey = null;
        pendingInheritProgress = 0;
        pendingInheritEpisode = null;
        pendingInheritIndex = -1;
    }

    // ==================== 会话标记 ====================

    /** 取流/起播期间不更新通知(避免"旧集通知 → 新集"的中间态) */
    boolean switchingPlayback;

    /** 是否维护了媒体会话(有音频轨就维护;见 updateMusicSession) */
    boolean audioPlayback;

    /**
     * 本次会话确认过"纯音频"的粘滞标记:轨道信息读不到时不得让退后台判定翻转成影视;
     * 不得用于封面判定(封面须实时读取,否则影视被压成海报);复位点仅内容边界(见 beginNewPlay/startSession)。
     */
    boolean audioOnlyConfirmed;

    // ==================== 具名转移(每个赋值只在本段出现一次) ====================

    /** 会话边界:清已起播/停播标记与自动软解原值(留着会把上一轮解码方式回填落库;内容边界标记由调用方管) */
    void beginSession() {
        playbackStarted = false;
        switchStopPending = false;
        autoSwitchedDecodeOld = null;
    }

    /**
     * 重试阶梯里最后一次读到的 EXO 错误是网络类:彻底无路可走时据此把"断网 / 线路被墙"与
     * "片源本身失效"分开提示。必须在内容边界复位,否则新一部明明断网却报"片源不可用"。
     */
    boolean lastFailureNetwork;

    /**
     * 本次内容**见到过视频轨**(粘滞,内容边界复位)。
     *
     * <p>为什么需要:内核在 prepare 早期可能只报出音轨,此刻"没有视频轨"是**解析未完成**而非纯音频。
     * 少了这条,纯音频判定会在那个窗口里为真并置上 {@link #audioOnlyConfirmed},详情页随后把普通影视
     * 交接给音乐播放页 —— 外部表现是只有声音、没有画面。
     */
    boolean everHadVideoTrack;

    /**
     * 本次内容的**实测画质是否已写进记忆**(见 {@link VideoQualityMemory})。
     *
     * <p>不复用 {@link #everHadVideoTrack}:那条是"见到过视频轨"就置位,而尺寸要等内核解析完格式才上报。
     * 复用会导致"第一次没读到尺寸就永远不再记",记忆永远是空的、画质优选形同虚设。
     */
    boolean qualityRecorded;

    /**
     * 上一次检查时的已缓冲百分比读数(-1 = 本轮还没基线)。
     * 宽限要的是**增量**:media3 的 bufferedPercentage 是整数粒度,40 分钟的片子 1% ≈ 24 秒缓冲,
     * "活着但极慢"与"冻住"读回同一个数字(真机就停在 14 三拍)—— 只问"大于 0"就分不开这两种。
     */
    int switchLineLastBuffered = -1;

    /**
     * 起播分段的三个时刻(2026-10-15 分段计时,配合 {@link PlayStage}),0 = 还没走到。
     *
     * <p>{@code stageAnsweredAt} = 爬虫的结果被采纳(过期/换源中那些都不算,所以归属是对的);
     * {@code stagePurifyAt} = m3u8 净化开始拉它自己的播放列表;{@code stageKernelAt} = 地址定稿交给内核。
     * 之前只有一把总秒表,超时后无法回答"到底慢在哪一段" ⇒ 既不敢调阈值,也冤枉过线路。
     */
    long stageAnsweredAt = 0L;

    /**
     * 取流请求**发起**的时刻(与 {@link #resolveStartElapsed} 同源,但那个是消费式的、读走就清零)。
     * 分段日志要算"爬虫等了多久",所以必须留一份不被消费的原件。
     */
    long stageRequestedAt = 0L;
    long stagePurifyAt = 0L;
    long stageKernelAt = 0L;

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记 */
    void beginNewPlay() {
        playbackStarted = false;
        lastFailureNetwork = false;
        everHadVideoTrack = false;
        qualityRecorded = false;
        playTimeoutBasePosition = 0;
        switchLineLineFaultUnproven = false;
        stageRequestedAt = 0L;
        stageAnsweredAt = 0L;
        stagePurifyAt = 0L;
        stageKernelAt = 0L;
        // 预算**不在这里发数字**,只作废:截止时刻由 startSwitchLinePlayTimeout 在本轮第一次 arm 时
        // 按这条线路的历史首帧算一次,之后重复 arm 不覆盖(playUrl 与页面桥各调一次、同址重播还再来,
        // 每次重发预算就等于每次续命 —— 那正是上一版用"剩余次数"时留下的漏洞)。
        switchLineDeadline = 0L;
        switchLineLastBuffered = -1;
        resolveStartElapsed = 0L;
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        hasAutoSwitchedDecode = false;
        hasRetriedAfterStart = false;
        hasRetriedSameUrlOnBoot = false;
        usedPreloadedResult = false;
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    void userSelfRescue() {
        hasAutoSwitchedPlayer = false;
        hasRetriedAfterStart = false;
        hasRetriedSameUrlOnBoot = false;
    }

    /** 换源点击即停:清起播标记与复用意图,置"在途结果作废" */
    void stoppedForSourceSwitch() {
        playbackStarted = false;
        playTimeoutBasePosition = 0;
        toggleReuseIntent(false, false);
        switchStopPending = true;
    }

    /** 重试阶梯复位(60s 窗口过期 / 关自动换线):切内核额度 + 已试线路 */
    void resetAutoRetryLadder() {
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        hasRetriedSameUrlOnBoot = false;
        clearTriedLines();
    }

    /**
     * **仅复位"同址重播/切内核"额度,不动已试线路**(2026-10-13 真机修复,问题 17a)。
     *
     * <p>## 为什么必须与 [resetAutoRetryLadder] 分开
     * 原实现在 60 秒窗口过期时调 [resetAutoRetryLadder],它**顺手清空了已试线路**
     * ⇒ 结果:一条 404 死链被 `replay same url` 反复重播、换源链也在清空后**又切回刚失败的线路**
     * (真机取证:千与千寻 `hn.bfvvs.com/...` 死链在 21:25:23/25/27 被反复重播 ≥3 次)。
     *
     * <p>60 秒窗口的本意只是"给同址重播和切内核一次新额度"(吸收瞬时网络抖动),
     * **不该**同时把"这条线路已经试过并失败"的事实抹掉 —— 那是两件事。
     * 线路记忆的正确复位点是 [linesExhausted](换源时)与 [clearTriedLines](切集时)。
     */
    void resetPepLadderKeepLines() {
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        hasRetriedSameUrlOnBoot = false;
        // 刻意不调 clearTriedLines():线路记忆保留到换源/切集
    }

    /** 换线成功:阶梯复位 + 置复用意图(不覆盖 REBUILD:自动切过内核回滚时该意图必须保持) */
    void onLineSwitched() {
        allowSwitchPlayer = true;
        hasAutoSwitchedPlayer = false;
        if (switchIntent != SwitchIntent.REBUILD) switchIntent = SwitchIntent.REUSE;
    }

    /** 无路可走(无剧集数据 / 线路耗尽):清已试线路 */
    void linesExhausted() {
        clearTriedLines();
    }

    /** 清空已尝试线路(切集/换线/关自动换线) */
    void clearTriedLines() {
        triedLineFlags.clear();
    }

    /** 单字段写,另一个意图不动(避免读-改-写冲掉并发改动);REBUILD 不被复用意图覆盖 */
    void setReuseIntent(boolean reuse) {
        if (reuse) {
            if (switchIntent == SwitchIntent.NONE) switchIntent = SwitchIntent.REUSE;
        } else if (switchIntent == SwitchIntent.REUSE) {
            switchIntent = SwitchIntent.NONE;
        }
    }

    void setReleaseIntent(boolean release) {
        if (release) {
            switchIntent = SwitchIntent.REBUILD;
        } else if (switchIntent == SwitchIntent.REBUILD) {
            switchIntent = SwitchIntent.NONE;
        }
    }

    /** 两个意图一起写 */
    void toggleReuseIntent(boolean reuse, boolean release) {
        switchIntent = release ? SwitchIntent.REBUILD : (reuse ? SwitchIntent.REUSE : SwitchIntent.NONE);
    }

    /** 取出并复位(REBUILD 优先:true = 复用) */
    boolean consumeReuseIntent() {
        boolean reuse = switchIntent == SwitchIntent.REUSE;
        switchIntent = SwitchIntent.NONE;
        return reuse;
    }

    /** 清会话标记(退出页面/起播失败/页面销毁三处同集) */
    void clearSessionFlags() {
        switchingPlayback = false;
        audioPlayback = false;
    }
}
