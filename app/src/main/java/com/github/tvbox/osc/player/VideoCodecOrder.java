package com.github.tvbox.osc.player;

/**
 * 视频解码器名称判定与排序:**纯逻辑**,不碰 media3 实例,可直接 JVM 单测。
 *
 * <p>为什么必须抽出来:这层判据此前直接写在 {@link ExoPlayer} 的选择器里,而比较器参数写反了 ——
 * "硬件优先"实际排成了软件在前,且没有任何测试能发现。判据一旦离开播放器,方向就能被单测锁住。
 *
 * <p>为什么用名称前缀而不是 {@code MediaCodecInfo#isHardwareAccelerated()}:media3 1.11.1 的公开 API
 * 里没有该方法,只能按命名约定判定。
 */
final class VideoCodecOrder {

    private VideoCodecOrder() {
    }

    /**
     * 是否为系统软件解码器。
     *
     * <p>只认固定前缀({@code OMX.google.} / {@code c2.android.} / {@code OMX.ffmpeg.})。
     * **不认识的名称一律按硬解候选处理**:厂商硬解命名五花八门(如 {@code OMX.qcom.} /
     * {@code OMX.MTK.} / {@code c2.qti.}),把它们误判成软解会平白降级,而"没认出来"本身不构成降级依据。
     */
    static boolean isSoftwareCodec(String name) {
        if (name == null) return false;
        return name.startsWith("OMX.google.")
                || name.startsWith("c2.android.")
                || name.startsWith("OMX.ffmpeg.");
    }

    /**
     * 硬解优先的比较键:**负值 = left 应排在 right 前面**。
     *
     * <p>同类之间返回 0,配合 {@code List.sort}(稳定排序)保持上游给的原始顺序 —— 只做"硬解整体前移",
     * 不额外打乱同组次序。
     */
    static int compareHardwareFirst(String leftName, String rightName) {
        return Boolean.compare(isSoftwareCodec(leftName), isSoftwareCodec(rightName));
    }
}
