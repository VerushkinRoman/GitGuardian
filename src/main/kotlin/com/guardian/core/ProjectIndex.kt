package com.guardian.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.math.log10

class ProjectIndex(projectRoot: String) {

    private val rootPath: Path = Paths.get(projectRoot).toAbsolutePath().normalize()

    private val deprioritizedFiles = setOf("AGENTS.MD", "AGENTS.md", "CLAUDE.md", "CURSOR.md")
    private val deprioritizeFactor = 0.3

    enum class ChunkType { CODE, DOC }

    data class Chunk(
        val filePath: String,
        val chunkId: Int,
        val startLine: Int,
        val endLine: Int,
        val content: String,
        val tokens: List<String>,
        val type: ChunkType,
    )

    data class SearchResult(
        val chunk: Chunk,
        val score: Double,
        val matchedTerms: List<String>,
    )

    private val chunks = mutableListOf<Chunk>()
    private val df = mutableMapOf<String, Int>()
    private var totalChunks = 0

    init {
        buildIndex()
    }

    fun search(query: String, maxResults: Int = 20): List<SearchResult> {
        val queryTerms = tokenize(query).filter { it.length >= 2 }.distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val scores = mutableMapOf<Int, Double>()
        val matchedTermsMap = mutableMapOf<Int, MutableList<String>>()

        for (term in queryTerms) {
            val termDf = df[term] ?: continue
            val idf = log10(totalChunks.toDouble() / termDf)

            for ((chunkId, termFreq) in getTermFreqs(term)) {
                val chunk = chunks[chunkId]
                val tf = 1.0 + log10(termFreq.toDouble())
                var tfidf = tf * idf
                if (chunk.type == ChunkType.CODE) tfidf *= 1.5
                scores[chunkId] = (scores[chunkId] ?: 0.0) + tfidf
                matchedTermsMap.getOrPut(chunkId) { mutableListOf() }.add(term)
            }
        }

        for (chunk in chunks) {
            val lowerContent = chunk.content.lowercase()
            for (term in queryTerms) {
                if (lowerContent.contains(term)) {
                    scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0.0) + 0.5
                }
            }
        }

        for (chunk in chunks) {
            val fileName = chunk.filePath.substringAfterLast("/")
            if (fileName in deprioritizedFiles) {
                val current = scores[chunk.chunkId] ?: continue
                scores[chunk.chunkId] = current * deprioritizeFactor
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { (chunkId, score) ->
                SearchResult(
                    chunk = chunks[chunkId],
                    score = score,
                    matchedTerms = matchedTermsMap[chunkId] ?: emptyList(),
                )
            }
    }

    private fun buildIndex() {
        val allFiles = listIndexableFiles()
        var chunkIdCounter = 0

        for (filePath in allFiles) {
            val file = rootPath.resolve(filePath).toFile()
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }

            val type = if (filePath.endsWith(".md")) ChunkType.DOC else ChunkType.CODE
            val fileChunks = splitIntoChunks(filePath, content, chunkIdCounter, type)
            chunks.addAll(fileChunks)

            val uniqueTerms = mutableSetOf<String>()
            for (chunk in fileChunks) {
                uniqueTerms.addAll(chunk.tokens)
            }
            for (term in uniqueTerms) {
                df[term] = (df[term] ?: 0) + 1
            }

            chunkIdCounter += fileChunks.size
        }

        totalChunks = chunkIdCounter
    }

    private fun splitIntoChunks(filePath: String, content: String, startId: Int, type: ChunkType): List<Chunk> {
        val lines = content.lines()
        return when (type) {
            ChunkType.DOC -> splitMarkdown(filePath, lines, startId)
            ChunkType.CODE -> splitCode(filePath, lines, startId)
        }
    }

    private fun splitMarkdown(filePath: String, lines: List<String>, startId: Int): List<Chunk> {
        if (lines.size <= 80) {
            return listOf(makeChunk(filePath, startId, 1, lines.size, lines.joinToString("\n"), ChunkType.DOC))
        }
        val chunks = mutableListOf<Chunk>()
        var chunkStart = 0
        var currentId = startId
        val headingPattern = Regex("^#{1,3}\\s+")
        for ((i, line) in lines.withIndex()) {
            if (headingPattern.containsMatchIn(line) && i > chunkStart + 5) {
                val chunkContent = lines.subList(chunkStart, i).joinToString("\n")
                chunks.add(makeChunk(filePath, currentId++, chunkStart + 1, i, chunkContent, ChunkType.DOC))
                chunkStart = i
            }
        }
        if (chunkStart < lines.size) {
            val chunkContent = lines.subList(chunkStart, lines.size).joinToString("\n")
            chunks.add(makeChunk(filePath, currentId, chunkStart + 1, lines.size, chunkContent, ChunkType.DOC))
        }
        return chunks
    }

    private fun splitCode(filePath: String, lines: List<String>, startId: Int): List<Chunk> {
        if (lines.size <= 100) {
            return listOf(makeChunk(filePath, startId, 1, lines.size, lines.joinToString("\n"), ChunkType.CODE))
        }
        val chunks = mutableListOf<Chunk>()
        var chunkStart = 0
        var currentId = startId
        var braceDepth = 0
        var inClassOrFun = false
        val classPatterns = listOf("class ", "interface ", "object ", "fun ",
            "abstract class ", "open class ", "data class ",
            "sealed class ", "sealed interface ", "enum class ")
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (!inClassOrFun && classPatterns.any { trimmed.startsWith(it) }) {
                if (i > chunkStart + 10) {
                    val chunkContent = lines.subList(chunkStart, i).joinToString("\n")
                    chunks.add(makeChunk(filePath, currentId++, chunkStart + 1, i, chunkContent, ChunkType.CODE))
                    chunkStart = i
                }
                inClassOrFun = true
            }
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
            if (inClassOrFun && braceDepth <= 0 && line.contains('}')) {
                inClassOrFun = false
                braceDepth = 0
            }
        }
        if (chunkStart < lines.size) {
            val chunkContent = lines.subList(chunkStart, lines.size).joinToString("\n")
            chunks.add(makeChunk(filePath, currentId, chunkStart + 1, lines.size, chunkContent, ChunkType.CODE))
        }
        return chunks
    }

    private fun makeChunk(filePath: String, chunkId: Int, startLine: Int, endLine: Int, content: String, type: ChunkType) =
        Chunk(filePath, chunkId, startLine, endLine, content, tokenize(content), type)

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

    private fun getTermFreqs(term: String): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        for (chunk in chunks) {
            val count = chunk.tokens.count { it == term }
            if (count > 0) result[chunk.chunkId] = count
        }
        return result
    }

    private fun listIndexableFiles(): List<String> {
        val results = mutableListOf<String>()
        val walkable = Files.walk(rootPath, 15)
        for (path in walkable) {
            val relative = path.relativeTo(rootPath).toString()
            if (relative.isBlank()) continue
            if (relative.startsWith(".") || relative.contains("/.")) continue
            if (relative.contains("build/") || relative.contains("build\\")) continue
            if (relative.contains(".gradle") || relative.contains("node_modules")) continue
            if (path.isRegularFile()) {
                val ext = path.extension.lowercase()
                if (ext in setOf("kt", "kts", "md", "toml")) {
                    results.add(relative)
                }
            }
        }
        return results.sorted()
    }
}
