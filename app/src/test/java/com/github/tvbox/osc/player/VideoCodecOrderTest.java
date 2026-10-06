package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * 解码器排序口径单测。
 *
 * <p>锁的是**方向**:硬件解码器必须排在已知软件解码器之前。原实现把比较器参数写反,
 * "硬件优先"实际排成了软件在前 —— 这类反向 bug 没有测试就只能靠真机掉帧才发现。
 */
public class VideoCodecOrderTest {

    @Test
    public void hardwareCodecsSortBeforeKnownSoftwareCodecs() {
        List<String> codecs = new ArrayList<>(Arrays.asList(
                "OMX.google.h264.decoder",
                "OMX.qcom.video.decoder.avc",
                "c2.android.avc.decoder",
                "OMX.MTK.VIDEO.DECODER.AVC"));

        codecs.sort(VideoCodecOrder::compareHardwareFirst);

        assertEquals(Arrays.asList(
                "OMX.qcom.video.decoder.avc",
                "OMX.MTK.VIDEO.DECODER.AVC",
                "OMX.google.h264.decoder",
                "c2.android.avc.decoder"), codecs);
    }

    @Test
    public void softwareCodecNamesAreRecognizedAndUnknownNamesAreNotDemoted() {
        assertTrue(VideoCodecOrder.isSoftwareCodec("OMX.google.h264.decoder"));
        assertTrue(VideoCodecOrder.isSoftwareCodec("c2.android.avc.decoder"));
        assertTrue(VideoCodecOrder.isSoftwareCodec("OMX.ffmpeg.h264.decoder"));
        // 认不出的厂商名不能当成软解:那会让本来可用的硬解被排到后面
        assertFalse(VideoCodecOrder.isSoftwareCodec("OMX.vendor.video.decoder.avc"));
        assertFalse(VideoCodecOrder.isSoftwareCodec(null));
    }

    @Test
    public void codecsInSameClassKeepInputOrder() {
        assertEquals(0, VideoCodecOrder.compareHardwareFirst(
                "OMX.vendor.decoder.one", "c2.vendor.decoder.two"));
        assertEquals(0, VideoCodecOrder.compareHardwareFirst(
                "OMX.google.decoder.one", "c2.android.decoder.two"));
    }
}
