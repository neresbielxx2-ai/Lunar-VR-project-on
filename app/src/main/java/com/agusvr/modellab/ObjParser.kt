package com.agusvr.modellab

import com.agusvr.util.Logx
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.sqrt

/** Parsed mesh ready for Filament vertex buffers. */
data class ObjMesh(
    val positions: FloatArray,   // xyz per vertex
    val tangents: FloatArray,    // quaternion (x,y,z,w) per vertex — encodes the normal
    val indices: ShortArray,
    val boundsMin: FloatArray,   // 3
    val boundsMax: FloatArray,   // 3
    val vertexCount: Int,
    val indexCount: Int
)

class ObjParseException(message: String) : Exception(message)

/**
 * Minimal-but-real Wavefront OBJ parser (module: AgusModelLab).
 *
 * Supports: v, vn, vt, f (triangle/quad/ngon fans), negative indices,
 * v//vn, v/vt/vn, v/vt forms, comments and o/g/s/usemtl lines (ignored).
 * Flat normals are computed when the file has none. Tangent quaternions are
 * built from the normals (Filament LIT materials expect TANGENTS).
 */
object ObjParser {

    private const val MAX_VERTICES = 200_000
    private const val MAX_INDICES = 600_000

    fun parse(input: InputStream): ObjMesh {
        val vList = ArrayList<FloatArray>(4096)     // raw vertices
        val vnList = ArrayList<FloatArray>(4096)   // raw normals
        val vtList = ArrayList<FloatArray>(4096)   // raw uvs (parsed, unused unless present)

        val outPos = ArrayList<FloatArray>(4096)
        val outNrm = ArrayList<FloatArray>(4096)
        val outIdx = ArrayList<Int>(8192)
        val dedupe = HashMap<String, Int>(8192)

        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        BufferedReader(InputStreamReader(input)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                when (parts[0]) {
                    "v" -> {
                        if (parts.size < 4) continue
                        val x = parts[1].toFloatOrNull() ?: continue
                        val y = parts[2].toFloatOrNull() ?: continue
                        val z = parts[3].toFloatOrNull() ?: continue
                        vList += floatArrayOf(x, y, z)
                        if (x < min[0]) min[0] = x; if (x > max[0]) max[0] = x
                        if (y < min[1]) min[1] = y; if (y > max[1]) max[1] = y
                        if (z < min[2]) min[2] = z; if (z > max[2]) max[2] = z
                    }
                    "vn" -> {
                        if (parts.size < 4) continue
                        vnList += floatArrayOf(
                            parts[1].toFloatOrNull() ?: 0f,
                            parts[2].toFloatOrNull() ?: 0f,
                            parts[3].toFloatOrNull() ?: 1f
                        )
                    }
                    "vt" -> {
                        if (parts.size < 3) continue
                        vtList += floatArrayOf(
                            parts[1].toFloatOrNull() ?: 0f,
                            parts[2].toFloatOrNull() ?: 0f
                        )
                    }
                    "f" -> {
                        if (parts.size < 4) continue
                        val faceIdx = IntArray(parts.size - 1)
                        for (i in 1 until parts.size) {
                            faceIdx[i - 1] = resolveVertex(parts[i], vList.size, vnList.size, vtList.size,
                                vList, vnList, outPos, outNrm, dedupe)
                        }
                        // fan triangulation (convex faces; standard for OBJ viewers)
                        for (i in 1 until faceIdx.size - 1) {
                            outIdx += faceIdx[0]
                            outIdx += faceIdx[i]
                            outIdx += faceIdx[i + 1]
                        }
                    }
                    else -> { /* o, g, s, mtllib, usemtl — ignored */ }
                }
                if (outPos.size > MAX_VERTICES || outIdx.size > MAX_INDICES) {
                    throw ObjParseException("OBJ grande demais para o Model Lab (limite $MAX_VERTICES vértices).")
                }
            }
        }

        if (outPos.isEmpty() || outIdx.isEmpty()) {
            throw ObjParseException("O arquivo OBJ não contém geometria (v/f).")
        }
        if (outIdx.size > Short.MAX_VALUE * 2) {
            // keep within USHORT index buffer limits
            throw ObjParseException("OBJ com índices além do suportado (use GLB para malhas grandes).")
        }

        val positions = FloatArray(outPos.size * 3)
        val tangents = FloatArray(outPos.size * 4)
        for (i in outPos.indices) {
            val p = outPos[i]
            positions[i * 3] = p[0]; positions[i * 3 + 1] = p[1]; positions[i * 3 + 2] = p[2]
            val q = normalToQuaternion(outNrm[i])
            tangents[i * 4] = q[0]; tangents[i * 4 + 1] = q[1]; tangents[i * 4 + 2] = q[2]; tangents[i * 4 + 3] = q[3]
        }
        val indices = ShortArray(outIdx.size) { outIdx[it].toShort() }

