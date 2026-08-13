package io.github.bossincrypto.velox

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.bossincrypto.velox.databinding.ItemVideoBinding
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One row of the library, straight out of MediaStore. */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * MediaStore access and thumbnail loading.
 *
 * ponytail: no Coil/Glide and no coroutines dependency. A two-thread pool plus an LruCache
 * is ~40 lines and keeps the APK and the dependency graph small; image loading here is one
 * fixed-size thumbnail per row, which is exactly the case those libraries are overkill for.
 */
object VideoLibrary {

    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "velox-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** ~8 MB of decoded thumbnails, or an eighth of the heap, whichever is smaller. */
    private val cache = object : LruCache<Long, Bitmap>(
        minOf(8 * 1024, (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt())
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    private val thumbSize = Size(320, 180)

    fun query(context: Context): List<VideoItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val out = ArrayList<VideoItem>(64)
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(nameCol) ?: "",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                )
            }
        }
        return out
    }

    fun submit(task: () -> Unit): Future<*> = io.submit(task)

    fun cachedThumb(id: Long): Bitmap? = cache.get(id)

    fun loadThumb(context: Context, item: VideoItem): Bitmap? {
        cache.get(item.id)?.let { return it }
        return runCatching {
            context.contentResolver.loadThumbnail(item.uri, thumbSize, null)
        }.getOrNull()?.also { cache.put(item.id, it) }
    }
}

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
) : ListAdapter<VideoItem, VideoAdapter.Holder>(DIFF) {

    init {
        // Stable ids let RecyclerView skip rebinding rows that did not change.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) = holder.recycle()

    class Holder(
        private val binding: ItemVideoBinding,
        private val onClick: (VideoItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var pending: Future<*>? = null
        private var boundId = -1L

        fun bind(item: VideoItem) {
            boundId = item.id
            binding.title.text = item.title
            binding.meta.text = Format.duration(item.durationMs) + "  ·  " + Format.size(item.sizeBytes)
            binding.root.setOnClickListener { onClick(item) }

            val cached = VideoLibrary.cachedThumb(item.id)
            if (cached != null) {
                binding.thumb.setImageBitmap(cached)
                return
            }
            binding.thumb.setImageDrawable(null)
            val ctx = binding.root.context.applicationContext
            pending = VideoLibrary.submit {
                val bmp = VideoLibrary.loadThumb(ctx, item) ?: return@submit
                binding.thumb.post {
                    // The row may have been recycled onto a different video while we decoded.
                    if (boundId == item.id) binding.thumb.setImageBitmap(bmp)
                }
            }
        }

        fun recycle() {
            pending?.cancel(false)
            pending = null
            boundId = -1L
            binding.thumb.setImageDrawable(null)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
