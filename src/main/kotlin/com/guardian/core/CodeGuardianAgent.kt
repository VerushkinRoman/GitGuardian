package com.guardian.core

import com.guardian.api.ChatMessage
import com.guardian.api.ClientFactory
import com.guardian.api.RouterClient
import com.guardian.api.RouterRequest
import java.io.File

class CodeGuardianAgent(
    projectPath: String,
    private val onProgress: ((String) -> Unit)? = null,
) {
    private val projectPath: String = File(projectPath).absolutePath
    private val git = GitWrapper(this.projectPath)
    private val tools = LocalFileTools(this.projectPath)
    private val index = ProjectIndex(this.projectPath)
    private val apiClient: RouterClient = ClientFactory.create()

    private val toolSchemas = """
        ТЫ — AI-ревьюер KMP-проекта. У тебя есть тулзы для проверки кода и сохранения отчёта.

        ТУЛЗЫ:
        [TOOL_CALL] op=read_file path="src/Main.kt"
        [TOOL_CALL] op=search query="BaseViewModel"
        [TOOL_CALL] op=save_report path="review.md"
        ... полный отчёт на русском языке ...
        [/TOOL_CALL]

        ПРАВИЛА:
        1. Сначала проанализируй diff и содержимое файлов.
        2. Если сомневаешься — вызови read_file или search для проверки.
        3. НЕ ПИШИ утверждения о коде который не видел.
        4. Каждая проблема должна иметь конкретный файл:строка.
        5. Когда готов — вызови save_report с полным отчётом.
           ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТЧЁТА (на русском языке):
           ## ОБЩАЯ ОЦЕНКА: X/10
           ## КРАТКОЕ САММАРИ
           ## ПРОВЕРКИ
           ### Архитектура (Clean Architecture + MVI)
           ### Именование
           ### Код-качество
           ### Безопасность
           ## ПРОБЛЕМЫ (список)
           ## РЕКОМЕНДАЦИИ
           ## ХВАЛА

        ВАЖНО: Весь отчёт должен быть написан на русском языке.
    """.trimIndent()

    suspend fun review(): ReviewReport {
        onProgress?.invoke("Checking git repository...")

        if (!git.isGitRepo()) {
            return ReviewReport(
                summary = "Not a git repository.",
                score = 0, sections = emptyList(),
                stats = ReviewStats(0, 0, 0, 0, 0)
            )
        }

        val ctx = collectGitContext()

        if (ctx.changedFiles.isEmpty() && ctx.untrackedFiles.isEmpty()) {
            return ReviewReport(
                summary = "No changes to review. Branch: ${ctx.branch}",
                score = 10,
                sections = listOf(ReviewSection("Status", ReviewStatus.OK, listOf("No changes"))),
                stats = ReviewStats(0, 0, 0, 0, 0)
            )
        }

        val fileContexts = readFileContexts(ctx.changedFiles)
        val ragContext = buildRagContext(ctx.changedFiles)
        val initialPrompt = buildReviewPrompt(ctx, fileContexts, ragContext)

        return runToolLoop(initialPrompt, ctx)
    }

    // ═══════════════════════════════════════════
    // Git context
    // ═══════════════════════════════════════════

    private data class GitContext(
        val branch: String,
        val changedFiles: List<String>,
        val binaryFiles: List<String>,
        val untrackedFiles: List<String>,
        val addedFiles: List<String>,
        val deletedFiles: List<String>,
        val modifiedFiles: List<String>,
        val diff: String,
        val diffStat: String,
    )

    private fun collectGitContext(): GitContext {
        val branch = git.getBranch()
        val changedFiles = git.getChangedTextFiles()
        val allChangedFiles = git.getChangedFiles()
        val binaryFiles = allChangedFiles.filter { git.isBinaryFile(it) }

        onProgress?.invoke(
            "Найдено ${changedFiles.size} текстовых файлов" +
                    if (binaryFiles.isNotEmpty()) ", ${binaryFiles.size} бинарных пропущено" else ""
        )

        return GitContext(
            branch = branch,
            changedFiles = changedFiles,
            binaryFiles = binaryFiles,
            untrackedFiles = git.getUntrackedFiles(),
            addedFiles = git.getAddedFiles(),
            deletedFiles = git.getDeletedFiles(),
            modifiedFiles = git.getModifiedFiles(),
            diff = git.getDiffAll(),
            diffStat = git.getDiffStat(),
        )
    }

    // ═══════════════════════════════════════════
    // Context collection
    // ═══════════════════════════════════════════

    private fun readFileContexts(changedFiles: List<String>): Map<String, String> {
        onProgress?.invoke("Reading changed files...")
        val fileContexts = mutableMapOf<String, String>()
        for (filePath in changedFiles) {
            try {
                fileContexts[filePath] = tools.readFile(filePath)
            } catch (e: Exception) {
                onProgress?.invoke("[warn] Не удалось прочитать $filePath: ${e.message}")
            }
        }
        return fileContexts
    }

    private fun buildRagContext(changedFiles: List<String>): String {
        onProgress?.invoke("Searching relevant context via RAG...")
        val relevantChunks = index.search(
            query = "KMP architecture ${changedFiles.joinToString(" ")}",
            maxResults = 10
        )
        return relevantChunks.joinToString("\n") { it.chunk.content }.take(5000)
    }

    // ═══════════════════════════════════════════
    // Tool loop
    // ═══════════════════════════════════════════

    private suspend fun runToolLoop(initialPrompt: String, ctx: GitContext): ReviewReport {
        onProgress?.invoke("Analyzing code (step 1/2)...")
        val allToolResults = mutableListOf<String>()
        var currentPrompt = "$toolSchemas\n\n$initialPrompt"
        val allLlmResponses = mutableListOf<String>()

        repeat(5) {
            val llmResponse = callLlm(currentPrompt)
            allLlmResponses.add(llmResponse)

            val toolCalls = extractToolCalls(llmResponse)
            if (toolCalls.isEmpty()) {
                onProgress?.invoke("Analysis complete (step 2/2)...")
                saveReport(llmResponse, ctx.branch)
                val report = parseReviewReport(llmResponse, ctx.branch, ctx.changedFiles.size, ctx.diff.length)
                onProgress?.invoke("Review done. Score: ${report.score}/10")
                return report
            }

            val reportSaved = executeToolCalls(toolCalls, llmResponse, allToolResults)
            if (reportSaved) {
                onProgress?.invoke("Analysis complete (step 2/2)...")
                val report = parseReviewReport(allLlmResponses.last(), ctx.branch, ctx.changedFiles.size, ctx.diff.length)
                onProgress?.invoke("Review done. Score: ${report.score}/10")
                return report
            }

            currentPrompt = buildVerificationPrompt(initialPrompt, allLlmResponses.last(), allToolResults)
        }

        val lastResponse = allLlmResponses.lastOrNull() ?: ""
        saveReport(lastResponse, ctx.branch)
        val report = parseReviewReport(lastResponse, ctx.branch, ctx.changedFiles.size, ctx.diff.length)
        onProgress?.invoke("Review done. Score: ${report.score}/10")
        return report
    }

    private fun executeToolCalls(
        toolCalls: List<ToolCall>,
        llmResponse: String,
        allToolResults: MutableList<String>,
    ): Boolean {
        var reportSaved = false
        for (call in toolCalls) {
            if (call.op == "save_report") {
                val content = call.content ?: llmResponse
                saveReport(content, "")
                onProgress?.invoke("Report saved to report/review.md")
                allToolResults.add("[save_report] saved ${content.length} chars to report/review.md")
                reportSaved = true
            } else {
                val result = executeToolCall(call)
                allToolResults.add("[${call.op}] ${call.args}: $result")
                onProgress?.invoke("Checking: ${call.args["path"] ?: call.args["query"] ?: call.op}...")
            }
        }
        return reportSaved
    }

    // ═══════════════════════════════════════════
    // Report saving
    // ═══════════════════════════════════════════

    private fun saveReport(content: String, branch: String) {
        val reportDir = File(projectPath, "report")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "review.md")
        reportFile.writeText(buildString {
            appendLine("# Code Guardian Review")
            appendLine()
            appendLine("- **Branch:** $branch")
            appendLine("- **Date:** ${java.time.LocalDateTime.now()}")
            appendLine()
            appendLine(content)
        })
    }

    // ═══════════════════════════════════════════
    // Tools
    // ═══════════════════════════════════════════

    private data class ToolCall(
        val op: String,
        val args: Map<String, String>,
        val content: String? = null,
    )

    private fun extractToolCalls(text: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()

        val blockPattern = Regex(
            """\[TOOL_CALL]\s*op=(\w+)\s*(.*?)\n(.*?)\[/TOOL_CALL]""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in blockPattern.findAll(text)) {
            val op = match.groupValues[1]
            val argsStr = match.groupValues[2]
            val content = match.groupValues[3].trim()
            val args = parseToolArgs(argsStr)
            results.add(ToolCall(op, args, content))
        }

        if (results.isEmpty()) {
            val simplePattern = Regex(
                """\[TOOL_CALL]\s*op=(\w+)\s*(.*)""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in simplePattern.findAll(text)) {
                val op = match.groupValues[1]
                val argsStr = match.groupValues[2]
                val args = parseToolArgs(argsStr)
                results.add(ToolCall(op, args))
            }
        }

        return results
    }

    private fun parseToolArgs(argsStr: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex("""(\w+)="([^"]*)"""")
        for (match in pattern.findAll(argsStr)) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }

    private fun executeToolCall(call: ToolCall): String {
        return when (call.op) {
            "read_file" -> {
                val path = call.args["path"] ?: return "Error: path not specified"
                try {
                    val content = tools.readFile(path)
                    "=== $path ===\n$content"
                } catch (_: Exception) {
                    "File not found: $path"
                }
            }

            "search" -> {
                val query = call.args["query"] ?: return "Error: query not specified"
                try {
                    val results = tools.searchContent(query)
                    if (results.isEmpty()) "Nothing found for: $query"
                    else results.joinToString("\n") { "${it.filePath}:${it.lineNumber}: ${it.lineContent}" }
                } catch (_: Exception) {
                    "Search error: $query"
                }
            }

            else -> "Unknown tool: ${call.op}"
        }
    }

    // ═══════════════════════════════════════════
    // Prompts
    // ═══════════════════════════════════════════

    private fun buildReviewPrompt(
        ctx: GitContext,
        fileContexts: Map<String, String>,
        ragContext: String,
    ): String = buildString {
        appendLine("ЗАДАЧА: Проведи code review изменений в KMP-проекте.")
        appendLine()
        appendLine("ВЕТКА: ${ctx.branch}")
        appendLine("ИЗМЕНЁННЫХ ФАЙЛОВ: ${ctx.changedFiles.size}")
        appendLine("ДОБАВЛЕНО: ${ctx.addedFiles.size}, УДАЛЕНО: ${ctx.deletedFiles.size}, ИЗМЕНЕНО: ${ctx.modifiedFiles.size}")
        appendLine()
        appendLine("-- Diff Stat --")
        appendLine(ctx.diffStat)
        appendLine()

        appendLine("-- Изменённые файлы --")
        ctx.changedFiles.forEach { appendLine("- $it") }
        appendLine()

        if (fileContexts.isNotEmpty()) {
            appendLine("-- Полное содержимое изменённых файлов --")
            fileContexts.forEach { (path, content) ->
                appendLine("=== $path ===")
                appendLine(content)
                appendLine()
            }
        }

        if (ragContext.isNotBlank()) {
            appendLine("-- RAG-контекст проекта --")
            appendLine(ragContext)
            appendLine()
        }

        appendLine("-- Diff --")
        appendLine(ctx.diff.take(20000))
        appendLine()

        appendLine("ИНСТРУКЦИЯ:")
        appendLine()
        appendLine("1. Изучи diff и полные содержимое файлов выше.")
        appendLine("2. Если видишь упоминание класса/метода и сомневаешься —")
        appendLine("   используй read_file или search для проверки.")
        appendLine("3. НЕ ПИШИ проблему если ты не видел код лично.")
        appendLine("4. Когда готов — вызови save_report с полным отчётом на русском языке.")
    }

    private fun buildVerificationPrompt(
        initialPrompt: String,
        lastLlmResponse: String,
        toolResults: List<String>
    ): String = buildString {
        appendLine(toolSchemas)
        appendLine()
        appendLine(initialPrompt)
        appendLine()
        appendLine("=== ТВОЙ ПРОШЛЫЙ ОТВЕТ ===")
        appendLine(lastLlmResponse.take(3000))
        appendLine()
        appendLine("=== РЕЗУЛЬТАТЫ ТУЛЗОВ ===")
        toolResults.forEach { appendLine(it) }
        appendLine()
        appendLine("=== ПРОДОЛЖИ ===")
        appendLine("Проанализируй результаты тулзов.")
        appendLine("Если достаточно информации — вызови save_report с полным отчётом на русском.")
        appendLine("Если нужны ещё проверки — вызови read_file или search.")
    }

    // ═══════════════════════════════════════════
    // LLM + Parsing
    // ═══════════════════════════════════════════

    private suspend fun callLlm(prompt: String): String {
        return try {
            val request = RouterRequest(
                model = "mistral/mistral-large-latest",
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.0,
                maxTokens = 8192,
            )
            val response = apiClient.sendRequest(request)
            if (response.error != null) {
                "LLM error: ${response.error.message}"
            } else {
                response.choices?.firstOrNull()?.message?.content ?: ""
            }
        } catch (e: Exception) {
            "Error calling LLM: ${e.message}"
        }
    }

    private fun parseReviewReport(
        text: String,
        branch: String,
        fileCount: Int,
        diffLength: Int
    ): ReviewReport {
        val score = Regex("""OVERALL SCORE:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""ОБЩАЯ ОЦЕНКА:\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: 5

        val sections = extractSections(text)
        val summary = extractSummary(text)

        return ReviewReport(
            summary = summary,
            score = score,
            sections = sections,
            stats = ReviewStats(
                filesReviewed = fileCount,
                issuesFound = sections.sumOf { s ->
                    s.items.count { it.contains("ERROR") || it.contains("WARNING") }
                },
                criticalIssues = sections.sumOf { s -> s.items.count { it.contains("ERROR") } },
                warnings = sections.sumOf { s -> s.items.count { it.contains("WARNING") } },
                diffSize = diffLength
            ),
            branch = branch,
            rawResponse = text
        )
    }

    private fun extractSummary(text: String): String {
        val markers = listOf("SUMMARY", "КРАТКОЕ САММАРИ")
        for (marker in markers) {
            val start = text.indexOf(marker)
            if (start != -1) {
                val afterMarker = text.substring(start + marker.length).trim()
                val end = afterMarker.indexOf("\n##")
                return if (end > 0) afterMarker.substring(0, end).trim()
                else afterMarker.take(500).trim()
            }
        }
        return text.take(500)
    }

    private fun extractSections(text: String): List<ReviewSection> {
        val sections = mutableListOf<ReviewSection>()
        val sectionPattern = Regex("""###\s+(.+)""")
        val lines = text.lines()
        var currentTitle: String? = null
        val currentItems = mutableListOf<String>()

        for (line in lines) {
            val match = sectionPattern.find(line)
            if (match != null) {
                if (currentTitle != null && currentItems.isNotEmpty()) {
                    sections.add(ReviewSection(currentTitle, determineStatus(currentItems), currentItems.toList()))
                }
                currentTitle = match.groupValues[1].trim()
                currentItems.clear()
            } else if (currentTitle != null && line.isNotBlank() && line.startsWith("- ")) {
                currentItems.add(line.removePrefix("- ").trim())
            }
        }
        if (currentTitle != null && currentItems.isNotEmpty()) {
            sections.add(ReviewSection(currentTitle, determineStatus(currentItems), currentItems.toList()))
        }
        return sections
    }

    private fun determineStatus(items: List<String>): ReviewStatus = when {
        items.any { it.contains("ERROR") } -> ReviewStatus.ERROR
        items.any { it.contains("WARNING") } -> ReviewStatus.WARNING
        else -> ReviewStatus.OK
    }
}

data class ReviewReport(
    val summary: String,
    val score: Int,
    val sections: List<ReviewSection>,
    val stats: ReviewStats,
    val branch: String = "",
    val rawResponse: String = ""
)

data class ReviewSection(
    val title: String,
    val status: ReviewStatus,
    val items: List<String>
)

enum class ReviewStatus { OK, WARNING, ERROR }

data class ReviewStats(
    val filesReviewed: Int,
    val issuesFound: Int,
    val criticalIssues: Int,
    val warnings: Int,
    val diffSize: Int
)
