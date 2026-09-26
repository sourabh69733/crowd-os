package org.freegram.app.protocol

/** NIP-19 bech32 text for 32-byte keys only (`npub`, `nsec`). TLV forms are not supported. */
object Nip19 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encodePublicKey(key: ByteArray): String = encode("npub", key)
    fun encodeSecretKey(key: ByteArray): String = encode("nsec", key)
    fun decodePublicKey(text: String): ByteArray = decode("npub", text)
    fun decodeSecretKey(text: String): ByteArray = decode("nsec", text)

    fun encode(prefix: String, key: ByteArray): String {
        require(key.size == 32)
        val data = convertBits(key.map { it.toInt() and 0xff }, 8, 5, pad = true)
        val checksum = checksum(prefix, data)
        return buildString {
            append(prefix).append('1')
            (data + checksum).forEach { append(CHARSET[it]) }
        }
    }

    fun decode(prefix: String, text: String): ByteArray {
        val input = text.trim()
        require(input.length <= 90 && (input == input.lowercase() || input == input.uppercase())) { "Invalid $prefix text" }
        val lower = input.lowercase()
        val split = lower.lastIndexOf('1')
        require(split > 0 && lower.substring(0, split) == prefix) { "Expected text starting with ${prefix}1" }
        val values = lower.substring(split + 1).map { char ->
            CHARSET.indexOf(char).also { require(it >= 0) { "Invalid $prefix character" } }
        }
        require(values.size > 6 && polymod(expand(prefix) + values) == 1) { "Invalid $prefix checksum" }
        val bytes = convertBits(values.dropLast(6), 5, 8, pad = false)
        require(bytes.size == 32) { "Invalid $prefix length" }
        return ByteArray(32) { bytes[it].toByte() }
    }

    private fun checksum(prefix: String, data: List<Int>): List<Int> {
        val mod = polymod(expand(prefix) + data + List(6) { 0 }) xor 1
        return List(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun expand(prefix: String): List<Int> =
        prefix.map { it.code ushr 5 } + 0 + prefix.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var check = 1
        for (value in values) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) check = check xor generator[i]
        }
        return check
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int> {
        var acc = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val result = ArrayList<Int>()
        for (value in data) {
            require(value ushr from == 0)
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                result.add((acc ushr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) result.add((acc shl (to - bits)) and maxValue)
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxValue) == 0) { "Invalid padding" }
        }
        return result
    }
}
