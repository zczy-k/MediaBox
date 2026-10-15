package com.github.tvbox.osc.player.effect

import androidx.media3.common.Effect
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.effect.anime4k.Anime4kEffect
import com.github.tvbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.tvbox.osc.player.effect.anime4k.Anime4kStatus
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.lang.ref.WeakReference

/** 画质参数不可用的原因(None = 可用;面板据此显示一行说明) */
enum class PictureEffectUnavailableReason {
    None, Tunneling, Hdr, RestartRequired, DecoderUnsupported;

    companion object {
        /** 判定优先级:隧道 > HDR > 本集未开通 > 渲染器吞掉;没有待生效的参数(hasLook=false)一律不提示 */
        fun of(
            tunneling: Boolean,
            hdr: Boolean,
            pipeOpen: Boolean,
            effectsActive: Boolean,
            hasLook: Boolean,
        ): PictureEffectUnavailableReason = when {
            tunneling -> Tunneling
            hdr && hasLook -> Hdr
            !pipeOpen && hasLook -> RestartRequired
            pipeOpen && !effectsActive && hasLook -> DecoderUnsupported
            else -> None
        }
    }
}

/** 画质参数(调色)唯一入口:面板改参数走这里;prepare 前必须下发一次 setVideoEffects,否则本集不生效(media3 只在渲染器首次 enable 时建 VideoSink) */
object PictureEffects {

    /** 效果实例复用:调参只改实例内的 volatile 参数 */
    private val colorTone = ColorToneAdjustEffect()
    private val detail = DetailAdjustEffect()
    private val anime4k = Anime4kEffect()

    /** 下发列表的两种形态:超分关闭时不下发它 —— media3 不消费 isNoOp,进了列表就一定会被实例化 */
    private val listWithoutAnime4k: List<Effect> = listOf(colorTone, detail)
    private val listWithAnime4k: List<Effect> = listOf(colorTone, detail, anime4k)

    internal fun effectsFor(anime4kEnabled: Boolean): List<Effect> =
        if (anime4kEnabled) listWithAnime4k else listWithoutAnime4k

    /** 当前在出画的内核实例(实时调参打给它) */
    private var current: WeakReference<ExoPlayer>? = null

    /** 本集被隧道挡住:不开通、不参与实时调参(参数照常落库,不开隧道起播时生效) */
    private var tunneling = false

    /** 本集是否下发过效果:未启用时不下发 —— 空列表同样会让 media3 建 VideoSink,整段失去直通路径 */
    private var openedThisSession = false

    /** 「按住对比」中:临时按恒等参数出画(不落库) */
    private var comparing = false

    /**
     * 超分这一档**每帧**要不要跑:按住对比 或 用户把开关关了 ⇒ 走链里的纯拷贝分支。
     *
     * <p>为什么用 bypass 而不是"把 effect 从列表里摘掉":列表长度一变,media3 要重建 VideoSink,
     * 我们只能重播本集 —— 于是"开个增强从片头重播"。链既然已经挂上了(本集有第三项),
     * 关掉就多一次全屏拷贝,这比重播便宜得多。
     */
    private fun refreshBypass() {
        Anime4kStatus.setBypass(comparing || !Anime4kSettings.enabled())
    }

    /** 本集链里 Anime4K 是否参与、参与的哪个档位/去模糊:任一者变了都要重播(效果列表挂上后恒定) */
    private var anime4kOpened = false
    private var anime4kTierOpened: Anime4kTier = Anime4kTier.default
    private var anime4kDeblurOpened = false

    // ==================== 参数读写 ====================

    /** 当前预置(面板 chips 选中态);老 KV 里被合并掉的档名走 [PicturePreset.resolve] 映射 */
    fun preset(): PicturePreset =
        PicturePreset.resolve(KV.get(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name))

