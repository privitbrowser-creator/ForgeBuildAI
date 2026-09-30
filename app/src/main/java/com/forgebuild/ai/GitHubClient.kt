package com.forgebuild.ai

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WorkflowRun(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val runNumber: Int
)

data class JobInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?
)

/** One file of a Git tree: either an existing blob [sha] or inline UTF-8 [content]. */
class TreeEntry(val path: String, val mode: String, val sha: String?, val content: String?)

data class ArtifactInfo(
    val id: Long,
    val name: String,
    val size: Long,
    val expired: Boolean
)

/** org.json on Android returns the text "null" for JSON null in optString, so read it safely. */
private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

private fun parseRun(r: JSONObject): WorkflowRun = WorkflowRun(
    r.getLong("id"),
    r.optString("status"),
    r.strOrNull("conclusion"),
    r.optString("html_url"),
    r.optInt("run_number")
)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

class GitHubClient(private val token: String) {
    private val base = "https://api.github.com"

    private fun open(url: String, method: String, withAuth: Boolean, accept: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (accept != null) c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "ForgeBuildAI/1.0")
        if (withAuth) {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        c.connectTimeout = 20000
        c.readTimeout = 30000
        return c
    }

    private fun readBody(c: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun request(method: String, path: String, body: String? = null, readTimeoutMs: Int = 30000): String {
        val c = open(base + path, method, true, "application/vnd.github+json")
        c.readTimeout = readTimeoutMs
        if (body != null || method == "POST") {
            c.doOutput = true
            if (body != null) c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = readBody(c, code)
        if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code: $text")
        return text
    }

    /** GET an API path that answers with a 302 to a short-lived URL; returns that URL (no auth needed on it). */
    private fun redirectLocation(path: String): String {
        val c = open(base + path, "GET", true, "application/vnd.github+json")
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in 300..399) {
            return c.getHeaderField("Location")
                ?: throw IllegalStateException("GitHub HTTP $code without Location header")
        }
        val text = readBody(c, code)
        throw IllegalStateException("GitHub HTTP $code: $text")
    }

    // ---------- repository creation + upload (Git Data API, one commit for the whole project) ----------

    // HttpURLConnection cannot send PATCH, which the ref update needs; OkHttp is used for that single call.
    private val ok = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun login(): String = JSONObject(validate()).getString("login")

    fun repoExists(owner: String, name: String): Boolean {
        val c = open("$base/repos/$owner/$name", "GET", true, "application/vnd.github+json")
        val code = c.responseCode
        readBody(c, code)
        return when (code) {
            200 -> true
            404 -> false
            else -> throw IllegalStateException("GitHub HTTP $code while checking the repository name")
        }
    }

    /** Creates a repo on the authenticated account with an initial commit, so the default branch exists. */
    fun createRepo(name: String, isPrivate: Boolean, description: String): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
            .put("auto_init", true)
            .put("description", description)
        return JSONObject(request("POST", "/user/repos", body.toString()))
    }

    fun branchHead(owner: String, repo: String, branch: String): String =
        JSONObject(request("GET", "/repos/$owner/$repo/git/ref/heads/$branch")).getJSONObject("object").getString("sha")

    fun createBlob(owner: String, repo: String, bytes: ByteArray): String {
        val body = JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        return JSONObject(request("POST", "/repos/$owner/$repo/git/blobs", body.toString(), 120000)).getString("sha")
    }

