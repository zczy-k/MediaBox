package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质切换调度器的门控口径(**2026-10-10 晚判据替换后**)。
 *
 * <p>旧口径是"按高度向上取整的档位阶梯"(480/720/1080/1440/2160),**已整段删除** ——
 * 真机取证:它把 1280×534 与 1728×720 判成同一档 ⇒ 任何模式都升不上去(整天零升档),
 * 而 480→534 反被算成 +1 档。现在统一按等效清晰度 **S = √(W×H)** 比较,并加了 4K 天花板。
 *
 * <p>本文件锁三件事:①门控的短路顺序与各条拒绝理由;②"值得切"的 θ/ΔS/bpp 口径;
 * ③选择器是**一次到位**(取最优)而**不是**逐级爬梯。
 */
class QualityGovernorTest {

    private fun v(w: Int, h: Int, bw: Int = 0, flag: String = "") =
        VideoQualityPolicy.Variant(w, h, bw, VideoQualityPolicy.Confidence.MEASURED, flag)

    // 常用样本(尺寸 → S)
    private val sd = v(640, 480) // 554
    private val hd = v(1280, 720) // 960
    private val wideFhd = v(1920, 800) // 1239(宽银幕 1080p)
    private val fhd = v(1920, 1080) // 1440
    private val qhd = v(2560, 1440) // 1920
    private val uhd = v(3840, 1600) // 2479(4K 级)

    private fun gate(
        mode: DeviceCapability.QualityMode = DeviceCapability.QualityMode.QUALITY_FIRST,
        current: VideoQualityPolicy.Variant? = sd,
        target: VideoQualityPolicy.Variant? = fhd,
        cap: Int = 0,
        since: Long = 120_000L,
        done: Int = 0,
        lockedS: Int = -1,
        saver: Boolean = false,
    ) = QualityGovernor.canSwitchUp(mode, current, target, cap, since, done, lockedS, saver)

    // ==================== 无条件拒绝(短路在最前) ====================

    @Test
    fun `流量节省开启时任何模式都不切`() {
        DeviceCapability.QualityMode.values().forEach { mode ->
            assertFalse("saver 开启必须恒拒(mode=$mode)", gate(mode = mode, saver = true))
        }
    }

    @Test
    fun `尺寸未实测时一律不切_没数据不猜`() {
        assertFalse(gate(current = null))
        assertFalse(gate(target = null))
        // Variant() 的宽高为 0 ⇒ known=false
        assertFalse(gate(current = VideoQualityPolicy.Variant()))
        assertFalse(gate(target = VideoQualityPolicy.Variant()))
    }

    @Test
    fun `速度优先档不切`() {
        assertFalse(gate(mode = DeviceCapability.QualityMode.SPEED_FIRST))
    }

    @Test
    fun `超设备上限直接拒_而不是排后面`() {
        // 目标 1440p > cap 1080 ⇒ 拒(选中后才失败会白等一次起播超时)
        assertFalse(gate(current = sd, target = qhd, cap = 1080))
        // cap 放宽到 2160 ⇒ 放行
        assertTrue(gate(current = sd, target = qhd, cap = 2160))
    }

    @Test
    fun `已达4K级即停_不得无限追高`() {
        // 当前已在 4K 级(有效宽度 ≥3840):无论目标多高都拒
        assertFalse(gate(current = uhd, target = v(3840, 2160)))
        // 非标宽高同样算达顶(3840×1600 高度只有 1600,不能因此漏判)
        assertTrue(VideoQualityPolicy.isAtCeiling(v(3840, 1600)))
    }

    @Test
    fun `目标不更高时拒_只升不降`() {
        assertFalse(gate(current = fhd, target = hd))
        // 同尺寸也不算"升"
        assertFalse(gate(current = fhd, target = v(1920, 1080)))
    }

    // ==================== "值得切"口径:θ / ΔS / bpp ====================

    @Test
    fun `自动档需 1_08 倍_画质优先 1_03 倍`() {
        // ratio = 1500 / 1440 ≈ 1.042:落在 [1.03, 1.08) —— 画质优先放行、自动档拒绝
        val mid = v(2000, 1125)
        assertFalse(gate(mode = DeviceCapability.QualityMode.AUTO, current = fhd, target = mid))
        assertTrue(gate(mode = DeviceCapability.QualityMode.QUALITY_FIRST, current = fhd, target = mid))
    }

    @Test
    fun `大幅提升任何模式都放行`() {
        DeviceCapability.QualityMode.values()
            .filter { it != DeviceCapability.QualityMode.SPEED_FIRST }
            .forEach { mode ->
                assertTrue("mode=$mode", gate(mode = mode, current = sd, target = fhd))
            }
    }

    @Test
    fun `低清晰度下比值够但绝对提升不足时拒`() {
        // 139 → 151:比值 1.086 ≥1.08,但 ΔS 只有 12 <32 —— 肉眼不可见,不值得一次重载
        val tiny = v(160, 120)
        val tinyPlus = v(179, 127)
        assertEquals(139, VideoQualityPolicy.sharpness(tiny))
        assertEquals(151, VideoQualityPolicy.sharpness(tinyPlus))
        assertFalse(gate(mode = DeviceCapability.QualityMode.AUTO, current = tiny, target = tinyPlus))
    }

