package com.agusvr.modellab

import com.agusvr.storage.AgusPaths
import com.agusvr.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ModelProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverPath: String?,
    val nodes: JSONArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("cover", coverPath ?: JSONObject.NULL)
        put("nodes", nodes)
    }
}

/**
 * Model Lab project storage (module: AgusModelLab + AgusStorage).
 * Projects live in AgusVR/Projects: project.json + optional cover.png.
 */
object ProjectRepository {

    private fun dir(): File = AgusPaths.projects

    fun all(): List<ModelProject> {
        val out = mutableListOf<ModelProject>()
        val root = dir()
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            try {
                val json = File(d, "project.json")
                if (!json.exists()) return@forEach
                val obj = JSONObject(json.readText())
                out += ModelProject(
                    id = obj.optString("id", d.name),
                    name = obj.optString("name", d.name),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt", obj.optLong("createdAt")),
                    coverPath = obj.optString("cover").takeIf { it.isNotEmpty() && it != "null" },
                    nodes = obj.optJSONArray("nodes") ?: JSONArray()
                )
            } catch (t: Throwable) {
                Logx.w("Projects", "bad project ${d.name}", t)
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun save(name: String, nodes: List<Map<String, Any?>>, cover: ByteArray?, existingId: String?): ModelProject? {
        return try {
            val id = existingId ?: "prj_" + System.currentTimeMillis().toString(36)
            val d = File(dir(), id)
            d.mkdirs()
            val nodesArr = JSONArray()
            for (m in nodes) nodesArr.put(JSONObject(m))
            val previous = File(d, "project.json").takeIf { it.exists() }?.let {
                runCatching { JSONObject(it.readText()) }.getOrNull()
            }
            val coverRel: String? = if (cover != null) {
                File(d, "cover.png").writeBytes(cover)
                "cover.png"
            } else previous?.optString("cover")?.takeIf { it.isNotEmpty() && it != "null" }

            val project = ModelProject(
                id = id,
                name = name,
                createdAt = previous?.optLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                coverPath = coverRel,
                nodes = nodesArr
            )
            File(d, "project.json").writeText(project.toJson().toString(2))
            project
        } catch (t: Throwable) {
            Logx.e("Projects", "save failed", t)
            null
        }
    }

    fun delete(project: ModelProject): Boolean = try {
        File(dir(), project.id).deleteRecursively()
    } catch (t: Throwable) {
        false
    }

    fun coverFile(project: ModelProject): File? =
        project.coverPath?.let { File(File(dir(), project.id), it) }?.takeIf { it.exists() }
}
