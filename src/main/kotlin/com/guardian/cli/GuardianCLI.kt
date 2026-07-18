package com.guardian.cli

import com.guardian.api.ClientFactory
import com.guardian.core.CodeGuardianAgent
import com.guardian.core.GitWrapper
import com.guardian.core.ReviewStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val parsed = parseArgs(args)

    if (parsed.help) {
        printHelp()
        return@runBlocking
    }

    if (parsed.ollama) {
        ClientFactory.useOllama = true
        println("[info] Используется локальная модель Ollama")
        println()
    }

    val projectPath = parsed.path ?: System.getProperty("user.dir")
    val projectDir = File(projectPath).absoluteFile

    println("===========================================================")
    println("  KMP Code Guardian")
    println("===========================================================")
    println()
    println("Проект: ${projectDir.absolutePath}")
    println("Модель: ${if (parsed.ollama) "Ollama (локальная)" else "KodikRouter (облачная)"}")
    println()

    if (!projectDir.exists()) {
        println("[ошибка] Директория не существует: ${projectDir.absolutePath}")
        return@runBlocking
    }

    if (!projectDir.isDirectory) {
        println("[ошибка] Не является директорией: ${projectDir.absolutePath}")
        return@runBlocking
    }

    if (!projectDir.canRead()) {
        println("[ошибка] Нет прав на чтение: ${projectDir.absolutePath}")
        return@runBlocking
    }

    val hasSourceFiles = projectDir.walkTopDown()
        .maxDepth(3)
        .any { it.isFile && it.extension in setOf("kt", "kts", "java", "swift", "ts", "js") }

    if (!hasSourceFiles) {
        println("[предупреждение] Не найдены исходные файлы (.kt, .kts, .java, .swift, .ts, .js)")
        println("  Убедитесь, что путь указывает на корень проекта.")
        println()
    }

    val git = GitWrapper(projectDir.absolutePath)

    if (!git.isGitRepo()) {
        println("[ошибка] Не является git-репозиторием: ${projectDir.absolutePath}")
        println("[подсказка] Убедитесь, что вы находитесь в корне git-проекта.")
        return@runBlocking
    }

    val branch = withContext(Dispatchers.IO) { git.getBranch() }
    val changedFiles = withContext(Dispatchers.IO) { git.getChangedFiles() }
    val untrackedFiles = withContext(Dispatchers.IO) { git.getUntrackedFiles() }
    val diffStat = withContext(Dispatchers.IO) { git.getDiffStat() }

    println("===========================================================")
    println("  GIT СТАТУС")
    println("===========================================================")
    println("  Ветка: $branch")
    println("  Изменённых файлов: ${changedFiles.size}")
    println("  Новых файлов: ${untrackedFiles.size}")
    println()

    if (changedFiles.isNotEmpty()) {
        println("  Изменённые файлы:")
        changedFiles.forEach { println("    - $it") }
        println()
    }

    if (untrackedFiles.isNotEmpty()) {
        println("  Новые файлы:")
        untrackedFiles.take(10).forEach { println("    - $it") }
        if (untrackedFiles.size > 10) println("    ... и ещё ${untrackedFiles.size - 10}")
        println()
    }

    if (diffStat.isNotBlank()) {
        println("  Статистика:")
        diffStat.lines().forEach { println("    $it") }
        println()
    }

    println("===========================================================")
    println("  AI-РЕВЬЮ")
    println("===========================================================")
    println()

    val agent = CodeGuardianAgent(
        projectPath = projectDir.absolutePath,
        onProgress = { msg -> println("  -> $msg") }
    )

    val report = agent.review()

    println()
    println("===========================================================")
    println("  РЕЗУЛЬТАТ")
    println("===========================================================")
    println()
    println("  Оценка: ${report.score}/10")
    println("  Файлов проверено: ${report.stats.filesReviewed}")
    println("  Проблем: ${report.stats.issuesFound} (критичных: ${report.stats.criticalIssues})")
    println()
    println("  Саммари:")
    println("  ${report.summary}")
    println()

    if (report.sections.isNotEmpty()) {
        println("  Детали:")
        for (section in report.sections) {
            val icon = when (section.status) {
                ReviewStatus.OK -> "[ОК]"
                ReviewStatus.WARNING -> "[ВНИМ]"
                ReviewStatus.ERROR -> "[ОШИБ]"
            }
            println("  $icon ${section.title}")
            section.items.forEach { println("    - $it") }
            println()
        }
    }

    val reportFile = File(projectDir, "report/review.md")
    if (reportFile.exists()) {
        println("===========================================================")
        println("  Отчёт сохранён: ${reportFile.absolutePath}")
        println("===========================================================")
    }
}

private data class CliArgs(
    val path: String? = null,
    val ollama: Boolean = false,
    val help: Boolean = false,
)

private fun parseArgs(args: Array<String>): CliArgs {
    var path: String? = null
    var ollama = false
    var help = false

    for (arg in args) {
        when {
            arg == "--help" || arg == "-h" -> help = true
            arg == "--ollama" || arg == "-o" -> ollama = true
            !arg.startsWith("-") -> path = arg
        }
    }

    return CliArgs(path = path, ollama = ollama, help = help)
}

private fun printHelp() {
    println("""
===========================================================
  KMP Code Guardian — AI-ревьюер KMP-проектов
===========================================================

Использование:
  gitGuardian [путь] [опции]

Аргументы:
  путь                    Путь к корню проекта (по умолчанию: текущая директория)

Опции:
  --ollama, -o            Использовать локальную модель Ollama вместо облака
  --help, -h              Показать эту справку

Примеры:
  gitGuardian                             # ревью текущей директории
  gitGuardian /path/to/project            # ревью указанного проекта
  gitGuardian --ollama                    # ревью через локальную Ollama
  gitGuardian /path/to/project --ollama   # всё вместе

Конфигурация:
  Облачная модель (по умолчанию):
    Требуется файл keys.properties в текущей директории
    с паролем: llm_user_pwd=...

  Локальная модель (Ollama):
    Должна быть запущена Ollama на localhost:11434
    Модель: mistral/latest (или доступная в Ollama)

Результат:
  Отчёт сохраняется в ./report/review.md внутри проверяемого проекта
  (перезаписывается при каждом запуске)

Установка:
  ./gradlew shadowJar   # собирает и устанавливает в ~/.gitGuardian/
""".trimIndent())
}
