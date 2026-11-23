// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import java.io.IOException

/** 播放模型的候选域名与临时故障分类；路径顺序和总预算由会话健康度决定。 */
internal object PlayRetryPlan {
    /**
     * 官方空响应常见于单 host；b 为现行主路径（与 drama 对齐），a/hl/lf 作备选。
     * MobilePlayback 会再把 primary 放在队首并去重。
     */
    val hosts =
        listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
        )

    fun host(attempt: Int) = hosts[attempt % hosts.size]

    /** 诊断行用的短代号；不暴露完整域名以外的任何信息。 */
    fun tag(host: String) =
        when (host) {
            "api5-normal-sinfonlineb.fqnovel.com" -> "b"
            "api5-normal-sinfonlinea.fqnovel.com" -> "a"
            "api5-normal-sinfonlinec.fqnovel.com" -> "c"
            "api5-normal-hl.fqnovel.com" -> "hl"
            "api5-normal-lf.fqnovel.com" -> "lf"
            else -> host.substringBefore('.').takeLast(8)
        }

    /** 与 ContentRepository.text() 相同的临时故障判定：连接类故障与 5xx 值得换路重试。 */
    fun temporary(problem: IOException) =
        problem is EmptyPlaybackResponseException ||
            problem is java.net.SocketTimeoutException ||
            problem is java.net.SocketException ||
            problem is java.net.UnknownHostException ||
            (problem is ContentHttpException && retryableStatus(problem.statusCode)) ||
            (problem is PlaybackSourceException &&
                problem.reason == PlaybackSourceException.Reason.TEMPORARY_UNAVAILABLE)

    /** Do not rotate hosts for auth failures or rate limits; those amplify a deliberate rejection. */
    internal fun retryableStatus(status: Int) =
        status == 408 || status == 425 || status in setOf(500, 502, 503, 504)
}
