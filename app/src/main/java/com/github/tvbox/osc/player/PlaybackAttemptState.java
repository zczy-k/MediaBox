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

    /**
     * 记下"接着看"的位置(换线/换源前唯一入口)。
     *
     * <p>位置取**播放器实时值**优先:内核已释放(自动重试把内核收走了)时实时值读作 0,
     * 回落到已落盘的进度 —— 两个都不为正说明本来就没看过,此时不记(免得把一个 0 值当继承源)。
     */
    void rememberProgressForSwitch(String key, long livePosition, long savedProgress, String episodeName) {
        if (key == null || key.isEmpty()) return;
        long position = livePosition > 0 ? livePosition : savedProgress;
        if (position <= 0) return;
        pendingInheritKey = key;
        pendingInheritProgress = position;
        pendingInheritEpisode = episodeName;
    }

    /**
     * 待继承的位置是否适用于本次起播(= 记下的那一段与 {@code targetEpisode} 是同一集)。
     * 集名任一侧为空 ⇒ 无从判定,放行(不因为拿不到集名就丢掉用户的位置)。
     */
    boolean pendingInheritAppliesTo(String targetEpisode) {
        if (pendingInheritProgress <= 0 || pendingInheritKey == null || pendingInheritKey.isEmpty()) return false;
        String recorded = pendingInheritEpisode;
        if (recorded == null || recorded.isEmpty()) return true;
        if (targetEpisode == null || targetEpisode.isEmpty()) return true;
        return EpisodeMatcher.isSameEpisode(recorded, targetEpisode);
    }

    /** 清掉待继承:一次性语义 —— 不认的那一份也不能留到下一次起播 */
    void clearPendingInherit() {
        pendingInheritKey = null;
        pendingInheritProgress = 0;
        pendingInheritEpisode = null;
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

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记 */
    void beginNewPlay() {
        playbackStarted = false;
        lastFailureNetwork = false;
        everHadVideoTrack = false;
        qualityRecorded = false;
        playTimeoutBasePosition = 0;
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
