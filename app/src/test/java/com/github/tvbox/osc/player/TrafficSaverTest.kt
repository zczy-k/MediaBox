package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「流量节省」档位归一化的口径锁(见《选线机制设计》附录 F)。
 *
 * <p>只测纯函数 [DeviceCapability.resolveMode]:`trafficSaverOn()` 与 `effectiveMode()` 都要读 KV
 * (真机 MMKV),JVM 单测环境不可用 —— 与 [VideoQualityModeTest] 同一取舍(那里也只锁 enum 常量)。
 *
 * <p>这里锁死两条**不能被后人改错**的语义:
 * <ol>
 *   <li>开启 ⇒ 一律归一到自动档(画质优先被暂停);</li>
 *   <li>归一化**不改写入参** ⇒ 关掉开关即恢复用户原选择(存量迁移靠它做到"不丢选择")。</li>
 * </ol>
 */
class TrafficSaverTest {

    @Test
    fun `开启时一律归一到自动档`() {
        DeviceCapability.QualityMode.values().forEach { stored ->
            assertEquals(
                "流量节省开启时生效档位必须是自动档(stored=$stored)",
                DeviceCapability.QualityMode.AUTO,
                DeviceCapability.resolveMode(stored, saverOn = true),
            )
        }
    }

    @Test
    fun `关闭时原样返回用户选择`() {
        DeviceCapability.QualityMode.values().forEach { stored ->
            assertEquals(
                stored,
                DeviceCapability.resolveMode(stored, saverOn = false),
            )
        }
    }

    @Test
    fun `存量画质优先被暂停但选择不丢`() {
        val stored = DeviceCapability.QualityMode.QUALITY_FIRST
        // 升级后默认开启流量节省:实际按自动档跑(安全侧)
        assertEquals(
            DeviceCapability.QualityMode.AUTO,
            DeviceCapability.resolveMode(stored, saverOn = true),
        )
        // 关掉开关即恢复 —— 归一化没有改写用户选择
        assertEquals(stored, DeviceCapability.resolveMode(stored, saverOn = false))
    }

    @Test
    fun `归一到自动档后探测预算随自动档收窄`() {
        val effective = DeviceCapability.resolveMode(
            DeviceCapability.QualityMode.QUALITY_FIRST,
            saverOn = true,
        )
        // 起播前探测按自动档口径,而不是画质优先的 2s / 4 条
        assertEquals(DeviceCapability.QualityMode.AUTO.probeBudgetMs, effective.probeBudgetMs)
        assertEquals(DeviceCapability.QualityMode.AUTO.probeLines, effective.probeLines)
    }

    @Test
    fun `画质优先的下标就是置灰下标_UI与门控口径一致`() {
        // PlaySettingsPage 用 setOf(0) 置灰「画质优先」,这里把它钉死在枚举上:
        // 换序会让 UI 灰错项,必须先改 VideoQualityModeTest 再动 KV
        assertEquals(0, DeviceCapability.QualityMode.QUALITY_FIRST.ordinal)
    }
}
