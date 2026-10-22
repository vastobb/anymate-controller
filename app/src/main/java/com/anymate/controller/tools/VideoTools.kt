package com.anymate.controller.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device video tools: the ffmpeg-style jobs an animation workflow needs,
 * implemented natively so no desktop is required.
 */
object VideoTools {

    // ---- GIF --------------------------------------------------------------
    fun gif(
        workspace: File,
        framePaths: List<String>,
        output: String,
        fps: Int,
        width: Int
    ): ToolResult {
        val frames = framePaths.mapNotNull { path ->
            val file = resolve(workspace, path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
        if (frames.isEmpty()) return ToolResult(text = "No readable frames were found.", ok = false)
        val target = resolve(workspace, output)
        target.parentFile?.mkdirs()
        val scale = width.toFloat() / maxOf(frames.maxOf { it.width }, 1)
        val outW = (frames[0].width * scale).toInt().coerceAtLeast(2)
        val outH = (frames[0].height * scale).toInt().coerceAtLeast(2)
        val scaled = frames.map { Bitmap.createScaledBitmap(it, outW, outH, true) }
        GifEncoder(target.outputStream(), outW, outH, fps).let { encoder ->
            scaled.forEach { encoder.writeFrame(it) }
            encoder.finish()
            encoder.close()
        }
        scaled.forEach { it.recycle() }
        frames.forEach { it.recycle() }
        return ToolResult(
            text = "GIF written: ${target.name} (${outW}×$outH, ${frames.size} frames, $fps fps).",
            data = null
        )
    }

    // ---- MP4 --------------------------------------------------------------
    fun mp4(
        workspace: File,
        framePaths: List<String>,
        output: String,
        fps: Int,
        bitrate: Int
    ): ToolResult {
        val frames = framePaths.mapNotNull { path ->
            val file = resolve(workspace, path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
        if (frames.isEmpty()) return ToolResult(text = "No readable frames were found.", ok = false)
        val target = resolve(workspace, output)
        target.parentFile?.mkdirs()
        val width = frames.minOf { it.width } and 1.inv()
        val height = frames.minOf { it.height } and 1.inv()
        if (width < 16 || height < 16) {
            frames.forEach { it.recycle() }
            return ToolResult(text = "Frames are too small to encode.", ok = false)
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        return try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val writer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer

            fun drain() {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = writer.addTrack(encoder.outputFormat)
                        writer.start()
                        muxerStarted = true
                        return
                    }
                    if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            val buffer = encoder.getOutputBuffer(index)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                writer.writeSampleData(trackIndex, buffer, info)
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            var presentation = 0L
            for (bitmap in frames) {
                val scaled = if (bitmap.width == width && bitmap.height == height) bitmap
                else Bitmap.createScaledBitmap(bitmap, width, height, true)
                var index = encoder.dequeueInputBuffer(10_000)
                var guard = 0
                while (index < 0 && guard < 50) {
                    drain()
                    index = encoder.dequeueInputBuffer(10_000)
                    guard++
                }
                if (index >= 0) {
                    val image = encoder.getInputImage(index)
                    if (image != null) {
                        fillYuv(image, scaled, width, height)
                        encoder.queueInputBuffer(index, 0, image.width * image.height * 3 / 2, presentation, 0)
                    } else {
                        encoder.queueInputBuffer(index, 0, 0, presentation, 0)
                    }
                }
                presentation += 1_000_000L / fps
                drain()
                if (scaled !== bitmap) scaled.recycle()
            }
            var guard = 0
            while (guard < 200) {
                drain()
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                guard++
            }
            frames.forEach { it.recycle() }
            ToolResult(text = "MP4 written: ${target.name} (${width}x$height, ${frames.size} frames, $fps fps).")
        } catch (t: Throwable) {
            frames.forEach { it.recycle() }
            ToolResult(
                text = "MP4 encoding failed on this device: ${t.message}. Use video_gif instead.",
                ok = false
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun fillYuv(image: android.media.Image, bitmap: Bitmap, width: Int, height: Int) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPixel = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPixel = vPlane.pixelStride
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yBuffer.put(row * yRow + column * yPixel, y.coerceIn(0, 255).toByte())
                if (row % 2 == 0 && column % 2 == 0) {
                    val chromaIndex = (row / 2) * uRow + (column / 2) * uPixel
                    uBuffer.put(chromaIndex, u.coerceIn(0, 255).toByte())
                    vBuffer.put((row / 2) * vRow + (column / 2) * vPixel, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    // ---- probe / extract --------------------------------------------------
    fun probe(path: String): ToolResult {
        val file = File(path)
        if (!file.exists()) return ToolResult(text = "'$path' does not exist.", ok = false)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            ToolResult(
                text = "$path · ${width}×$height · ${duration / 1000.0}s · ${file.length() / 1024} KB" +
                    (frameRate?.let { " · $it fps" } ?: ""),
                data = null
            )
        } catch (t: Throwable) {
            ToolResult(text = "Could not read '$path': ${t.message}", ok = false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun extractFrames(path: String, outputDir: String, fps: Int, count: Int): ToolResult {
        val file = File(path)
        if (!file.exists()) return ToolResult(text = "'$path' does not exist.", ok = false)
        val retriever = MediaMetadataRetriever()
        val directory = File(outputDir)
        directory.mkdirs()
        val written = mutableListOf<String>()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val step = if (fps > 0) (1_000_000L / fps) else 1_000_000L / 12
            var time = 0L
            var index = 0
            while (time <= duration && index < count.coerceIn(1, 400)) {
                val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val target = File(directory, "frame_%04d.png".format(index + 1))
                    target.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 95, it) }
                    written.add(target.absolutePath)
                    frame.recycle()
                    index++
                }
                time += step
            }
            ToolResult(text = "Extracted ${written.size} frames to ${directory.absolutePath}")
        } catch (t: Throwable) {
            ToolResult(text = "Could not extract frames: ${t.message}", ok = false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolve(workspace: File, raw: String): File {
        val candidate = File(raw)
        return if (candidate.isAbsolute) candidate else File(workspace, raw)
    }
}
