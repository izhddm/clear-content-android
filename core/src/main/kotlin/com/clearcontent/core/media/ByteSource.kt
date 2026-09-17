package com.clearcontent.core.media

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access input so multi-gigabyte videos never have to be loaded into memory. */
interface ByteSource : Closeable {
    val size: Long

    /** Reads exactly [len] bytes starting at [position]. */
    fun readFully(position: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size)

    fun read(position: Long, len: Int): ByteArray = ByteArray(len).also { readFully(position, it) }

    override fun close() {}
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        if (position < 0 || position + len > bytes.size) throw EOFException("Read past end: $position+$len > ${bytes.size}")
        System.arraycopy(bytes, position.toInt(), dst, off, len)
    }
}

class FileChannelSource(private val channel: FileChannel, private val onClose: Closeable? = null) : ByteSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(dst, off, len)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("Read past end at $pos")
            pos += n
        }
    }

    override fun close() {
        channel.close()
        onClose?.close()
    }

    companion object {
        fun open(file: File): FileChannelSource {
            val raf = RandomAccessFile(file, "r")
            return FileChannelSource(raf.channel, raf)
        }
    }
}
