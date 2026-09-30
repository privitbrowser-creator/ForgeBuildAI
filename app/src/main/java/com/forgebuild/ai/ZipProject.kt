package com.forgebuild.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

class ProjectFile(val path: String, val bytes: ByteArray) {
    val executable: Boolean
        get() = path == "gradlew" || path.endsWith("/gradlew") || path.endsWith(".sh")

    /** UTF-8 text content, or null when the file is binary / empty / too large to send inline. */
    fun asText(): String? {
        if (bytes.isEmpty() || bytes.size > 1_500_000) return null
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

class ZipProject(val files: MutableList<ProjectFile>, val skippedNote: List<String>, val strippedRoot: String?) {
    val totalBytes: Long get() = files.sumOf { it.bytes.size.toLong() }

    fun has(path: String): Boolean = files.any { it.path == path }

    /** Build system detected at the project root (same rules as the workflow). */
    fun kind(): String? = when {
        has("pubspec.yaml") -> "Flutter"
        has("buildozer.spec") -> "Python / Buildozer"
        has("gradlew") || has("build.gradle") || has("build.gradle.kts") ||
            has("settings.gradle") || has("settings.gradle.kts") -> "Android Gradle"
        else -> null
    }
}

object ZipReader {
    private const val MAX_TOTAL = 250L * 1024 * 1024
    private const val MAX_FILE = 95L * 1024 * 1024 // GitHub rejects files of 100 MB and more

    private val JUNK_DIRS = setOf(".git", "__MACOSX", ".gradle", ".idea", "node_modules")

    private fun normalize(raw: String): String = raw.replace('\\', '/').trimStart('/')

    private fun isSafe(parts: List<String>): Boolean = parts.none { it == ".." || it.isEmpty() }

    private fun isJunk(parts: List<String>): Boolean {
        if (parts.dropLast(1).any { it in JUNK_DIRS }) return true
        val last = parts.last()
        if (last == ".DS_Store" || last == "Thumbs.db" || last == "local.properties") return true
        return false
    }

    /** Build output folders (build/ at the top level or directly under a module) are not sources. */
    private fun isBuildOutput(parts: List<String>): Boolean =
        (parts.size > 1 && parts[0] == "build") || (parts.size > 2 && parts[1] == "build")

    /**
     * Reads the ZIP twice (names first, then contents) so that junk and oversized files are never held in memory.
     * [open] must return a fresh stream on every call.
     */
    fun read(open: () -> InputStream, warn: (String) -> Unit): ZipProject {
        // Pass 1: names only.
        val names = ArrayList<String>()
        ZipInputStream(open().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val n = normalize(e.name)
                    val parts = n.split('/')
                    if (n.isNotEmpty() && isSafe(parts) && !isJunk(parts)) names.add(n)
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        if (names.isEmpty()) throw IllegalStateException("The ZIP is empty or is not a valid ZIP file.")

        // A single top-level folder wrapping everything is removed, so the project root becomes the repo root.
        val firstDir = names[0].substringBefore('/', "")
        val strip = if (firstDir.isNotEmpty() &&
            names.all { it.startsWith("$firstDir/") }
        ) "$firstDir/" else ""

        // Pass 2: contents.
        val files = ArrayList<ProjectFile>()
        val notes = ArrayList<String>()
        var junkCount = 0
        var total = 0L
        ZipInputStream(open().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val n = normalize(e.name)
                    val parts = n.split('/')
                    if (n.isNotEmpty() && isSafe(parts)) {
                        val rel = if (strip.isNotEmpty()) n.removePrefix(strip) else n
                        val rp = rel.split('/')
                        if (rel.isEmpty() || isJunk(rp) || isBuildOutput(rp)) {
                            junkCount++
                        } else {
                            val bytes = readEntry(zin)
                            if (bytes == null) {
                                notes.add("$rel (over 95 MB, skipped)")
                                warn("Skipped $rel: file is larger than 95 MB (GitHub limit).")
                            } else {
                                total += bytes.size
                                if (total > MAX_TOTAL) {
                                    throw IllegalStateException(
                                        "Project is larger than 250 MB after unzipping. Remove build outputs/large assets and try again."
                                    )
                                }
                                files.add(ProjectFile(rel, bytes))
                            }
                        }
                    }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        if (junkCount > 0) notes.add("$junkCount junk/build-output files ignored")
        return ZipProject(files, notes, if (strip.isEmpty()) null else strip.trimEnd('/'))
    }

    /** Returns null (after draining the entry) if the entry exceeds MAX_FILE. */
    private fun readEntry(zin: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var size = 0L
        var tooBig = false
        var n = zin.read(buf)
        while (n != -1) {
            size += n
            if (size > MAX_FILE) {
                tooBig = true
                out.reset()
            } else if (!tooBig) {
                out.write(buf, 0, n)
            }
            n = zin.read(buf)
        }
        return if (tooBig) null else out.toByteArray()
    }
}