    /** 自定义参数(仅「自定义」预置下参与出画) */
    fun custom(): PictureProfile = PictureProfile(
        saturation = KV.get(HawkConfig.PICTURE_SATURATION, 1f),
        contrast = KV.get(HawkConfig.PICTURE_CONTRAST, 1f),
        brightness = KV.get(HawkConfig.PICTURE_BRIGHTNESS, 0f),
        gamma = KV.get(HawkConfig.PICTURE_GAMMA, 1f),
        hue = KV.get(HawkConfig.PICTURE_HUE, 0f),
        temperature = KV.get(HawkConfig.PICTURE_TEMPERATURE, 0f),
        sharpness = KV.get(HawkConfig.PICTURE_SHARPNESS, 0f),
        shadowLift = KV.get(HawkConfig.PICTURE_SHADOW_LIFT, 0f),
        dehaze = KV.get(HawkConfig.PICTURE_DEHAZE, 0f),
    ).clamped()

    /** 当前应生效的参数(「按住对比」期间恒等) */
    fun applied(): PictureProfile {
        if (comparing) return PictureProfile.OFF
        val preset = preset()
        return if (preset.adjustable) custom() else PictureProfile.of(preset)
    }

    /** 本集是否需要挂链:调色非恒等 或 Anime4K 开着 */
    private fun wanted(): Boolean = !applied().isNoOp || Anime4kSettings.enabled()

    // ==================== 面板入口 ====================

    fun selectPreset(preset: PicturePreset) {
        KV.put(HawkConfig.PICTURE_PRESET, preset.name)
        push()
    }

    fun setCustom(profile: PictureProfile) {
        KV.put(HawkConfig.PICTURE_SATURATION, profile.saturation)
        KV.put(HawkConfig.PICTURE_CONTRAST, profile.contrast)
        KV.put(HawkConfig.PICTURE_BRIGHTNESS, profile.brightness)
        KV.put(HawkConfig.PICTURE_GAMMA, profile.gamma)
        KV.put(HawkConfig.PICTURE_HUE, profile.hue)
        KV.put(HawkConfig.PICTURE_TEMPERATURE, profile.temperature)
        KV.put(HawkConfig.PICTURE_SHARPNESS, profile.sharpness)
        KV.put(HawkConfig.PICTURE_SHADOW_LIFT, profile.shadowLift)
        KV.put(HawkConfig.PICTURE_DEHAZE, profile.dehaze)
        push()
    }

    /** 恢复默认:预置回「原始」+ 滑条回出厂值 */
    fun reset() {
        KV.put(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name)
        setCustom(PictureProfile.OFF)
    }

    /** 按住对比:true = 临时看原图(绕的是整条链 —— 调色 + Anime4K) */
    fun compare(original: Boolean) {
        if (comparing == original) return
        comparing = original
        refreshBypass()
        push()
    }

