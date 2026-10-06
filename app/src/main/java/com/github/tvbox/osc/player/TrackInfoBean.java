package com.github.tvbox.osc.player;

public class TrackInfoBean {
    public int trackId;
    public int renderId;
    public int trackGroupId;
    public String name;
    public String language;
    public int groupIndex;
    public int index;
    public boolean selected;
    public boolean bitmapSubtitle;
    /** 轨道类型(media3 C.TRACK_TYPE_*) */
    public int type;
    /** 轨道指纹(见 TrackMemory):跨集定位只认它 */
    public String formatKey;
    /**
     * 视频轨真实宽高(像素,已按 rotationDegrees 归一为显示方向)。
     *
     * <p>media3 未上报时为 {@code Format.NO_VALUE}(-1),这里归一为 0。
     * 0 的含义是"内核还没报出尺寸",**不能**当作"没有视频画面"的依据。
     */
    public int width;
    public int height;
    /** 平均码率(bits/s);内核未上报为 0 */
    public int bitrate;
    /** MIME 串(avc1.640032 / hvc1.1.6.L153.B0 / av01.…);空=未知 */
    public String codecs = "";
}
