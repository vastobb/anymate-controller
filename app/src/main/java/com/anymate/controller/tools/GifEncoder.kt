package com.anymate.controller.tools

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact GIF89a writer tuned for flat, illustrated animation. Palette choice
 * is popularity based, which is ideal for vector art with anti-aliased edges.
 */
object GifQuantizer {

    fun buildPalette(samples: List<Bitmap>, maxColors: Int = 256): IntArray {
        val histogram = IntArray(32768)
        for (bmp in samples) {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            var i = 0
            while (i < pixels.size) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                histogram[key]++
                i += 2
            }
        }
        val entries = ArrayList<IntArray>(4096)
        for (key in 0 until 32768) {
            val count = histogram[key]
            if (count > 0) entries.add(intArrayOf(key, count))
        }
        entries.sortByDescending { it[1] }

        val palette = LinkedHashSet<Int>()
        val colorSlots = (maxColors - 32).coerceAtLeast(8)
        for (entry in entries) {
            if (palette.size >= colorSlots) break
            val r5 = (entry[0] shr 10) and 0x1F
            val g5 = (entry[0] shr 5) and 0x1F
            val b5 = entry[0] and 0x1F
            palette.add((expand5(r5) shl 16) or (expand5(g5) shl 8) or expand5(b5))
        }
        // A neutral ramp keeps anti-aliased type and soft edges clean.
        for (i in 0 until 32) {
            val v = (i * 255 / 31)
            palette.add((v shl 16) or (v shl 8) or v)
        }
        while (palette.size < maxColors) palette.add(0x000000)
        return palette.toIntArray()
    }

    private fun expand5(v: Int): Int = (v shl 3) or (v shr 2)
}

class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    paletteColors: IntArray,
    fps: Int,
    loop: Boolean = true
) {
    private val palette: IntArray = paletteColors.copyOf(256)
    private val delayCs: Int = max(1, (100.0 / fps.coerceAtLeast(1)).roundToInt())
    private val lut: IntArray = IntArray(32768)
    private var framesWritten = 0

    init {
        require(width in 1..65535 && height in 1..65535)
        writeHeader()
        writePalette()
        if (loop) writeLoopExtension()
        buildLut()
    }

    fun writeFrame(bitmap: Bitmap) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)

            // Graphic control extension: dispose to background, no transparency.
            out.write(0x21)
            out.write(0xF9)
            out.write(4)
            out.write(0x08) // disposal method 2
            writeShort(delayCs)
            out.write(0)
            out.write(0)

            // Image descriptor.
            out.write(0x2C)
            writeShort(0)
            writeShort(0)
            writeShort(width)
            writeShort(height)
            out.write(0)

            val indices = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                val key = (((c shr 16) and 0xFF) shr 3 shl 10) or
                    (((c shr 8) and 0xFF) shr 3 shl 5) or
                    ((c and 0xFF) shr 3)
                indices[i] = lut[key].toByte()
            }
            out.write(8) // LZW minimum code size
            writeLzw(indices)
            framesWritten++
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    fun close() {
        runCatching { out.close() }
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0xF7) // global table, 8-bit colour resolution, 256 entries
        out.write(0)
        out.write(0)
    }

    private fun writePalette() {
        for (color in palette) {
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
    }

    private fun writeLoopExtension() {
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeShort(0) // infinite
        out.write(0)
    }

    private fun buildLut() {
        for (key in 0 until 32768) {
            val r = ((key shr 10) and 0x1F) * 255 / 31
            val g = ((key shr 5) and 0x1F) * 255 / 31
            val b = (key and 0x1F) * 255 / 31
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val pr = (palette[i] shr 16) and 0xFF
                val pg = (palette[i] shr 8) and 0xFF
                val pb = palette[i] and 0xFF
                val dr = r - pr
                val dg = g - pg
                val db = b - pb
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                    if (distance == 0) break
                }
            }
            lut[key] = best
        }
    }

    private fun writeLzw(indices: ByteArray) {
        val clear = 256
        val end = 257
        var codeSize = 9
        var nextCode = 258
        val dictionary = HashMap<Int, Int>(8192)
        val bytes = ByteArrayOutputStream(indices.size / 2 + 64)
        var bitBuffer = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                bytes.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer shr 8
                bitCount -= 8
            }
        }

        emit(clear)
        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val next = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or next
                val existing = dictionary[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emit(prefix)
                    // GIF LZW: the code width grows one code *after* the table
                    // reaches 2^codeSize. The check must happen before the entry
                    // is added, exactly like the reference encoders, otherwise
                    // decoders desynchronise and the file cannot be read.
                    if (nextCode > (1 shl codeSize) - 1 && codeSize < 12) {
                        codeSize++
                    }
                    if (nextCode < 4096) {
                        dictionary[key] = nextCode
                        nextCode++
                    } else {
                        emit(clear)
                        dictionary.clear()
                        codeSize = 9
                        nextCode = 258
                    }
                    prefix = next
                }
            }
            emit(prefix)
            if (nextCode > (1 shl codeSize) - 1 && codeSize < 12) {
                codeSize++
            }
        }
        emit(end)
        if (bitCount > 0) bytes.write(bitBuffer and 0xFF)

        val data = bytes.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(255, data.size - offset)
            out.write(size)
            out.write(data, offset, size)
            offset += size
        }
        out.write(0)
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
