package com.forgebuild.ai

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class UploadResult(
    val owner: String,
    val repo: String,
    val branch: String,
    val url: String,
    val fileCount: Int
)

/** Creates a repository on the user's account and uploads a whole project as ONE commit (Git Data API). */
object RepoUploader {

    /** ZIP file name -> valid GitHub repository name. "My App (1).zip" -> "My-App-1". */
    fun repoNameFromFile(displayName: String): String {
        var n = displayName.substringBeforeLast('.', displayName)
        if (displayName.endsWith(".zip", ignoreCase = true)) n = displayName.dropLast(4)
        n = n.replace(Regex("[^A-Za-z0-9._-]+"), "-").replace(Regex("-{2,}"), "-").trim('-', '.', '_')
        if (n.isEmpty()) n = "project"
        if (n.length > 90) n = n.take(90).trimEnd('-', '.', '_')
        return n
    }

    fun freeName(c: GitHubClient, owner: String, base: String): String {
        if (!c.repoExists(owner, base)) return base
        for (i in 2..99) {
            val cand = "$base-$i"
            if (!c.repoExists(owner, cand)) return cand
        }
        throw IllegalStateException("Too many repositories named '$base'. Rename the ZIP and try again.")
    }

    private fun <T> retry(times: Int, waitMs: Long, block: () -> T): T {
        var last: Exception? = null
        for (i in 1..times) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                val m = e.message ?: ""
                if (m.contains("HTTP 401") || m.contains("HTTP 422")) throw e
                if (i < times) {
                    try {
                        Thread.sleep(waitMs * i)
                    } catch (ie: InterruptedException) {
                        throw IllegalStateException("Cancelled")
                    }
                }
            }
        }
        throw last ?: IllegalStateException("Unknown error")
    }

    /**
     * [progress] is called from worker threads with (label, done, total); [log] with a log line.
     * Text files go inline in the tree (no extra request); binary/empty files become blobs, 4 at a time.
     */
    fun upload(
        c: GitHubClient,
        files: List<ProjectFile>,
        repoName: String,
        isPrivate: Boolean,
        progress: (String, Int, Int) -> Unit,
        log: (String) -> Unit
    ): UploadResult {
        progress("Creating repository $repoName…", 0, 0)
        val created = c.createRepo(repoName, isPrivate, "Uploaded with ForgeBuild AI")
        val owner = created.getJSONObject("owner").getString("login")
        val branch = created.optString("default_branch", "main").ifEmpty { "main" }
        val url = created.optString("html_url")
        log("Repository created: $owner/$repoName ($branch, ${if (isPrivate) "private" else "public"})")

        val head = retry(8, 1500) { c.branchHead(owner, repoName, branch) }

        // Split: inline text vs blobs.
        val entries = arrayOfNulls<TreeEntry>(files.size)
        val blobJobs = ArrayList<Int>()
        for ((i, f) in files.withIndex()) {
            val mode = if (f.executable) "100755" else "100644"
            val text = f.asText()
            if (text != null) entries[i] = TreeEntry(f.path, mode, null, text) else blobJobs.add(i)
        }

        val total = blobJobs.size + 2 // blobs + tree/commit + ref
        val done = AtomicInteger(0)
        progress("Uploading ${files.size} files…", 0, total)

        if (blobJobs.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = blobJobs.map { idx ->
                    pool.submit(Callable {
                        val f = files[idx]
                        val sha = retry(4, 2500) { c.createBlob(owner, repoName, f.bytes) }
                        entries[idx] = TreeEntry(f.path, if (f.executable) "100755" else "100644", sha, null)
                        progress("Uploading ${files.size} files…", done.incrementAndGet(), total)
                    })
                }
                for (fu in futures) {
                    try {
                        fu.get()
                    } catch (e: ExecutionException) {
                        throw (e.cause as? Exception) ?: e
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        log("${files.size - blobJobs.size} text files + ${blobJobs.size} binary files prepared")

        // Trees in batches (inline content can be large); first batch has no base tree so the auto-created README is dropped.
        var baseTree: String? = null
        var batch = ArrayList<TreeEntry>()
        var batchChars = 0L
        for (e in entries) {
            val te = e ?: continue
            batch.add(te)
            batchChars += (te.content?.length ?: 0) + te.path.length + 100
            if (batch.size >= 300 || batchChars > 3_000_000L) {
                val b = batch
                val bt = baseTree
                baseTree = retry(3, 2500) { c.createTree(owner, repoName, bt, b) }
                batch = ArrayList()
                batchChars = 0
            }
        }
        if (batch.isNotEmpty() || baseTree == null) {
            val b = batch
            val bt = baseTree
            baseTree = retry(3, 2500) { c.createTree(owner, repoName, bt, b) }
        }
        val tree = baseTree!!
        progress("Committing…", done.incrementAndGet(), total)
        val commit = retry(3, 2500) { c.createCommit(owner, repoName, "Initial upload via ForgeBuild AI", tree, head) }
        retry(3, 2500) { c.updateRef(owner, repoName, branch, commit) }
        progress("Upload complete", done.incrementAndGet(), total)
        log("Commit ${commit.take(7)} pushed to $branch")
        return UploadResult(owner, repoName, branch, url, files.size)
    }
}
