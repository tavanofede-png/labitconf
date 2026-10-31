package org.nieto.assistant.screen

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A process-local change detector. Neither the key, static text nor digest is part of the wire schema. */
class LocalScreenFingerprint private constructor(private val key: ByteArray) {
    constructor() : this(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun begin(metadata: String): Digest = Digest(Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }).also { it.add(metadata) }

    class Digest internal constructor(private val mac: Mac) {
        fun add(value: CharSequence?) {
            val text = value?.toString().orEmpty()
            mac.update(text.length.toString().toByteArray(Charsets.UTF_8))
            mac.update(0)
            // Hash in bounded chunks; do not retain a copy of the complete screen's prose.
            var offset = 0
            while (offset < text.length) {
                val end = minOf(text.length, offset + 2048)
                val chunk = ByteArray((end - offset) * 2)
                for (index in offset until end) {
                    val code = text[index].code
                    chunk[(index - offset) * 2] = (code ushr 8).toByte()
                    chunk[(index - offset) * 2 + 1] = code.toByte()
                }
                mac.update(chunk)
                offset = end
            }
        }
        fun finish(): String = mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
