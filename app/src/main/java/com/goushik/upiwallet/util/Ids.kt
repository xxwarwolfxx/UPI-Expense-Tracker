package com.goushik.upiwallet.util

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7 (RFC 9562): 48-bit ms timestamp + version + random ⇒ time-sortable ids. */
object Ids {
    private val rnd = SecureRandom()

    fun uuid7(): String {
        val tsMs = System.currentTimeMillis() and 0xFFFFFFFFFFFFL // low 48 bits
        val randA = rnd.nextInt(0x1000).toLong()                  // 12 bits
        val randB = rnd.nextLong()

        var msb = tsMs shl 16          // timestamp into bits 63..16
        msb = msb or (0x7L shl 12)     // version 7 into bits 15..12
        msb = msb or randA             // rand_a into bits 11..0

        var lsb = randB and 0x3FFFFFFFFFFFFFFFL // clear top 2 bits
        lsb = lsb or (0x2L shl 62)              // variant 10 into bits 63..62

        return UUID(msb, lsb).toString()
    }
}
