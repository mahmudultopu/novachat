package app.novachat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class MediaException(message: String) : Exception(message)

/** A file ready to send: copied into app storage, with its description. */
class PreparedMedia(val file: File, val attachment: Attachment)

/**
 * Turns picked photos, videos, documents and recordings into files in the
 * app's private storage, and loads them back for display. Photos are resized
 * and re-encoded, which keeps them small and strips location metadata.
 */
class MediaFiles(private val context: Context) {
    val dir: File get() = File(context.filesDir, "media").apply { mkdirs() }

    fun newFile(extension: String) = File(dir, "${UUID.randomUUID()}.$extension")

    suspend fun preparePhoto(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(uri, MAX_PHOTO_PX) ?: throw MediaException("That photo couldn't be opened")
        val upright = rotateFromExif(uri, bitmap)
        val scaled = scaleDown(upright, MAX_PHOTO_PX)
        val file = newFile("jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        PreparedMedia(
            file,
            Attachment(
                kind = AttachmentKind.image,
                mime = "image/jpeg",
                size = file.length(),
                width = scaled.width,
                height = scaled.height,
                thumb = thumbnail(scaled),
            ),
        )
    }

    suspend fun prepareVideo(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val file = copy(uri, "mp4")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val frame = retriever.getFrameAtTime(0)
            PreparedMedia(
                file,
                Attachment(
                    kind = AttachmentKind.video,
                    mime = context.contentResolver.getType(uri) ?: "video/mp4",
                    size = file.length(),
                    width = w,
                    height = h,
                    durationMs = duration,
                    thumb = frame?.let { thumbnail(it) },
                ),
            )
        } catch (e: RuntimeException) {
            file.delete()
            throw MediaException("That video couldn't be opened")
        } finally {
            retriever.release()
        }
    }

    suspend fun prepareDocument(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "document"
        val file = copy(uri, name.substringAfterLast('.', "bin").take(8))
        PreparedMedia(
            file,
            Attachment(
                kind = AttachmentKind.file,
                mime = context.contentResolver.getType(uri) ?: "application/octet-stream",
                size = file.length(),
                name = name.take(200),
            ),
        )
    }

    /** A square, center-cropped JPEG for a profile photo. */
    suspend fun prepareAvatar(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(uri, AVATAR_PX * 2) ?: throw MediaException("That photo couldn't be opened")
        val upright = rotateFromExif(uri, bitmap)
        val side = minOf(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val scaled = if (side > AVATAR_PX) Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true) else square
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /** Where a downloaded profile photo is cached. Photo ids never change content. */
    fun avatarFile(id: String) = File(File(context.cacheDir, "avatars").apply { mkdirs() }, "$id.jpg")

    fun prepareVoice(file: File, durationMs: Long) = PreparedMedia(
        file,
        Attachment(kind = AttachmentKind.voice, mime = "audio/mp4", size = file.length(), durationMs = durationMs),
    )

    /** Extension to save a downloaded attachment under. */
    fun extensionFor(a: Attachment): String = when (a.kind) {
        AttachmentKind.image -> "jpg"
        AttachmentKind.video -> "mp4"
        AttachmentKind.voice -> "m4a"
        AttachmentKind.file -> a.name?.substringAfterLast('.', "bin")?.take(8) ?: "bin"
    }

    /** Deletes every media file (log out). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Loads a photo, or a video's first frame, sized for display. */
    suspend fun loadPreview(path: String, video: Boolean, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$path@$maxPx"
        cache.get(key)?.let { return@withContext it }
        val bitmap = if (video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                r.getFrameAtTime(0)?.let { scaleDown(it, maxPx) }
            } catch (e: RuntimeException) {
                null
            } finally {
                r.release()
            }
        } else {
            decodeSampled(Uri.fromFile(File(path)), maxPx)
        }
        bitmap?.also { cache.put(key, it) }
    }

    fun decodeThumb(thumb: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(thumb)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun copy(uri: Uri, extension: String): File {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > MAX_FILE_BYTES) throw MediaException("Files can be at most 16 MB")
        val file = newFile(extension)
        val input = context.contentResolver.openInputStream(uri) ?: throw MediaException("That file couldn't be opened")
        input.use { inp ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) {
                        file.delete()
                        throw MediaException("Files can be at most 16 MB")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        return file
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun rotateFromExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val f = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt().coerceAtLeast(1), (bitmap.height * f).roundToInt().coerceAtLeast(1), true)
    }

    private fun thumbnail(bitmap: Bitmap): String {
        val small = scaleDown(bitmap, 40)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 50, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    companion object {
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_PHOTO_PX = 1600
        private const val AVATAR_PX = 640
    }
}
