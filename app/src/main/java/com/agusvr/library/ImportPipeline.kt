package com.agusvr.library

import android.content.Context
import android.net.Uri
import com.agusvr.runtime.AgusBus
import com.agusvr.runtime.AgusEvent
import com.agusvr.storage.AgusPaths
import com.agusvr.storage.FileOps
import com.agusvr.util.Logx
import com.agusvr.util.dirSize
import com.agusvr.util.extOf
import java.io.File

/**
 * The honest import/install pipeline (modules: AgusLibrary + AgusStore).
 *
 * Supported and REALLY executable:
 *  - .html/.htm           → runs in the internal WebView player
 *  - .zip containing HTML → extracted, main file detected, runs in WebView
 *  - web URLs             → stored as WEBAPP, open in the Agus Browser
 *  - .obj/.glb/.gltf      → 3D models for the AGUS MODEL LAB
 *  - .png/.jpg/.jpeg/.webp → images (covers/art)
 *
 * Everything else is refused with a clear explanation — nothing pretends to
 * run when it can't (spec §9): .java needs a JVM/compiler, .apk needs the
 * Android installer, etc.
 */
object ImportPipeline {

    private const val TAG = "Import"

    sealed class Outcome {
        data class Installed(val item: LibraryItem) : Outcome()
        data class Unsupported(val extension: String, val explanation: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    val RUNNABLE_WEB_EXTS = setOf("html", "htm")
    val MODEL_EXTS = setOf("obj", "glb", "gltf")
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
    val TEXT_EXTS = setOf("txt", "md", "json", "log", "csv", "xml")

    // ------------------------------------------------------------------
    // Local file import (SAF)
    // ------------------------------------------------------------------

    fun importUri(context: Context, uri: Uri): Outcome = try {
        val name = FileOps.queryDisplayName(context, uri)
        val ext = extOf(name)
        when {
            ext in RUNNABLE_WEB_EXTS -> {
                val file = FileOps.importUri(context, uri, AgusPaths.games)
                installSingleHtml(file, name, "import")
            }
            ext == "zip" -> {
                val zip = FileOps.importUri(context, uri, AgusPaths.downloads)
                val out = installZip(zip, name.removeSuffix(".zip"), "import")
                zip.delete()
                out
            }
            ext in MODEL_EXTS -> importModel(context, uri, name)
            ext in IMAGE_EXTS -> {
                val file = FileOps.importUri(context, uri, AgusPaths.images)
                val item = LibraryItem(
                    id = LibraryRepository.newId(), name = file.nameWithoutExtension,
                    type = LibraryType.IMAGE, path = file.absolutePath,
                    sizeBytes = file.length(), source = "import"
                )
                LibraryRepository.add(item)
                AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_image, "Imagem importada", item.name, AgusEvent.ToastKind.SUCCESS))
                Outcome.Installed(item)
            }
            ext == "java" || ext == "kt" -> Outcome.Unsupported(
                ext,
                "Arquivos .$ext precisam de compilação e de um runtime (JVM/ART toolchain) que não está disponível dentro do Agus VR. " +
                    "Compile o projeto no Android Studio ou converta o conteúdo para HTML/WebApp para executá-lo aqui."
            )
            ext == "apk" -> Outcome.Unsupported(
                ext,
                "O Agus VR não instala APKs de terceiros por segurança. Use o instalador do próprio Android para este arquivo."
            )
            ext == "exe" || ext == "msi" || ext == "dmg" -> Outcome.Unsupported(
                ext, "Executáveis de desktop não rodam no Android."
            )
            ext == "pdf" -> Outcome.Unsupported(
                ext, "PDF não tem visualizador nativo no Agus VR ainda. Abra com um app de PDF do Android."
            )
            ext in TEXT_EXTS -> {
                val file = FileOps.importUri(context, uri, AgusPaths.downloads)
                val item = LibraryItem(
                    id = LibraryRepository.newId(), name = file.name,
                    type = LibraryType.GAME, path = file.absolutePath,
                    sizeBytes = file.length(), source = "import",
                    description = "texto (visualizador simples)"
                )
                // Text files are viewable but NOT runnable games — keep them out
                // of the library and let the file manager open them.
                file.delete()
                Outcome.Unsupported(ext, "Arquivos de texto podem ser abertos pelo Gerenciador de Arquivos, mas não são jogos executáveis.")
            }
            else -> Outcome.Unsupported(ext.ifEmpty { "?" }, "Formato não suportado pelo Agus VR.")
        }
    } catch (t: Throwable) {
        Logx.e(TAG, "import failed", t)
        Outcome.Failed(t.message ?: "Falha ao importar o arquivo.")
    }

