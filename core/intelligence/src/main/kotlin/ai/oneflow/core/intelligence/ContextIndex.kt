package ai.oneflow.core.intelligence

import kotlinx.serialization.Serializable

@Serializable
data class IndexedSearchHit(
    val path: String,
    val score: Int,
    val snippet: String
)

/**
 * Deterministic lexical/symbol context index (§8.3).
 * Uses exact tokenization and term frequency without local AI models or embeddings.
 */
class DeterministicContextIndex {
    private data class DocEntry(
        val path: String,
        val content: String,
        val termFreqs: Map<String, Int>
    )

    private val docs = linkedMapOf<String, DocEntry>()

    @Synchronized
    fun indexDocument(path: String, content: String) {
        val tokens = tokenize(content)
        val freqs = mutableMapOf<String, Int>()
        for (t in tokens) {
            freqs[t] = (freqs[t] ?: 0) + 1
        }
        docs[path] = DocEntry(path, content, freqs)
    }

    @Synchronized
    fun removeDocument(path: String) {
        docs.remove(path)
    }

    @Synchronized
    fun search(query: String, limit: Int = 10): List<IndexedSearchHit> {
        val qTokens = tokenize(query).distinct()
        if (qTokens.isEmpty()) return emptyList()
        return docs.values
            .mapNotNull { doc ->
                val pathLower = doc.path.lowercase()
                val score = qTokens.sumOf { qt ->
                    (doc.termFreqs[qt] ?: 0) + if (pathLower.contains(qt)) 3 else 0
                }
                if (score <= 0) null
                else {
                    val firstMatchLine = doc.content.lineSequence()
                        .firstOrNull { line -> qTokens.any { line.lowercase().contains(it) } }
                        ?.trim()
                        ?.take(180)
                        ?: doc.content.take(180)
                    IndexedSearchHit(doc.path, score, firstMatchLine)
                }
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 2 }
}