    fun createTree(owner: String, repo: String, baseTree: String?, entries: List<TreeEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("path", e.path).put("mode", e.mode).put("type", "blob")
            if (e.sha != null) o.put("sha", e.sha) else o.put("content", e.content ?: "")
            arr.put(o)
        }
        val body = JSONObject().put("tree", arr)
        if (baseTree != null) body.put("base_tree", baseTree)
        return JSONObject(request("POST", "/repos/$owner/$repo/git/trees", body.toString(), 120000)).getString("sha")
    }

    fun createCommit(owner: String, repo: String, message: String, treeSha: String, parentSha: String): String {
        val body = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray().put(parentSha))
        return JSONObject(request("POST", "/repos/$owner/$repo/git/commits", body.toString())).getString("sha")
    }

    fun updateRef(owner: String, repo: String, branch: String, sha: String) {
        val json = JSONObject().put("sha", sha).put("force", true).toString()
        val req = Request.Builder()
            .url("$base/repos/$owner/$repo/git/refs/heads/$branch")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ForgeBuildAI/1.0")
            .patch(json.toRequestBody("application/json".toMediaType()))
            .build()
        ok.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub HTTP ${r.code}: ${r.body?.string() ?: ""}")
        }
    }

    fun validate(): String = request("GET", "/user")

    fun dispatch(owner: String, repo: String, workflow: String, ref: String, inputs: Map<String, String>) {
        val json = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) {
            val inp = JSONObject()
            for ((k, v) in inputs) inp.put(k, v)
            json.put("inputs", inp)
        }
        request("POST", "/repos/$owner/$repo/actions/workflows/${enc(workflow)}/dispatches", json.toString())
    }

    private fun dispatchRuns(owner: String, repo: String, workflow: String, branch: String): List<WorkflowRun> {
        val data = request(
            "GET",
            "/repos/$owner/$repo/actions/workflows/${enc(workflow)}/runs" +
                "?event=workflow_dispatch&branch=${enc(branch)}&per_page=10"
        )
        val arr = JSONObject(data).optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until arr.length()).map { parseRun(arr.getJSONObject(it)) }
    }

    /** Highest existing dispatch run id, taken BEFORE dispatching, so the new run can be identified reliably. */
    fun latestRunId(owner: String, repo: String, workflow: String, branch: String): Long =
        dispatchRuns(owner, repo, workflow, branch).maxOfOrNull { it.id } ?: 0L

    fun newRunAfter(owner: String, repo: String, workflow: String, branch: String, baselineId: Long): WorkflowRun? =
        dispatchRuns(owner, repo, workflow, branch).filter { it.id > baselineId }.maxByOrNull { it.id }

    fun getRun(owner: String, repo: String, runId: Long): WorkflowRun =
        parseRun(JSONObject(request("GET", "/repos/$owner/$repo/actions/runs/$runId")))

    fun jobs(owner: String, repo: String, runId: Long): List<JobInfo> {
        val arr = JSONObject(
            request("GET", "/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100")
        ).optJSONArray("jobs") ?: JSONArray()
        return (0 until arr.length()).map {
            val j = arr.getJSONObject(it)
            JobInfo(j.getLong("id"), j.optString("name"), j.optString("status"), j.strOrNull("conclusion"))
        }
    }

    fun jobLogs(owner: String, repo: String, jobId: Long): String {
        val url = redirectLocation("/repos/$owner/$repo/actions/jobs/$jobId/logs")
        val c = open(url, "GET", false, null)
        val code = c.responseCode
        val text = readBody(c, code)
        if (code !in 200..299) throw IllegalStateException("Log download HTTP $code")
        return text
    }

    fun artifacts(owner: String, repo: String, runId: Long): List<ArtifactInfo> {
        val arr = JSONObject(
            request("GET", "/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100")
        ).optJSONArray("artifacts") ?: JSONArray()
        return (0 until arr.length()).map {
            val a = arr.getJSONObject(it)
            ArtifactInfo(a.getLong("id"), a.optString("name"), a.optLong("size_in_bytes"), a.optBoolean("expired"))
        }
    }

    /** Short-lived pre-signed URL of the artifact zip. Must be used immediately and WITHOUT the token. */
    fun artifactDownloadLocation(owner: String, repo: String, artifactId: Long): String =
        redirectLocation("/repos/$owner/$repo/actions/artifacts/$artifactId/zip")

    fun cancel(owner: String, repo: String, runId: Long) {
        request("POST", "/repos/$owner/$repo/actions/runs/$runId/cancel")
    }

    fun rerunFailed(owner: String, repo: String, runId: Long) {
        request("POST", "/repos/$owner/$repo/actions/runs/$runId/rerun-failed-jobs")
    }
}
