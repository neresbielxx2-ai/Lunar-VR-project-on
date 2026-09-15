package com.agusvr.store

import android.content.Context
import com.agusvr.library.LibraryRepository
import com.agusvr.util.Logx
import org.json.JSONArray
import org.json.JSONObject

/** One AGUS STORE entry (module: AgusStore). */
data class CatalogItem(
    val id: String,
    val name: String,
    val tagline: String,
    val category: String,
    val type: String,          // builtin_html | builtin_models | remote_zip | web
    val asset: String?,
    val url: String?,
    val entry: String?,
    val sizeLabel: String,
    val accent: String,
    val author: String,
    val license: String
) {
    val isWeb: Boolean get() = type == "web"
    val isBuiltin: Boolean get() = type.startsWith("builtin")
    val isRemote: Boolean get() = type == "remote_zip"
}

/** Parses the bundled store catalog (assets/store_catalog.json). */
object StoreRepository {

    private var items: List<CatalogItem> = emptyList()
    private var categories: List<String> = emptyList()

    fun load(context: Context): List<CatalogItem> {
        if (items.isNotEmpty()) return items
        items = try {
            val json = context.assets.open("store_catalog.json").bufferedReader().use { it.readText() }
            parse(json)
        } catch (t: Throwable) {
            Logx.e("Store", "catalog parse failed", t)
            emptyList()
        }
        categories = (listOf("Tudo") + items.map { it.category }.distinct()).distinct()
        return items
    }

    fun getCategories(): List<String> {
        if (categories.isEmpty()) categories = listOf("Tudo")
        return categories
    }

    fun parse(json: String): List<CatalogItem> {
        val root = JSONObject(json)
        val arr: JSONArray = root.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<CatalogItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += CatalogItem(
                id = o.getString("id"),
                name = o.getString("name"),
                tagline = o.optString("tagline"),
                category = o.optString("category", "Geral"),
                type = o.getString("type"),
                asset = o.optString("asset").takeIf { it.isNotEmpty() },
                url = o.optString("url").takeIf { it.isNotEmpty() },
                entry = o.optString("entry").takeIf { it.isNotEmpty() },
                sizeLabel = o.optString("sizeLabel", "—"),
                accent = o.optString("accent", "#4DE8FF"),
                author = o.optString("author", "—"),
                license = o.optString("license", "—")
            )
        }
        return out
    }

    fun installed(id: String): Boolean = LibraryRepository.findByOrigin(id) != null

    fun installedItem(id: String) = LibraryRepository.findByOrigin(id)
}
