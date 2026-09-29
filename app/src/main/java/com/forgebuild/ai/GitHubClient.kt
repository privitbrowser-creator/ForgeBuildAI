package com.forgebuild.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    private fun request(method: String, path: String, body: String? = null): String {
        val c = open(base + path, method, true, "application/vnd.github+json")
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
