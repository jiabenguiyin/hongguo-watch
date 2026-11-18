// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * Compact SM3 (GM/T 0004-2012) digest used by App-path signing.
 * Replaces the full BouncyCastle provider jar to keep watch APKs small.
 */
internal object Sm3 {
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        val n = input.size
        val bitLen = n.toLong() * 8L
        // padding: 0x80 + zeros + 64-bit length; total multiple of 64
        val padLen = ((56 - ((n + 1) % 64)) + 64) % 64
        val total = n + 1 + padLen + 8
        val buf = ByteArray(total)
        System.arraycopy(input, 0, buf, 0, n)
        buf[n] = 0x80.toByte()
        for (i in 0 until 8) {
            buf[total - 1 - i] = ((bitLen ushr (8 * i)) and 0xffL).toByte()
        }

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)
        var off = 0
        while (off < total) {
            for (j in 0 until 16) {
                val i = off + j * 4
                w[j] = ((buf[i].u() shl 24) or (buf[i + 1].u() shl 16) or
                    (buf[i + 2].u() shl 8) or buf[i + 3].u())
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor
                    rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) {
                w1[j] = w[j] xor w[j + 4]
            }

            var a = v[0]
            var b = v[1]
            var c = v[2]
            var d = v[3]
            var e = v[4]
            var f = v[5]
            var g = v[6]
            var h = v[7]
            for (j in 0 until 64) {
                val ss1 = rotl(rotl(a, 12) + e + rotl(t(j), j), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val tt1 = ff(j, a, b, c) + d + ss2 + w1[j]
                val tt2 = gg(j, e, f, g) + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }
            v[0] = v[0] xor a
            v[1] = v[1] xor b
            v[2] = v[2] xor c
            v[3] = v[3] xor d
            v[4] = v[4] xor e
            v[5] = v[5] xor f
            v[6] = v[6] xor g
            v[7] = v[7] xor h
            off += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    private fun t(j: Int): Int = if (j < 16) 0x79cc4519 else 0x7a879d8a

    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)

    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    private fun rotl(x: Int, n: Int): Int = Integer.rotateLeft(x, n)
}
