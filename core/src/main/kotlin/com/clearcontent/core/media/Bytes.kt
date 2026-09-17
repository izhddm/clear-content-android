package com.clearcontent.core.media

import java.io.ByteArrayOutputStream

/** Thrown when a container is structurally broken; callers fall back to re-encoding. */
class MalformedMediaException(message: String) : Exception(message)

internal fun ByteArray.u8(pos: Int): Int {
    if (pos !in indices) throw MalformedMediaException("Unexpected end of data at $pos")
    return this[pos].toInt() and 0xFF
}

internal fun ByteArray.u16be(pos: Int): Int = (u8(pos) shl 8) or u8(pos + 1)

internal fun ByteArray.u16le(pos: Int): Int = u8(pos) or (u8(pos + 1) shl 8)

internal fun ByteArray.u32be(pos: Int): Long =
    (u8(pos).toLong() shl 24) or (u8(pos + 1).toLong() shl 16) or
        (u8(pos + 2).toLong() shl 8) or u8(pos + 3).toLong()

internal fun ByteArray.u32le(pos: Int): Long =
    u8(pos).toLong() or (u8(pos + 1).toLong() shl 8) or
        (u8(pos + 2).toLong() shl 16) or (u8(pos + 3).toLong() shl 24)

internal fun ByteArray.u64be(pos: Int): Long = (u32be(pos) shl 32) or u32be(pos + 4)

/** ASCII view of [len] bytes; non-printable bytes become '?'. */
internal fun ByteArray.ascii(pos: Int, len: Int): String {
    if (pos < 0 || pos + len > size) return ""
    val sb = StringBuilder(len)
    for (i in pos until pos + len) {
        val c = this[i].toInt() and 0xFF
        sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
    }
    return sb.toString()
}

internal fun ByteArray.startsWithAt(pos: Int, prefix: ByteArray): Boolean {
    if (pos < 0 || pos + prefix.size > size) return false
    for (i in prefix.indices) if (this[pos + i] != prefix[i]) return false
    return true
}

internal fun ByteArray.indexOf(needle: ByteArray, from: Int = 0, to: Int = size): Int {
    if (needle.isEmpty()) return from
    val last = minOf(to, size) - needle.size
    var i = maxOf(from, 0)
    val first = needle[0]
    while (i <= last) {
        if (this[i] == first) {
            var j = 1
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return i
        }
        i++
    }
    return -1
}

internal fun ByteArrayOutputStream.u16be(v: Int) {
    write((v ushr 8) and 0xFF); write(v and 0xFF)
}

internal fun ByteArrayOutputStream.u32be(v: Long) {
    write(((v ushr 24) and 0xFF).toInt()); write(((v ushr 16) and 0xFF).toInt())
    write(((v ushr 8) and 0xFF).toInt()); write((v and 0xFF).toInt())
}

internal fun ByteArrayOutputStream.u32le(v: Long) {
    write((v and 0xFF).toInt()); write(((v ushr 8) and 0xFF).toInt())
    write(((v ushr 16) and 0xFF).toInt()); write(((v ushr 24) and 0xFF).toInt())
}

internal fun ByteArrayOutputStream.writeRange(src: ByteArray, from: Int, until: Int) = write(src, from, until - from)

internal fun String.latin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

internal fun ByteArray.putU32be(pos: Int, v: Long) {
    this[pos] = (v ushr 24).toByte(); this[pos + 1] = (v ushr 16).toByte()
    this[pos + 2] = (v ushr 8).toByte(); this[pos + 3] = v.toByte()
}

internal fun ByteArray.putU64be(pos: Int, v: Long) {
    putU32be(pos, v ushr 32); putU32be(pos + 4, v and 0xFFFFFFFFL)
}

internal fun ByteArray.putU32le(pos: Int, v: Long) {
    this[pos] = v.toByte(); this[pos + 1] = (v ushr 8).toByte()
    this[pos + 2] = (v ushr 16).toByte(); this[pos + 3] = (v ushr 24).toByte()
}
