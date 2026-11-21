package com.hongguo.watch.data.redfruit

internal data class RedfruitCredentials(val cookie: String, val token: String = "") {
    override fun toString(): String = "RedfruitCredentials(redacted)"
}
