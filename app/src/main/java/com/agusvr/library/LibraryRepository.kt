package com.agusvr.library

import com.agusvr.runtime.AgusBus
import com.agusvr.storage.AgusPaths
import com.agusvr.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local library persistence (module: AgusLibrary).
 * JSON file at AgusVR/Library/library.json; exposed as a StateFlow so panels
 * refresh automatically after installs/renames/deletes.
 */
object LibraryRepository {

    private const val TAG = "LibraryRepo"
    private val file: File get() = File(AgusPaths.library, "library.json")

    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items

    private var loaded = false

    @Synchronized
    fun load(): List<LibraryItem> {
        if (loaded) return _items.value
        val list = mutableListOf<LibraryItem>()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += fromJson(o)
                }
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "load failed — starting empty", t)
        }
        // Prune entries whose backing file vanished (corruption guard).
        val valid = list.filter { item ->
            item.type == LibraryType.WEBAPP ||
                (item.path != null && File(item.path).exists())
        }
        if (valid.size != list.size) {
            Logx.w(TAG, "pruned ${list.size - valid.size} entries with missing files")
        }
        _items.value = valid
        loaded = true
        persist()
        return valid
    }

    @Synchronized
    fun persist() {
        try {
            val arr = JSONArray()
            for (item in _items.value) arr.put(toJson(item))
            AgusPaths.ensure(file.parentFile!!)
            file.writeText(arr.toString(1))
        } catch (t: Throwable) {
            Logx.e(TAG, "persist failed", t)
        }
    }

    @Synchronized
    fun add(item: LibraryItem): LibraryItem {
        load()
        val list = _items.value.toMutableList()
        list.removeAll { it.id == item.id }
        list.add(0, item)
        _items.value = list
        persist()
        AgusBus.post(com.agusvr.runtime.AgusEvent.LibraryChanged)
        if (item.isModel) AgusBus.post(com.agusvr.runtime.AgusEvent.ModelsChanged)
        return item
    }

    @Synchronized
    fun update(id: String, transform: (LibraryItem) -> LibraryItem): LibraryItem? {
        load()
        var updated: LibraryItem? = null
        _items.value = _items.value.map {
            if (it.id == id) transform(it).also { u -> updated = u } else it
        }
        persist()
        AgusBus.post(com.agusvr.runtime.AgusEvent.LibraryChanged)
        return updated
    }

    @Synchronized
    fun remove(id: String, deleteFiles: Boolean = true) {
        load()
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (deleteFiles && item.path != null) {
            try {
                val f = File(item.path)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (t: Throwable) {
                Logx.w(TAG, "file delete failed for $id", t)
            }
        }
        _items.value = _items.value.filterNot { it.id == id }
        persist()
        AgusBus.post(com.agusvr.runtime.AgusEvent.LibraryChanged)
        if (item.isModel) AgusBus.post(com.agusvr.runtime.AgusEvent.ModelsChanged)
    }

    fun find(id: String): LibraryItem? {
        load()
        return _items.value.firstOrNull { it.id == id }
    }

    fun findByOrigin(originId: String): LibraryItem? {
        load()
        return _items.value.firstOrNull { it.originId == originId }
    }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    /** Runnable entry URL for a GAME item (file:// for WebView). */
    fun entryUrl(item: LibraryItem): String? {
        val path = item.path ?: return null
        val dir = File(path)
        val entry = item.entryFile
        val target = when {
            dir.isDirectory && entry != null -> File(dir, entry)
            dir.isDirectory -> File(dir, "index.html")
            else -> dir
        }
        return if (target.exists()) "file://" + target.absolutePath else null
    }

    private fun toJson(i: LibraryItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("name", i.name); put("type", i.type)
        put("path", i.path ?: JSONObject.NULL)
        put("entryFile", i.entryFile ?: JSONObject.NULL)
        put("url", i.url ?: JSONObject.NULL)
        put("sizeBytes", i.sizeBytes); put("dateAdded", i.dateAdded)
        put("cover", i.cover ?: JSONObject.NULL)
        put("description", i.description ?: JSONObject.NULL)
        put("source", i.source); put("originId", i.originId ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject): LibraryItem = LibraryItem(
        id = o.optString("id"),
        name = o.optString("name"),
        type = o.optString("type", LibraryType.GAME),
        path = o.optString("path").takeIf { it.isNotEmpty() && it != "null" },
        entryFile = o.optString("entryFile").takeIf { it.isNotEmpty() && it != "null" },
        url = o.optString("url").takeIf { it.isNotEmpty() && it != "null" },
        sizeBytes = o.optLong("sizeBytes"),
        dateAdded = o.optLong("dateAdded", System.currentTimeMillis()),
        cover = o.optString("cover").takeIf { it.isNotEmpty() && it != "null" },
        description = o.optString("description").takeIf { it.isNotEmpty() && it != "null" },
        source = o.optString("source", "import"),
        originId = o.optString("originId").takeIf { it.isNotEmpty() && it != "null" }
    )
}
