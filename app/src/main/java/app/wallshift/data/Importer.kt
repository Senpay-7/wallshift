package app.wallshift.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Copies picked images into internal storage, already downscaled to the screen resolution
 * (JPEG 90), plus a small WebP thumbnail for the grid. Runs in the background with bounded
 * parallelism so big photos never pile up in memory; the UI only sees placeholders and then
 * the finished thumbnail.
 */
class Importer(
    context: Context,
    private val store: WallpaperStore,
    private val scope: CoroutineScope,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(PARALLELISM)

    /** Screen size, portrait-normalised: (short side, long side). */
    private val screen: Pair<Int, Int> by lazy {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val a = m.widthPixels.coerceAtLeast(720)
        val b = m.heightPixels.coerceAtLeast(720)
        min(a, b) to max(a, b)
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val now = System.currentTimeMillis()
        val jobs = uris.mapIndexed { i, uri -> PendingItem(newId(), now + i) to uri }
        store.addPending(jobs.map { it.first })
        for ((p, uri) in jobs) {
            scope.launch(io) {
                // Any failure (unreadable file, OOM on a giant image, disk full) just drops this one.
                val ok = try {
                    store.snapshot() // index (and orphan sweep) must be done before we write files
                    prepare(uri, p.id) && run { store.commit(WallItem(p.id, p.added)); true }
                } catch (t: Throwable) {
                    false
                }
                if (!ok) {
                    store.imageFile(p.id).delete()
                    store.thumbFile(p.id).delete()
                    store.dropPending(p.id)
                }
            }
        }
    }

    private fun prepare(uri: Uri, id: String): Boolean {
        val full = decodeForScreen(uri) ?: return false
        try {
            store.wallsDir.mkdirs()
            store.thumbsDir.mkdirs()
            writeAtomically(store.imageFile(id)) { full.compress(Bitmap.CompressFormat.JPEG, 90, it) }

            val s = THUMB_SIZE.toFloat() / min(full.width, full.height)
            val thumb = if (s < 1f) {
                Bitmap.createScaledBitmap(
                    full, (full.width * s).roundToInt().coerceAtLeast(1),
                    (full.height * s).roundToInt().coerceAtLeast(1), true,
                )
            } else full
            try {
                writeAtomically(store.thumbFile(id)) { thumb.compress(webp(), 80, it) }
            } finally {
                if (thumb !== full) thumb.recycle()
            }
            return true
        } finally {
            full.recycle()
        }
    }

    /** Decodes [uri] straight to a size that just covers the screen (never upscales). */
    private fun decodeForScreen(uri: Uri): Bitmap? {
        val (sw, sh) = screen
        return if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF orientation and samples during decode.
            val src = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val (w, h) = coverSize(info.size.width, info.size.height, sw, sh)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            decodeLegacy(uri, sw, sh)
        }
    }

    private fun decodeLegacy(uri: Uri, sw: Int, sh: Int): Bitmap? {
        val rotation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val swapped = rotation == 90 || rotation == 270
        val ow = if (swapped) bounds.outHeight else bounds.outWidth
        val oh = if (swapped) bounds.outWidth else bounds.outHeight
        val (tw, th) = coverSize(ow, oh, sw, sh)

        var sample = 1
        while (ow / (sample * 2) >= tw && oh / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rw = if (swapped) raw.height else raw.width
        val rh = if (swapped) raw.width else raw.height
        val scale = min(tw.toFloat() / rw, th.toFloat() / rh)
        if (rotation == 0 && scale >= 0.999f) return raw
        val m = Matrix().apply {
            if (scale < 0.999f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
            if (it !== raw) raw.recycle()
        }
    }

    private inline fun writeAtomically(target: File, write: (FileOutputStream) -> Boolean) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = FileOutputStream(tmp).use { out -> write(out).also { out.fd.sync() } }
        if (!ok || !tmp.renameTo(target)) {
            tmp.delete()
            error("write failed: ${target.name}")
        }
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    companion object {
        const val THUMB_SIZE = 300
        private const val PARALLELISM = 2

        /** Smallest size >= screen in both dimensions (portrait-normalised), capped at the original. */
        fun coverSize(iw: Int, ih: Int, sw: Int, sh: Int): Pair<Int, Int> {
            val scale = min(1f, max(sw.toFloat() / iw, sh.toFloat() / ih))
            return (iw * scale).roundToInt().coerceAtLeast(1) to (ih * scale).roundToInt().coerceAtLeast(1)
        }

        private val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray()
        private val rng = java.security.SecureRandom()
        fun newId(): String = String(CharArray(12) { alphabet[rng.nextInt(alphabet.size)] })
    }
}