    /**
     * 面板开关超分:本集链里已经有这一档时**只切每帧 bypass**(下一帧就变,不重播);
     * 链里还没有(本集起播时是关的)才走原路径 —— 挂新链只能等下次起播。
     */
    fun setAnime4kEnabled(enabled: Boolean) {
        Anime4kSettings.setEnabled(enabled)
        if (anime4kOpened && !tunneling) {
            refreshBypass()
            val player = current?.get()
            if (player != null &&
                RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive)
            ) {
                player.redrawVideoFrame()
            }
            LOG.i("echo-picture anime4k live: enabled=" + enabled + " (链常驻,不重播)")
            return
        }
        push()
    }

    // ==================== 内核入口 ====================

    /**
     * 起播钩子:记下出画内核;隧道与直播都不挂效果链(隧道要帧直出显示面、效果链要帧过 GL 图)。
     * 直播内核每次起播都是新实例(enterLive/enterLiveState 先 releasePlayer),链不会残留;参数照常落库。
     */
    fun onPrepare(player: ExoPlayer, tunnelingBlocked: Boolean) {
        comparing = false
        if (KV.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            Anime4kStatus.setBypass(false)
            current = null
            LOG.i("echo-picture-effects skip: live")
            return
        }
        current = WeakReference(player)
        tunneling = tunnelingBlocked
        if (tunnelingBlocked) {
            LOG.i("echo-picture-effects skip: tunneling enabled")
            return
        }
        val profile = applied()
        val tier = Anime4kTier.current()
        val anime4kWanted = Anime4kSettings.enabled()
        anime4k.tier = if (anime4kWanted) tier else null
        anime4kOpened = anime4kWanted
        anime4kTierOpened = tier
        anime4kDeblurOpened = Anime4kSettings.deblur()
        refreshBypass()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        // 链挂着 ⟺ 当前参数非恒等:关闭(参数回恒等)只表示"下次起播不再挂链",绝不下发空列表摘链
        val enabled = !profile.isNoOp || anime4kWanted
        openedThisSession = enabled
        if (enabled) player.applyVideoEffects(effectsFor(anime4kWanted))
    }

    /** 内核释放:摘掉引用,后续调参只落库、等下次起播生效 */
    fun onPlayerReleased(player: ExoPlayer) {
        if (current?.get() === player) current = null
    }

    // ==================== 送屏画布 ====================

    /** 显示侧推给管线的输出尺寸(`MSG_SET_VIDEO_OUTPUT_RESOLUTION` 那个数):Anime4K 链末按它出画 */
    @Volatile
    private var outputCanvas: IntArray? = null

    fun setOutputCanvas(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        outputCanvas = intArrayOf(width, height)
    }

    /** (宽, 高);没推过时 null = 链末回落源尺寸 */
    fun outputCanvas(): IntArray? = outputCanvas

    /** Anime4K 链在本机构建失败(编译不过/资产读不到):面板补一行"本次已跳过";隧道/HDR 属既定跳过、不提示 */
    fun anime4kUnavailable(): Boolean {
        val player = current?.get() ?: return false
        return Anime4kSettings.enabled() && !tunneling && !player.isPictureHdrSource() &&
            Anime4kStatus.unavailable()
    }

    /** 链末锐化强度改动:链每帧现读 ⇒ 播放中下一帧就变;暂停态补一次重绘(不必重播本集) */
    fun setAnime4kSharpen(value: Float) {
        Anime4kSettings.setSharpen(value)
        val player = current?.get() ?: return
        if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive)) {
            player.redrawVideoFrame()
        }
    }

    /** 当前不可调色的原因(内核实例缺失时不下结论) */
    fun unavailableReason(): PictureEffectUnavailableReason {
        val player = current?.get() ?: return PictureEffectUnavailableReason.None
        return PictureEffectUnavailableReason.of(
            tunneling = tunneling,
            hdr = player.isPictureHdrSource(),
            pipeOpen = openedThisSession,
            effectsActive = player.isPictureEffectsActive(),
            hasLook = wanted(),
        )
    }

    /** 面板改参数后调用:true = 需要重播本集才生效(只有"要挂链但本集没挂"这一种情况;关成恒等不再要求摘链) */
    fun consumeRestartNeeded(): Boolean {
        val player = current?.get() ?: return false
        if (tunneling || player.isPictureHdrSource()) return false
        val anime4kWanted = Anime4kSettings.enabled()
        // 开→关不重播:链里的第三项留着,每帧走纯拷贝(见 [refreshBypass]);
        // 关→开且本集起播时没挂这一档,才必须等下次起播(media3 只在渲染器 enable 时建 sink)
        if (anime4kWanted && !anime4kOpened) return true
        if (anime4kWanted && Anime4kTier.current() != anime4kTierOpened) return true
        if (anime4kWanted && Anime4kSettings.deblur() != anime4kDeblurOpened) return true
        return restartNeeded(wanted(), openedThisSession)
    }

    /**
     * 上一条的口径本体(独立出来供 JVM 单测):**要效果、本集却没挂链** ⇒ 重播一次挂上。
     *
     * <p>反向(全关但链挂着)**不重播**:摘链的收益是一次全屏拷贝,代价是从头重播本集 —— 不划算。
     * 链会在下次起播时自然不挂。
     */
    internal fun restartNeeded(wantEffects: Boolean, opened: Boolean): Boolean =
        wantEffects && !opened

    /** 参数变化:本集已挂效果时只改实例参数(着色器每帧现读)并补一次重绘;本集未挂则先下发一次(链要等重播后的 prepare 才真建) */
    private fun push() {
        val player = current?.get() ?: return
        val profile = applied()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        if (tunneling) return
        if (openedThisSession) {
            // 播放中下一帧就现读新参数;暂停态没有下一帧,只能靠重绘那次合成
            if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive)) {
                player.redrawVideoFrame()
            }
            return
        }
        if (!wanted()) return
        // 本集列表形态跟 prepare 时记下的启用态:开启超分必走重播,这里不会遇到两者不一致
        player.applyVideoEffects(effectsFor(anime4kOpened))
    }
}