    @Test
    fun `bpp 例外_同清晰度但码率显著更高可切`() {
        val lowBpp = v(1920, 1080, bw = 10_000_000)
        val highBpp = v(1920, 1080, bw = 30_000_000)
        assertTrue(QualityGovernor.worthSwitching(lowBpp, highBpp, DeviceCapability.QualityMode.QUALITY_FIRST))
        // 反向(码率更低)不切
        assertFalse(QualityGovernor.worthSwitching(highBpp, lowBpp, DeviceCapability.QualityMode.QUALITY_FIRST))
    }

    @Test
    fun `宽银幕按清晰度排序_不再落入档位死区`() {
        // 旧口径把 1920×800 与 1280×720 都归到"720 档"(向上取整)⇒ 永远升不上去
        assertTrue(VideoQualityPolicy.sharpness(wideFhd) > VideoQualityPolicy.sharpness(hd))
        assertTrue(gate(mode = DeviceCapability.QualityMode.AUTO, current = hd, target = wideFhd))
    }

    // ==================== 稳定期 / 额度 / 会话锁 ====================

    @Test
    fun `稳定期不足不切`() {
        assertFalse(gate(since = QualityGovernor.MIN_WATCH_MS - 1))
        assertFalse(gate(since = -1L))
        assertTrue(gate(since = QualityGovernor.MIN_WATCH_MS))
    }

    @Test
    fun `本集额度用尽不切`() {
        assertFalse(gate(done = QualityGovernor.MAX_UPGRADES_PER_EPISODE))
        assertTrue(gate(done = 1))
    }

    @Test
    fun `会话锁按 S 判_高于出发地S的目标一律拒`() {
        // 出发地 S=960(hd),目标 1440(fhd)>960 ⇒ 拒
        assertFalse(gate(current = hd, target = fhd, lockedS = 960))
        // 锁放宽到 1500 ⇒ 1440 ≤ 1500,放行
        assertTrue(gate(current = hd, target = fhd, lockedS = 1500))
    }

    // ==================== 选择器:一次到位 / 降档镜像 ====================

    private fun up(flag: String, w: Int, h: Int) = v(w, h, flag = flag)

    @Test
    fun `升档一次到位_取最优而不是相邻一档`() {
        val a = up("a", 640, 480) // 当前
        val b = up("b", 1280, 720)
        val c = up("c", 1920, 1080)
        val d = up("d", 3840, 2160)
        val measured = listOf(a, b, c, d)

        // 逐级 = N 次重载 = N 次闪屏;这里必须直奔最优 d
        assertEquals("d", LineQualitySelector.pickUpgrade(measured, "a", a, emptySet()))
        // d 已试(升过又回滚)⇒ 退到次优 c
        assertEquals("c", LineQualitySelector.pickUpgrade(measured, "a", a, setOf("d")))
        // 全部试过 ⇒ null
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", a, setOf("d", "c", "b")))
    }

    @Test
    fun `升档当前尺寸未知时返回null`() {
        val measured = listOf(up("a", 640, 480), up("b", 1920, 1080))
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", null, emptySet()))
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", VideoQualityPolicy.Variant(), emptySet()))
    }

    @Test
    fun `没有更高画质时返回null`() {
        val a = up("a", 1920, 1080)
        val b = up("b", 1280, 720)
        assertNull(LineQualitySelector.pickUpgrade(listOf(a, b), "a", a, emptySet()))
    }

    @Test
    fun `降档取低于当前里最高的一条`() {
        val a = up("a", 640, 480)
        val b = up("b", 1280, 720)
        val c = up("c", 1920, 1080)
        val measured = listOf(a, b, c)

        // 降得最少:取 b(960)而不是 a(554)
        assertEquals("b", LineQualitySelector.pickDowngrade(measured, "c", c, emptySet()))
        // b 已试 ⇒ 退到 a
        assertEquals("a", LineQualitySelector.pickDowngrade(measured, "c", c, setOf("b")))
        // 已是最低 ⇒ null
        assertNull(LineQualitySelector.pickDowngrade(measured, "a", a, emptySet()))
        // 当前未知 ⇒ null
        assertNull(LineQualitySelector.pickDowngrade(measured, "c", null, emptySet()))
    }

    // ==================== inRollbackWindow ====================

    @Test
    fun `回滚窗口判定`() {
        assertFalse(QualityGovernor.inRollbackWindow(1_000_000L, 0L))
        val at = 1_000_000L
        assertTrue(QualityGovernor.inRollbackWindow(at + 1, at))
        assertTrue(QualityGovernor.inRollbackWindow(at + QualityGovernor.ROLLBACK_WINDOW_MS, at))
        assertFalse(QualityGovernor.inRollbackWindow(at + QualityGovernor.ROLLBACK_WINDOW_MS + 1, at))
    }
}
