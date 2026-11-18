package com.hongguo.watch.player

/**
 * 播放内核：Media3（默认非手表）与 IJK/FFmpeg 软解（手表 / 低配 A/B）。
 * UI 文案用「系统播放器 / 兼容软解」，不点名第三方客户端。
 */
enum class PlayerKernel {
    /** 手表 / 脆弱 SoC → IJK；其它 → Media3 */
    AUTO,
    MEDIA3,
    IJK,
    ;

    companion object {
        fun fromStorage(raw: String?): PlayerKernel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}

enum class DecodePath {
    /** 跟随设备：手表 / 脆弱 SoC 默认软解 */
    AUTO,
    HARDWARE,
    SOFTWARE,
    ;

    companion object {
        fun fromStorage(raw: String?): DecodePath =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}
