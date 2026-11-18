// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

class Sm3Test {
    @Test
    fun knownVectorAbc() {
        // GM/T 0004-2012 example: SM3("abc")
        val expected = "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"
        assertEquals(expected, sm3("abc".toByteArray(Charsets.US_ASCII)).hex())
    }

    @Test
    fun knownVectorEmpty() {
        val expected = "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b"
        assertEquals(expected, sm3(ByteArray(0)).hex())
    }

    @Test
    fun base64RoundTrip() {
        val raw = byteArrayOf(1, 2, 3, 250.toByte(), 255.toByte())
        assertEquals(raw.toList(), decode64(raw.base64()).toList())
    }
}
