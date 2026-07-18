package com.guardian.core

import java.io.File

class GitWrapper(private val projectPath: String) {

    private val rootDir: File
        get() = File(projectPath)

    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
        "mp3", "mp4", "avi", "mov", "mkv", "wav",
        "zip", "tar", "gz", "rar", "7z",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "exe", "dll", "so", "dylib", "bin",
        "ttf", "otf", "woff", "woff2",
        "jar", "war", "ear",
    )

    fun isGitRepo(): Boolean = File(projectPath, ".git").exists()

    fun getBranch(): String = runGit("rev-parse", "--abbrev-ref", "HEAD").trim()

    private fun getDiffStaged(): String = runGit("diff", "--cached")

    private fun getDiffWorking(): String = runGit("diff")

    fun getDiffAll(): String {
        val staged = getDiffStaged()
        val working = getDiffWorking()
        return buildString {
            if (staged.isNotBlank()) {
                appendLine("=== STAGED ===")
                appendLine(staged)
            }
            if (working.isNotBlank()) {
                appendLine("=== WORKING TREE ===")
                appendLine(working)
            }
            if (staged.isBlank() && working.isBlank()) {
                appendLine("(no changes)")
            }
        }
    }

    fun getDiffStat(): String = runGit("diff", "--stat")

    fun getChangedFiles(): List<String> {
        val output = runGit("diff", "--name-only")
        val staged = runGit("diff", "--cached", "--name-only")
        val all = mutableSetOf<String>()
        if (output.isNotBlank()) all.addAll(output.lines().filter { it.isNotBlank() })
        if (staged.isNotBlank()) all.addAll(staged.lines().filter { it.isNotBlank() })
        return all.sorted()
    }

    fun getChangedTextFiles(): List<String> {
        return getChangedFiles().filter { !isBinaryFile(it) }
    }

    fun getUntrackedFiles(): List<String> {
        val output = runGit("ls-files", "--others", "--exclude-standard")
        return output.lines().filter { it.isNotBlank() }.sorted()
    }

    fun getAddedFiles(): List<String> = getFilesByDiffFilter("A")

    fun getDeletedFiles(): List<String> = getFilesByDiffFilter("D")

    fun getModifiedFiles(): List<String> = getFilesByDiffFilter("M")

    fun isBinaryFile(path: String): Boolean {
        val ext = path.substringAfterLast(".").lowercase()
        if (ext in binaryExtensions) return true

        // Check if git considers it binary
        val result = runGit("diff", "--numstat", "--", path)
        // Binary files show dashes: "-\t-\tpath"
        return result.contains("-\t-\t")
    }

    private fun getFilesByDiffFilter(filter: String): List<String> {
        val output = runGit("diff", "--cached", "--diff-filter=$filter", "--name-only")
        val working = runGit("diff", "--diff-filter=$filter", "--name-only")
        val all = mutableSetOf<String>()
        if (output.isNotBlank()) all.addAll(output.lines().filter { it.isNotBlank() })
        if (working.isNotBlank()) all.addAll(working.lines().filter { it.isNotBlank() })
        return all.sorted()
    }

    private fun runGit(vararg args: String): String {
        return try {
            val process = ProcessBuilder(listOf("git") + args.toList())
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0 && output.isBlank()) {
                "git error (exit $exitCode): ${args.joinToString(" ")}"
            } else {
                output
            }
        } catch (e: Exception) {
            "git error: ${e.message}"
        }
    }
}
