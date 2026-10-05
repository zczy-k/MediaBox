package com.github.tvbox.osc.ui.page

/** Automatic home-card aspect-ratio sampling policy. */
internal object HomePosterRatio {
    const val DEFAULT = 2f / 3f
    const val PORTRAIT = 2f / 3f
    const val LANDSCAPE = 16f / 9f

    private const val MIN_RATIO = 0.55f
    private const val MAX_RATIO = 1.90f

    /** Robust to a few outlier covers: the median becomes the shared grid ratio. */
    fun dominant(ratios: Collection<Float>, fallback: Float = DEFAULT): Float {
        val valid = ratios
            .filter { it.isFinite() && it > 0f }
            .map { it.coerceIn(MIN_RATIO, MAX_RATIO) }
            .sorted()
        if (valid.isEmpty()) return fallback.coerceIn(MIN_RATIO, MAX_RATIO)
        val middle = valid.size / 2
        val median = if (valid.size % 2 == 1) {
            valid[middle]
        } else {
            (valid[middle - 1] + valid[middle]) / 2f
        }
        return median.coerceIn(MIN_RATIO, MAX_RATIO)
    }
}
