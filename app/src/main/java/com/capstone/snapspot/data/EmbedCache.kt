package com.capstone.snapspot.data

import com.capstone.snapspot.core.Embedder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** About 12KB per vector (3072 floats), so the file stays under 5MB. */
const val EMBED_CACHE_MAX = 400

@Serializable
internal data class EmbedEntry(val at: Long, val v: String)

/**
 * Embedding vectors by text (embeddings.json), so the same place name is sent to Gemini once.
 * The least recently used entries go first past [max]. Not part of the team's data contract.
 */
class EmbedCache(
    dir: File,
    private val fetch: suspend (List<String>) -> List<FloatArray>,
    private val max: Int = EMBED_CACHE_MAX,
    private val clock: () -> Long = System::currentTimeMillis,
) : Embedder {
    private val lock = Mutex()
    private val file = JsonFile(File(dir, "embeddings.json"), MapSerializer(String.serializer(), EmbedEntry.serializer()))
    private var mem: MutableMap<String, EmbedEntry>? = null

    override suspend fun embed(texts: List<String>): List<FloatArray> = lock.withLock {
        val m = mem ?: (file.read().orEmpty().toMutableMap()).also { mem = it }
        val missing = texts.distinct().filter { it !in m }
        val now = clock()
        if (missing.isNotEmpty()) {
            val got = fetch(missing)
            check(got.size == missing.size) { "임베딩 개수가 맞지 않아요 (${got.size}/${missing.size})." }
            missing.zip(got).forEach { (t, v) -> m[t] = EmbedEntry(now, encode(v)) }
        }
        val out = texts.map { decode(m.getValue(it).v) }
        texts.forEach { t -> m[t] = m.getValue(t).copy(at = now) }
        if (m.size > max) m.entries.sortedBy { it.value.at }.take(m.size - max).forEach { m.remove(it.key) }
        file.write(m.toMap())
        out
    }

    private fun encode(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    private fun decode(s: String): FloatArray {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buf.remaining() / 4) { buf.getFloat() }
    }
}
