package com.guardian.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

class LocalFileTools(private val projectRoot: String) {

    private val rootPath: Path = Paths.get(projectRoot).toAbsolutePath().normalize()

    init {
        require(rootPath.toFile().exists()) { "Project root does not exist: $projectRoot" }
    }

    data class FileMatch(
        val filePath: String,
        val lineNumber: Int,
        val lineContent: String,
        val context: String? = null,
    )

    private fun listFiles(
        pattern: String? = null,
        maxDepth: Int = 15,
        extensions: Set<String>? = null,
    ): List<String> {
        val results = mutableListOf<String>()
        val walkable = Files.walk(rootPath, maxDepth)

        for (path in walkable) {
            val relative = path.relativeTo(rootPath).toString()
            if (relative.isBlank()) continue
            if (relative.startsWith(".") || relative.contains("/.")) continue
            if (relative.contains("build/") || relative.contains("build\\")) continue
            if (relative.contains(".gradle") || relative.contains("node_modules")) continue

            if (path.isRegularFile()) {
                val ext = path.extension.lowercase()
                if (!extensions.isNullOrEmpty() && ext !in extensions) continue
                if (pattern != null && !relative.contains(pattern, ignoreCase = true)) continue
                results.add(relative)
            }
        }
        return results.sorted()
    }

    fun readFile(relativePath: String, maxLines: Int = DEFAULT_MAX_LINES): String {
        val file = resolveFile(relativePath)

        val fileSize = file.length()
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            val truncatedLines = file.bufferedReader().useLines { lines ->
                lines.take(maxLines).toList()
            }
            return truncatedLines.joinToString("\n") +
                "\n\n// [файл обрезан: ${fileSize / 1024}KB > ${MAX_FILE_SIZE_BYTES / 1024}KB лимит]"
        }

        val lines = file.readLines()
        val truncated = lines.size > maxLines
        return if (truncated) {
            lines.take(maxLines).joinToString("\n") +
                "\n\n// [обрезано на $maxLines строках, всего: ${lines.size}]"
        } else {
            lines.joinToString("\n")
        }
    }

    fun searchContent(
        query: String,
        extensions: Set<String>? = setOf("kt", "kts"),
        maxResults: Int = 50,
    ): List<FileMatch> {
        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        val results = mutableListOf<FileMatch>()
        val kotlinFiles = listFiles(extensions = extensions)

        for (relativePath in kotlinFiles) {
            if (results.size >= maxResults) break

            val file = resolveFile(relativePath)
            if (file.length() > MAX_SEARCH_FILE_SIZE) continue

            val lines = file.readLines()

            for ((index, line) in lines.withIndex()) {
                if (results.size >= maxResults) break
                if (regex.containsMatchIn(line)) {
                    val contextStart = maxOf(0, index - 1)
                    val contextEnd = minOf(lines.size - 1, index + 1)
                    val context = lines.subList(contextStart, contextEnd + 1).joinToString("\n")

                    results.add(
                        FileMatch(
                            filePath = relativePath,
                            lineNumber = index + 1,
                            lineContent = line.trim(),
                            context = context,
                        )
                    )
                }
            }
        }
        return results
    }

    private fun resolveFile(relativePath: String): File {
        val normalized = relativePath.replace("\\", "/")
        val file = rootPath.resolve(normalized).toAbsolutePath().normalize().toFile()
        require(file.exists()) { "File not found: $relativePath" }
        require(file.path.startsWith(rootPath.toString())) { "Path traversal not allowed: $relativePath" }
        return file
    }

    companion object {
        private const val DEFAULT_MAX_LINES = 500
        private const val MAX_FILE_SIZE_BYTES = 512 * 1024L   // 512KB
        private const val MAX_SEARCH_FILE_SIZE = 1024 * 1024L // 1MB
    }
}
