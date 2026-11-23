// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * Official / modded 红果 **7.4.x** signs play traffic with ByteDance **MetaSec / MSSDK**
 * (`libmetasec_ml.so`). That binary is proprietary and is **not** shipped here.
 *
 * ## Default play path (N3urda TV 0.16.14)
 * Pure Kotlin Medusa V5 — [MobilePlayback.signer] keeps Khronos/Helios/Medusa with
 * [V5Constants.field21] = 312, 74532 query contour, and `preload/v1` on
 * `reading.snssdk.com`. No MetaSec, unidbg, or remote JP proxy required.
 *
 * ## Optional MetaSec / proxy
 * [PlaybackProxyClient] remains available in settings (off by default) for a
 * self-hosted MetaSec/unidbg bridge. Demoted [MobilePlayback.realSigned] /
 * [PlayerSigner] Argus/Ladon is not the primary path (empty HTTP 200).
 *
 * ## Why MetaSec is not wired into the APK
 * 1. **Legal / redistribution:** `libmetasec_ml.so` cannot ship in this GPL tree.
 * 2. **JNI surface** needs MetaSec init the watch cannot lawfully perform.
 * 3. **Load-from-installed-official-app** fails under linker namespaces / SELinux.
 * 4. **CI JVM** cannot run Android-native MetaSec in unit tests.
 */
internal object MetaSecPlayback {
    const val NATIVE_LIB = "metasec_ml"
    const val OFFICIAL_PACKAGE = "com.phoenix.read"
    const val OFFICIAL_VERSION_CODE = VendorConstants.OFFICIAL_VERSION_CODE

    /** Always false until a lawful native bridge exists; keeps call sites honest. */
    fun isAvailable(): Boolean = false

    /**
     * Reserved hook for a future MetaSec-backed [MobilePlaybackSigner].
     * Today always returns null so callers keep [MobilePlayback.signer].
     */
    fun signerOrNull(): MobilePlaybackSigner? = null
}
