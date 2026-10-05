package com.github.tvbox.osc.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.bean.VodInfo;

/**
 * 一次播放会话的数据(播放服务化 Spec §2.1,`skill/avbox-playback-service-spec.md`)。
 *
 * <p>背景:改造前页面把"播什么"经两个隐式通道交给播放器 —— `App.getInstance().setVodInfo(vod)`
 * 全局单槽 + `Bundle(sourceKey/userPickedLine)`。全局单槽与"页面可叠加"天然冲突(详情页 B 覆盖
 * 详情页 A 时 A 的播放数据被动改掉),也无法交给服务持有。本类把这两者合并为一个显式对象,
 * 由页面组装、播放侧消费(P0 行为等价:内容与改造前逐字相同)。
 *
 * <p>不可变:构造后不再修改引用;`VodInfo` 内部字段(playFlag/playIndex/playerCfg)仍由播放侧按
 * 既有语义就地修改 —— 与改造前共用同一个对象,行为不变。
 */
public final class PlaybackSession {

    /** 播放数据(与页面 vm.vodInfo 同一对象引用) */
    private final VodInfo vod;
    /** 播放所属源 key(换源后为新源 key) */
    private final String sourceKey;
    /** 用户手动点选线路标记:仅作用于紧接着的一次取流 */
    private final boolean userPickedLine;

    public PlaybackSession(@NonNull VodInfo vod, @Nullable String sourceKey, boolean userPickedLine) {
        this.vod = vod;
        this.sourceKey = sourceKey == null ? "" : sourceKey;
        this.userPickedLine = userPickedLine;
    }

    @NonNull
    public VodInfo vod() {
        return vod;
    }

    @NonNull
    public String sourceKey() {
        return sourceKey;
    }

    public boolean userPickedLine() {
        return userPickedLine;
    }

    /**
     * 播放归属键:源 + 片 + 线路 + 集(播放服务化 Spec D6)。
     * 页面 attach 时用它判断"服务里正在播的是不是我要的这一集"(同键=只接管续播,不同=用户显式换片)。
     */
    public String playbackKey() {
        return sourceKey + "|" + vod.id + "|" + vod.playFlag + "|" + vod.playIndex;
    }
}