    private fun importModel(context: Context, uri: Uri, name: String): Outcome {
        val file = FileOps.importUri(context, uri, AgusPaths.models)
        val item = LibraryItem(
            id = LibraryRepository.newId(), name = file.nameWithoutExtension,
            type = LibraryType.MODEL, path = file.absolutePath,
            sizeBytes = file.length(), source = "import",
            description = "modelo ${extOf(name).uppercase()}"
        )
        LibraryRepository.add(item)
        AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_modellab, "Modelo importado", item.name, AgusEvent.ToastKind.SUCCESS))
        return Outcome.Installed(item)
    }

    // ------------------------------------------------------------------
    // Single HTML install
    // ------------------------------------------------------------------

    fun installSingleHtml(file: File, displayName: String, source: String, originId: String? = null): Outcome {
        return try {
            if (!file.exists() || file.length() == 0L) {
                Outcome.Failed("O arquivo HTML está vazio ou inacessível.")
            } else if (!looksLikeHtml(file)) {
                Outcome.Failed("O arquivo não parece ser um HTML válido (integridade).")
            } else {
                val item = LibraryItem(
                    id = LibraryRepository.newId(),
                    name = displayName.removeSuffix(".html").removeSuffix(".htm"),
                    type = LibraryType.GAME,
                    path = file.absolutePath,
                    entryFile = null,
                    sizeBytes = file.length(),
                    source = source,
                    originId = originId,
                    description = "HTML executável no WebView interno"
                )
                LibraryRepository.add(item)
                AgusBus.post(
                    AgusEvent.Toast(
                        com.agusvr.R.drawable.ic_library, "Jogo adicionado à biblioteca",
                        item.name, AgusEvent.ToastKind.SUCCESS
                    )
                )
                Outcome.Installed(item)
            }
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: "Falha na instalação.")
        }
    }

    private fun looksLikeHtml(file: File): Boolean = try {
        val head = ByteArray(2048)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) false
        else {
            val s = String(head, 0, n).lowercase()
            s.contains("<!doctype html") || s.contains("<html") || s.contains("<body") || s.contains("<script")
        }
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // ZIP install (html project)
    // ------------------------------------------------------------------

    fun installZip(zip: File, displayName: String, source: String, originId: String? = null): Outcome {
        val id = LibraryRepository.newId()
        val dir = File(AgusPaths.games, id)
        return try {
            val main = FileOps.extractZip(zip, dir)
            if (main == null) {
                dir.deleteRecursively()
                Outcome.Failed("O ZIP não contém nenhum HTML executável (index.html ou similar).")
            } else {
                val rel = main.relativeTo(dir).path.replace('\\', '/')
                val item = LibraryItem(
                    id = id,
                    name = displayName,
                    type = LibraryType.GAME,
                    path = dir.absolutePath,
                    entryFile = rel,
                    sizeBytes = dirSize(dir),
                    source = source,
                    originId = originId,
                    description = "projeto web extraído de ZIP · entrada: $rel"
                )
                LibraryRepository.add(item)
                AgusBus.post(
                    AgusEvent.Toast(
                        com.agusvr.R.drawable.ic_library, "Jogo adicionado à biblioteca",
                        "${item.name} (ZIP extraído)", AgusEvent.ToastKind.SUCCESS
                    )
                )
                Outcome.Installed(item)
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "zip install failed", t)
            dir.deleteRecursively()
            Outcome.Failed(t.message ?: "Falha ao extrair o ZIP.")
        }
    }

    // ------------------------------------------------------------------
    // Builtin content (assets)
    // ------------------------------------------------------------------

    fun installBuiltinHtml(context: Context, assetPath: String, displayName: String, originId: String): Outcome {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val target = File(AgusPaths.games, "builtin_$originId")
            if (target.exists()) target.deleteRecursively()
            AgusPaths.ensure(target)
            val html = File(target, "index.html")
            html.writeBytes(bytes)
            val existing = LibraryRepository.findByOrigin(originId)
            if (existing != null) LibraryRepository.remove(existing.id, deleteFiles = false)
            val item = LibraryItem(
                id = LibraryRepository.newId(),
                name = displayName,
                type = LibraryType.GAME,
                path = target.absolutePath,
                entryFile = "index.html",
                sizeBytes = html.length(),
                source = "builtin",
                originId = originId,
                description = "conteúdo original do Agus VR"
            )
            LibraryRepository.add(item)
            AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_library, "Jogo adicionado à biblioteca", item.name, AgusEvent.ToastKind.SUCCESS))
            Outcome.Installed(item)
        } catch (t: Throwable) {
            Logx.e(TAG, "builtin install failed", t)
            Outcome.Failed(t.message ?: "Falha ao instalar o conteúdo embutido.")
        }
    }

    /** Installs the bundled sample 3D models (cube/sphere GLB + pyramid OBJ). */
    fun installBuiltinModels(context: Context): Outcome {
        return try {
            val names = listOf("agus_cube.glb", "agus_sphere.glb", "agus_pyramid.obj")
            var count = 0
            for (n in names) {
                val bytes = try {
                    context.assets.open("samples/$n").use { it.readBytes() }
                } catch (_: Throwable) {
                    continue
                }
                val dst = AgusPaths.unique(AgusPaths.models, n)
                dst.writeBytes(bytes)
                LibraryRepository.add(
                    LibraryItem(
                        id = LibraryRepository.newId(),
                        name = dst.nameWithoutExtension,
                        type = LibraryType.MODEL,
                        path = dst.absolutePath,
                        sizeBytes = dst.length(),
                        source = "builtin",
                        originId = "agus_sample_pack:$n",
                        description = "modelo de exemplo do Agus VR"
                    )
                )
                count++
            }
            if (count == 0) Outcome.Failed("Nenhum modelo de exemplo encontrado nos assets.")
            else {
                AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_modellab, "Modelos importados", "$count modelos de exemplo", AgusEvent.ToastKind.SUCCESS))
                Outcome.Installed(
                    LibraryItem(
                        id = "pack", name = "Agus Sample Pack", type = LibraryType.MODEL,
                        path = AgusPaths.models.absolutePath, source = "builtin"
                    )
                )
            }
        } catch (t: Throwable) {
            Outcome.Failed(t.message ?: "Falha ao instalar os modelos.")
        }
    }

    // ------------------------------------------------------------------
    // Remote (URL) install
    // ------------------------------------------------------------------

    /** Classifies a URL: returns a sane download file name or null when the URL is a web page. */
    fun classifyUrl(url: String): Pair<String, Boolean>? {
        val path = try {
            Uri.parse(url).path ?: ""
        } catch (_: Throwable) {
            ""
        }
        val name = path.substringAfterLast('/').ifEmpty { "download" }
        val ext = extOf(name)
        return when {
            ext == "zip" -> name to true
            ext in RUNNABLE_WEB_EXTS -> name to true
            ext in MODEL_EXTS -> name to true
            else -> null // treat as web page → WEBAPP entry
        }
    }

    fun installDownloadedFile(file: File, displayName: String, originId: String?): Outcome {
        val ext = extOf(file.name)
        return when {
            ext == "zip" -> installZip(file, displayName, "url", originId)
            ext in RUNNABLE_WEB_EXTS -> installSingleHtml(file, displayName, "url", originId)
            ext in MODEL_EXTS -> {
                val dst = AgusPaths.unique(AgusPaths.models, file.name)
                FileOps.moveFile(file, dst)
                val item = LibraryItem(
                    id = LibraryRepository.newId(), name = dst.nameWithoutExtension,
                    type = LibraryType.MODEL, path = dst.absolutePath,
                    sizeBytes = dst.length(), source = "url", originId = originId
                )
                LibraryRepository.add(item)
                AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_modellab, "Modelo importado", item.name, AgusEvent.ToastKind.SUCCESS))
                Outcome.Installed(item)
            }
            else -> {
                file.delete()
                Outcome.Unsupported(ext, "Formato não suportado pelo Agus VR.")
            }
        }
    }

    fun installWebApp(url: String, displayName: String, originId: String?): Outcome {
        val existing = originId?.let { LibraryRepository.findByOrigin(it) }
        if (existing != null) return Outcome.Installed(existing)
        val item = LibraryItem(
            id = LibraryRepository.newId(),
            name = displayName,
            type = LibraryType.WEBAPP,
            path = null,
            url = url,
            source = "url",
            originId = originId,
            description = "web app — abre no Navegador Agus"
        )
        LibraryRepository.add(item)
        AgusBus.post(AgusEvent.Toast(com.agusvr.R.drawable.ic_browser, "Web app adicionado", item.name, AgusEvent.ToastKind.SUCCESS))
        return Outcome.Installed(item)
    }
}
