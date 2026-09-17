package com.clearcontent.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Builders for synthetic media carrying AI-provenance metadata. */
object TestMedia {

    fun image(width: Int = 64, height: Int = 48, alpha: Boolean = false): BufferedImage {
        val img = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val a = if (alpha) (x * 4) and 0xFF else 0xFF
            img.setRGB(x, y, (a shl 24) or ((x * 4) shl 16) or ((y * 5) shl 8) or ((x + y) and 0xFF))
        }
        return img
    }

    fun jpeg(progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image(), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun png(alpha: Boolean = true): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(alpha = alpha), "png", it) }.toByteArray()

    fun gif(): ByteArray = ByteArrayOutputStream().also {
        val indexed = BufferedImage(32, 32, BufferedImage.TYPE_BYTE_INDEXED)
        indexed.graphics.drawImage(image(32, 32), 0, 0, null)
        ImageIO.write(indexed, "gif", it)
    }.toByteArray()

    fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("undecodable")

    fun pixels(img: BufferedImage): IntArray = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    // ------------------------------------------------------------------ metadata payloads

    const val XMP = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
 Iptc4xmpExt:DigitalSourceType="http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
 xmp:CreatorTool="Google AI" exif:GPSLatitude="59,56.3N" xmlns:exif="http://ns.adobe.com/exif/1.0/"/>
</rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""

    /** A structurally plausible JUMBF superbox labelled "c2pa" carrying a ChatGPT claim generator string. */
    fun jumbf(): ByteArray {
        val claim = "claim_generator\u0000ChatGPT c2pa-rs/0.49 c2pa.actions c2pa.created".toByteArray()
        val label = "c2pa".toByteArray() + 0.toByte()
        val jumd = box("jumd", ByteArray(16) + byteArrayOf(0x03) + label)
        val json = box("json", claim)
        return box("jumb", jumd + json)
    }

    fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(8 + payload.size.toLong()); out.write(type.toByteArray(Charsets.ISO_8859_1)); out.write(payload)
        return out.toByteArray()
    }

    fun uuidBox(uuidHex: String, payload: ByteArray): ByteArray {
        val uuid = ByteArray(16) { uuidHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return box("uuid", uuid + payload)
    }

    /** Big-endian TIFF with Orientation, Software and a GPS IFD. */
    fun exifTiff(orientation: Int, software: String, withGps: Boolean = true): ByteArray {
        val sw = software.toByteArray() + 0.toByte()
        val entries = if (withGps) 3 else 2
        val ifd0Size = 2 + entries * 12 + 4
        val swOffset = 8 + ifd0Size
        val gpsOffset = swOffset + sw.size
        val out = ByteArrayOutputStream()
        out.write("MM".toByteArray()); out.u16(42); out.u32(8)
        out.u16(entries)
        out.u16(0x0112); out.u16(3); out.u32(1); out.u16(orientation); out.u16(0)
        out.u16(0x0131); out.u16(2); out.u32(sw.size.toLong()); out.u32(swOffset.toLong())
        if (withGps) { out.u16(0x8825); out.u16(4); out.u32(1); out.u32(gpsOffset.toLong()) }
        out.u32(0)
        out.write(sw)
        if (withGps) {
            out.u16(1)
            out.u16(0x0001); out.u16(2); out.u32(2); out.write("N".toByteArray() + byteArrayOf(0, 0, 0))
            out.u32(0)
        }
        return out.toByteArray()
    }

    fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(marker); out.u16(payload.size + 2); out.write(payload)
        return out.toByteArray()
    }

    /** Inserts [segments] right after SOI (and after JFIF APP0 if present). */
    fun injectJpeg(jpeg: ByteArray, segments: List<ByteArray>, trailing: ByteArray = ByteArray(0)): ByteArray {
        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            insertAt = 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        }
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, insertAt)
        segments.forEach { out.write(it) }
        out.write(jpeg, insertAt, jpeg.size - insertAt)
        out.write(trailing)
        return out.toByteArray()
    }

    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.u32(data.size.toLong()); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.u32(crc.value)
        return out.toByteArray()
    }

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(data); d.finish()
        val buf = ByteArray(data.size + 64)
        val n = d.deflate(buf); d.end()
        return buf.copyOf(n)
    }

    /** Inserts chunks right after IHDR. */
    fun injectPng(png: ByteArray, chunks: List<ByteArray>, trailing: ByteArray = ByteArray(0)): ByteArray {
        val afterIhdr = 8 + 8 + 13 + 4
        val out = ByteArrayOutputStream()
        out.write(png, 0, afterIhdr)
        chunks.forEach { out.write(it) }
        out.write(png, afterIhdr, png.size - afterIhdr)
        out.write(trailing)
        return out.toByteArray()
    }

    fun ByteArrayOutputStream.u16(v: Int) { write((v ushr 8) and 0xFF); write(v and 0xFF) }
    fun ByteArrayOutputStream.u32(v: Long) {
        write(((v ushr 24) and 0xFF).toInt()); write(((v ushr 16) and 0xFF).toInt())
        write(((v ushr 8) and 0xFF).toInt()); write((v and 0xFF).toInt())
    }
    fun ByteArrayOutputStream.u32le(v: Long) {
        write((v and 0xFF).toInt()); write(((v ushr 8) and 0xFF).toInt())
        write(((v ushr 16) and 0xFF).toInt()); write(((v ushr 24) and 0xFF).toInt())
    }
    fun ByteArrayOutputStream.u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }

    fun ByteArray.contains(needle: String): Boolean = String(this, Charsets.ISO_8859_1).contains(needle)

    const val C2PA_UUID = "d8fec3d61b0e483c92975828877ec481"
}
