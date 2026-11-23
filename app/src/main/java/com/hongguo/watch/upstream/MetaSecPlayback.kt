// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * Official / modded 红果 **7.4.x** (incl. 7.4.8.32 / 74832) signs play traffic with
 * ByteDance **MetaSec / MSSDK** (`com.bytedance.mobsec.metasec.ml.MS*` + native
 * `libmetasec_ml.so` ≈ 4.3 MiB). Modded builds do **not** provide a portable signer.
 *
 * ## Prefer MetaSec?
 * Yes for protocol parity — empty HTTP 200 on correct routes under [PlayerSigner]
 * is consistent with upstream rejecting non-MetaSec digests.
 *
 * ## Why this build does **not** wire MetaSec
 * 1. **Legal / redistribution:** `libmetasec_ml.so` is proprietary; copying it from the
 *    Baidu APK into this GPL tree or shipping it inside watch APKs is not viable.
 * 2. **JNI surface:** DEX exposes `MS` / `MSManager` / `MSConfig` with `getToken` /
 *    `getSign` style hooks, but they need MetaSec init (app id, settings fetch,
 *    package signature checks). There is no public SDK license for third-party use.
 * 3. **Load-from-installed-official-app:** `System.load` / `dlopen` of another app’s
 *    extracted `.so` fails under Android linker namespaces / SELinux; copying the
 *    `.so` into our private dir is still unauthorized redistribution of the binary.
 * 4. **Cloud/JVM unit tests:** MetaSec is Android-native only; cannot run in
 *    `:app:testDebugUnitTest` on the CI JVM.
 *
 * ## Current stand-in
 * [MobilePlayback.signer] → v5 ticket pick + [PlayerSigner] (GPL Argus/Ladon).
 * Full play orchestration (register → cookies → sign → video_tab → model → stream)
 * lives in [AppSourceClient] + [MobilePlayback]; MetaSec remains the documented ceiling.
 *
 * ## Unblock conditions (product / legal)
 * - Written permission to redistribute or dynamically link MetaSec for this app, **or**
 * - An official/partner signing oracle that returns MetaSec-equivalent headers, **or**
 * - Upstream accepting PlayerSigner digests again (observable non-empty live bodies).
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
