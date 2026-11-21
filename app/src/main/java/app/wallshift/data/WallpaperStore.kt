package app.wallshift.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One prepared wallpaper. Files live in filesDir: walls/<id>.jpg and thumbs/<id>.webp. */
data class WallItem(val id: String, val added: Long)

/** An import in progress — shown as a placeholder cell with the same key it will have when done. */
data class PendingItem(val id: String, val added: Long)

/**
 * Library index: a small JSON file in internal storage, mirrored into a StateFlow.
 * Read once per process, asynchronously; all writes go through [mutex].
 */
class WallpaperStore(context: Context, private val scope: CoroutineScope) {

    val wallsDir = File(context.filesDir, "walls")
    val thumbsDir = File(context.filesDir, "thumbs")
    private val indexFile = AtomicFile(File(context.filesDir, "index.json"))
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<WallItem>?>(null)
    /** null until the index has been read. Sorted by [WallItem.added] ascending. */
    val items: StateFlow<List<WallItem>?> = _items.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingItem>>(emptyList())
    val pending: StateFlow<List<PendingItem>> = _pending.asStateFlow()

    fun imageFile(id: String) = File(wallsDir, "$id.jpg")
    fun thumbFile(id: String) = File(thumbsDir, "$id.webp")

    /** Kicks off the async index read; returns immediately. */
    fun preload() {
        if (_items.value == null) scope.launch { snapshot() }
    }

    /** Current library, reading the index first if needed. */
    suspend fun snapshot(): List<WallItem> {
        _items.value?.let { return it }
        return mutex.withLock {
            _items.value ?: withContext(Dispatchers.IO) {
                readIndex().also { deleteOrphans(it) }
            }.also { _items.value = it }
        }
    }

    fun addPending(list: List<PendingItem>) = _pending.update { it + list }

    fun dropPending(id: String) = _pending.update { list -> list.filterNot { it.id == id } }

    /** Commits a fully prepared item (both files already in place). */
    suspend fun commit(item: WallItem) {
        snapshot()
        mutex.withLock {
            val current = _items.value.orEmpty()
            if (current.none { it.id == item.id }) {
                val next = ArrayList<WallItem>(current.size + 1)
                val pos = current.indexOfFirst { it.added > item.added }.let { if (it < 0) current.size else it }
                next.addAll(current.subList(0, pos))
                next.add(item)
                next.addAll(current.subList(pos, current.size))
                withContext(Dispatchers.IO) { writeIndex(next) }
                _items.value = next
            }
            dropPending(item.id)
        }
    }

    /** Removes items from the index (UI updates immediately), then deletes their files. */
    suspend fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        snapshot()
        mutex.withLock {
            val current = _items.value.orEmpty()
            val next = current.filterNot { it.id in ids }
            if (next.size != current.size) {
                _items.value = next
                withContext(Dispatchers.IO) { writeIndex(next) }
            }
        }
        withContext(Dispatchers.IO) {
            ids.forEach { imageFile(it).delete(); thumbFile(it).delete() }
        }
    }

    private fun readIndex(): List<WallItem> {
        if (!indexFile.baseFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(String(indexFile.readFully(), Charsets.UTF_8))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                WallItem(o.getString("id"), o.optLong("t"))
            }.sortedBy { it.added }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(list: List<WallItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.added)) }
        val out = indexFile.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(out)
        } catch (e: Exception) {
            indexFile.failWrite(out)
            throw e
        }
    }

    /** Removes files left behind by an import that was killed before it was committed. */
    private fun deleteOrphans(items: List<WallItem>) {
        val known = items.mapTo(HashSet()) { it.id }
        val busy = _pending.value.mapTo(HashSet()) { it.id }
        fun sweep(dir: File) = dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.')
            if (id !in known && id !in busy) f.delete()
        }
        sweep(wallsDir)
        sweep(thumbsDir)
    }
}
