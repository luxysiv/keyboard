package com.goviet.keyboard.engine

/**
 * Vietnamese tones.
 */
enum class Tone(val index: Int) {
    NONE(0),
    ACUTE(1),  // acute ('s')
    GRAVE(2),  // grave ('f')
    HOOK(3),   // hook above ('r')
    TILDE(4),  // tilde ('x')
    DOT(5);    // dot below ('j')

    companion object {
        fun fromKey(c: Char): Tone? = when (c.lowercaseChar()) {
            's' -> ACUTE
            'f' -> GRAVE
            'r' -> HOOK
            'x' -> TILDE
            'j' -> DOT
            'z' -> NONE
            else -> null
        }
    }
}

/**
 * Vietnamese composition ownership & editing modes.
 */
/**
 * Simplified composition mode. Two modes cover all previous three ownership states:
 * - VIETNAMESE: full Telex processing (covers both freshly-typed and adopted text).
 * - LITERAL: raw text passthrough (no Telex mutations).
 *
 * The former LIVE_VIETNAMESE and ADOPTED_VIETNAMESE were functionally identical
 * in every processing path and have been merged.
 */
enum class CompositionMode {
    /** Full Telex transformations are active. */
    VIETNAMESE,
    /** Raw text — protected from Telex re-interpretation. */
    LITERAL
}

/**
 * Engine configuration options.
 */
data class EngineOptions(
    var macroEnabled: Boolean = false,
    var alwaysMacro: Boolean = false,
    var directW: Boolean = false,
    var oldTonePlacement: Boolean = false
)


enum class TonePlacement {
    LEGACY,
    MODERN
}