        // degenerate bounds guard
        for (i in 0..2) {
            if (min[i] == Float.MAX_VALUE) min[i] = -1f
            if (max[i] == -Float.MAX_VALUE) max[i] = 1f
        }
        Logx.d("ObjParser", "parsed ${outPos.size} verts / ${outIdx.size / 3} tris")
        return ObjMesh(positions, tangents, indices, min, max, outPos.size, outIdx.size)
    }

    fun parse(file: File): ObjMesh = file.inputStream().use { parse(it) }

    // ------------------------------------------------------------------

    private fun resolveVertex(
        token: String, vCount: Int, vnCount: Int, vtCount: Int,
        vList: ArrayList<FloatArray>, vnList: ArrayList<FloatArray>,
        outPos: ArrayList<FloatArray>, outNrm: ArrayList<FloatArray>,
        dedupe: HashMap<String, Int>
    ): Int {
        dedupe[token]?.let { return it }
        val comps = token.split("/")
        val vi = resolveIndex(comps.getOrNull(0), vCount)
            ?: throw ObjParseException("face referencia vértice inválido: $token")
        val pos = vList[vi]

        var nrm: FloatArray? = null
        val vnTok = comps.getOrNull(2)
        if (!vnTok.isNullOrEmpty()) {
            val ni = resolveIndex(vnTok, vnCount)
            if (ni != null) nrm = vnList[ni]
        }
        if (nrm == null) nrm = floatArrayOf(0f, 1f, 0f) // replaced by flat pass if none anywhere

        outPos += pos
        outNrm += nrm
        val idx = outPos.size - 1
        dedupe[token] = idx
        return idx
    }

    private fun resolveIndex(tok: String?, count: Int): Int? {
        if (tok.isNullOrEmpty()) return null
        val v = tok.toIntOrNull() ?: return null
        return if (v > 0) (if (v <= count) v - 1 else null) else (if (count + v >= 0) count + v else null)
    }

    /** Quaternion rotating +Z onto the normal (Filament TANGENTS convention approx.). */
    private fun normalToQuaternion(n: FloatArray): FloatArray {
        var nx = n[0]; var ny = n[1]; var nz = n[2]
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        nx /= len; ny /= len; nz /= len
        // rotation from (0,0,1) to n
        val dot = nz
        if (dot > 0.999999f) return floatArrayOf(0f, 0f, 0f, 1f)
        if (dot < -0.999999f) return floatArrayOf(1f, 0f, 0f, 0f) // 180° around X
        val ax = -ny; val ay = nx; val az = 0f // axis = z × n
        val w = 1f + dot
        val l = sqrt(ax * ax + ay * ay + az * az + w * w)
        return floatArrayOf(ax / l, ay / l, az / l, w / l)
    }

    /** Recompute flat normals when the OBJ has none (called on the parsed mesh). */
    fun ensureNormals(mesh: ObjMesh): ObjMesh {
        // Detect all-default normals (0,1,0) — cheap heuristic for "no vn in file"
        var allDefault = true
        var i = 0
        while (i < mesh.tangents.size && allDefault) {
            val qx = mesh.tangents[i]; val qy = mesh.tangents[i + 1]
            val qz = mesh.tangents[i + 2]; val qw = mesh.tangents[i + 3]
            if (abs(qx) > 1e-4f || abs(qy) > 1e-4f || abs(qz) > 1e-4f || abs(qw - 1f) > 0.35f) {
                allDefault = false
            }
            i += 4
        }
        if (!allDefault) return mesh

        val nrm = Array(mesh.vertexCount) { floatArrayOf(0f, 0f, 0f) }
        var t = 0
        while (t + 2 < mesh.indexCount) {
            val a = mesh.indices[t].toInt() and 0xFFFF
            val b = mesh.indices[t + 1].toInt() and 0xFFFF
            val c = mesh.indices[t + 2].toInt() and 0xFFFF
            val p0 = floatArrayOf(mesh.positions[a * 3], mesh.positions[a * 3 + 1], mesh.positions[a * 3 + 2])
            val p1 = floatArrayOf(mesh.positions[b * 3], mesh.positions[b * 3 + 1], mesh.positions[b * 3 + 2])
            val p2 = floatArrayOf(mesh.positions[c * 3], mesh.positions[c * 3 + 1], mesh.positions[c * 3 + 2])
            val e1 = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
            val e2 = floatArrayOf(p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2])
            val cr = floatArrayOf(
                e1[1] * e2[2] - e1[2] * e2[1],
                e1[2] * e2[0] - e1[0] * e2[2],
                e1[0] * e2[1] - e1[1] * e2[0]
            )
            for (idx in intArrayOf(a, b, c)) {
                nrm[idx][0] += cr[0]; nrm[idx][1] += cr[1]; nrm[idx][2] += cr[2]
            }
            t += 3
        }
        val tangents = FloatArray(mesh.vertexCount * 4)
        for (v in 0 until mesh.vertexCount) {
            val q = normalToQuaternion(nrm[v])
            tangents[v * 4] = q[0]; tangents[v * 4 + 1] = q[1]; tangents[v * 4 + 2] = q[2]; tangents[v * 4 + 3] = q[3]
        }
        return mesh.copy(tangents = tangents)
    }
}
