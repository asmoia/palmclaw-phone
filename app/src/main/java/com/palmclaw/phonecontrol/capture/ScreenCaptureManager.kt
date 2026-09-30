package com.palmclaw.phonecontrol.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.palmclaw.phonecontrol.core.Logs
import java.io.ByteArrayOutputStream

/** 一次截图结果：JPEG 字节 + 实际像素尺寸（AI 看到、并参与坐标换算的坐标系） */
data class Jpeg(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * MediaProjection → VirtualDisplay → ImageReader → Bitmap。
 * 持续保留「最近一帧」，captureJpeg() 随时能取。
 */
class ScreenCaptureManager(private val ctx: Context) {

    @Volatile private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var latest: Bitmap? = null

    /** 帧交换锁：捕获线程换帧 与 截图线程读帧 互斥，防止 Bitmap recycle 竞态 */
    private val frameLock = Any()

    /** 最近一次返回的截图 JPEG 的实际像素尺寸 —— tap/swipe 的「截图坐标」以它为基准换算（见 ToolRegistry.scaleFactor） */
    @Volatile var lastJpegWidth = 0
        private set
    @Volatile var lastJpegHeight = 0
        private set

    /** 截图（缩放后）的像素尺寸 —— AI 看到的坐标系 */
    @Volatile var imageWidth = 0
        private set
    @Volatile var imageHeight = 0
        private set

    /** 屏幕真实像素尺寸 —— 手势真正使用的坐标系 */
    @Volatile var realWidth = 0
        private set
    @Volatile var realHeight = 0
        private set

    @Volatile private var density = 0

    val running: Boolean get() = reader != null

    fun start(mp: MediaProjection, maxWidth: Int) {
        stop()

        val dm = ctx.resources.displayMetrics
        realWidth = dm.widthPixels
        realHeight = dm.heightPixels
        density = if (dm.densityDpi > 0) dm.densityDpi else 320

        val target = maxWidth.coerceIn(240, 4096)
        val scale = if (realWidth > target) target.toFloat() / realWidth else 1f
        imageWidth = (realWidth * scale).toInt().coerceAtLeast(2)
        imageHeight = (realHeight * scale).toInt().coerceAtLeast(2)

        val ht = HandlerThread("apkmcp-capture").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        val r = ImageReader.newInstance(
            imageWidth, imageHeight, PixelFormat.RGBA_8888, 2
        )
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img: Image? = try {
                ir.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (img != null) {
                try {
                    val bmp = toBitmap(img)
                    synchronized(frameLock) {
                        val old = latest
                        latest = bmp
                        if (old != null && !old.isRecycled) old.recycle()
                    }
                } catch (t: Throwable) {
                    Logs.add("解码帧失败: ${t.message}")
                } finally {
                    try {
                        img.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }, h)

        projection = mp
        try {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logs.add("MediaProjection 被系统停止")
                    release()
                }
            }, h)
        } catch (_: Throwable) {
        }

        virtualDisplay = try {
            mp.createVirtualDisplay(
                "apkmcp-vd",
                imageWidth,
                imageHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                h
            )
        } catch (t: Throwable) {
            Logs.add("创建虚拟屏幕失败: ${t.message}")
            null
        }

        Logs.add("屏幕捕获已启动 ${imageWidth}x${imageHeight} (真实 ${realWidth}x${realHeight})")
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * imageWidth
        val padded = Bitmap.createBitmap(
            imageWidth + rowPadding / pixelStride,
            imageHeight,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        if (padded.width == imageWidth) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight)
        padded.recycle()
        return cropped
    }

    /** 等第一帧，最多 timeoutMs 毫秒 */
    fun awaitFirstFrame(timeoutMs: Long = 2500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (latest != null) return true
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                return latest != null
            }
        }
        return latest != null
    }

    fun captureJpeg(maxWidth: Int, quality: Int): Jpeg? {
        val target = maxWidth.coerceIn(240, 4096)
        synchronized(frameLock) {
            val src = latest ?: return null
            if (src.isRecycled) return null

            var bmp: Bitmap = src
            var scaled: Bitmap? = null
            var w = src.width
            var h = src.height
            if (src.width > target) {
                h = (src.height.toFloat() * target / src.width).toInt().coerceAtLeast(2)
                scaled = Bitmap.createScaledBitmap(src, target, h, true)
                bmp = scaled
                w = target
            }
            return try {
                val bos = ByteArrayOutputStream(256 * 1024)
                bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), bos)
                lastJpegWidth = w
                lastJpegHeight = h
                Jpeg(bos.toByteArray(), w, h)
            } catch (t: Throwable) {
                Logs.add("压缩截图失败: ${t.message}")
                null
            } finally {
                scaled?.recycle()
            }
        }
    }

    fun captureBase64(maxWidth: Int, quality: Int): String? {
        val cap = captureJpeg(maxWidth, quality) ?: return null
        return Base64.encodeToString(cap.bytes, Base64.NO_WRAP)
    }

    fun release() {
        synchronized(frameLock) {
            val bmp = latest
            latest = null
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        }
        lastJpegWidth = 0
        lastJpegHeight = 0
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        release()
        try {
            thread?.quitSafely()
        } catch (_: Throwable) {
        }
        thread = null
        handler = null
    }
}
