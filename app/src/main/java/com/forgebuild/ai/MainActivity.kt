package com.forgebuild.ai

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Target(val owner: String, val repo: String, val workflow: String, val branch: String)

class MainActivity : Activity() {
    // poller: dispatch + monitoring (one at a time). worker: cancel / download / test (never blocked by polling).
    private val poller: ExecutorService = Executors.newSingleThreadExecutor()
    private val worker: ExecutorService = Executors.newCachedThreadPool()
    private val pollGen = AtomicInteger(0)

    @Volatile private var alive = true
    @Volatile private var client: GitHubClient? = null
    @Volatile private var currentRun: WorkflowRun? = null
    @Volatile private var target: Target? = null

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var retryButton: Button
    private lateinit var downloadButton: Button
    private lateinit var token: EditText
    private lateinit var owner: EditText
    private lateinit var repo: EditText
    private lateinit var workflow: EditText
    private lateinit var branch: EditText
    private lateinit var buildType: Spinner

    private val buildLabels = arrayOf("Debug APK", "Release APK", "Release AAB", "Auto")
    private val buildValues = arrayOf("debug_apk", "release_apk", "release_aab", "auto")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        buildUi()
        restore()
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 14f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(4), dp(5), dp(4), dp(5))
        return t
    }

    private fun edit(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.rgb(145, 150, 170))
        e.setSingleLine(true)
        e.setPadding(dp(12), dp(4), dp(12), dp(4))
        e.setBackgroundColor(Color.rgb(27, 34, 55))
        return e
    }

    private fun button(text: String): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(Color.rgb(86, 66, 190))
        b.isAllCaps = false
        return b
    }

    private fun space(h: Int): View {
        val s = Space(this)
        s.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return s
    }

    private fun lp(h: Int = 52): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
        p.setMargins(0, dp(4), 0, dp(4))
        return p
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(10), dp(14), dp(14))
        root.setBackgroundColor(Color.rgb(9, 13, 25))

        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        content.addView(tv("⚡ ForgeBuild AI", 28f))
        content.addView(tv("GitHub Actions • Android CI/CD Control Center", 13f))
        content.addView(space(10))

        token = edit("GitHub Personal Access Token")
        token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        content.addView(token, lp())
        content.addView(tv("Token is stored encrypted (Android Keystore) on this device. It needs Actions read & write on the target repo.", 11f))

        owner = edit("Repository owner")
        repo = edit("Repository name")
        workflow = edit("Workflow file, e.g. build.yml")
        branch = edit("Branch, e.g. main")
        content.addView(owner, lp())
        content.addView(repo, lp())
        content.addView(workflow, lp())
        content.addView(branch, lp())

        buildType = Spinner(this)
        buildType.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, buildLabels)
        content.addView(buildType, lp())

        val save = button("💾 Save & Test GitHub")
        save.setOnClickListener { saveAndTest() }
        content.addView(save, lp())

        runButton = button("🚀 Start Build")
        runButton.setOnClickListener { startBuild() }
        content.addView(runButton, lp())

        cancelButton = button("⛔ Cancel Build")
        cancelButton.isEnabled = false
        cancelButton.setOnClickListener { cancelBuild() }
        content.addView(cancelButton, lp())

        retryButton = button("🔄 Retry Failed Jobs")
        retryButton.isEnabled = false
        retryButton.setOnClickListener { retry() }
        content.addView(retryButton, lp())

        downloadButton = button("📥 Download Latest APK / AAB")
        downloadButton.isEnabled = false
        downloadButton.setOnClickListener { downloadArtifact() }
        content.addView(downloadButton, lp())

        content.addView(space(8))
        statusView = tv("● READY", 16f)
        statusView.setTextColor(Color.rgb(90, 230, 150))
        content.addView(statusView)

        logView = tv("Build output will appear here…", 12f)
        logView.setTextIsSelectable(true)
        logView.setBackgroundColor(Color.rgb(15, 20, 34))
        logView.setPadding(dp(10), dp(10), dp(10), dp(10))
        logView.minHeight = dp(160)
        val logParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        logParams.setMargins(0, dp(4), 0, dp(4))
        content.addView(logView, logParams)

        val open = button("🌐 Open Run in GitHub")
        open.setOnClickListener {
            val url = currentRun?.htmlUrl
            if (!url.isNullOrEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
            } else {
                toast("No run yet")
            }
        }
        content.addView(open, lp())

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ---------- persistence ----------

    private fun v(e: EditText): String = e.text.toString().trim()

    private fun restore() {
        val p = getPreferences(Context.MODE_PRIVATE)
        val enc = p.getString("token_enc", null)
        if (!enc.isNullOrEmpty()) {
            try {
                token.setText(TokenCrypto.decrypt(enc))
            } catch (e: Exception) {
                token.setText("")
            }
        }
        owner.setText(p.getString("owner", "") ?: "")
        repo.setText(p.getString("repo", "") ?: "")
        workflow.setText(p.getString("workflow", "build.yml") ?: "build.yml")
        branch.setText(p.getString("branch", "main") ?: "main")
    }

    private fun saveAll() {
        val ed = getPreferences(Context.MODE_PRIVATE).edit()
        val t = v(token)
        if (t.isEmpty()) {
            ed.remove("token_enc")
        } else {
            try {
                ed.putString("token_enc", TokenCrypto.encrypt(t))
            } catch (e: Exception) {
                ed.remove("token_enc")
                toast("Could not encrypt token, it will not be saved")
            }
        }
        ed.putString("owner", v(owner))
        ed.putString("repo", v(repo))
        ed.putString("workflow", v(workflow))
        ed.putString("branch", v(branch))
        ed.apply()
    }

    private fun makeClient(): GitHubClient? {
        val t = v(token)
        if (t.isEmpty()) {
            toast("Enter GitHub token")
            return null
        }
        return GitHubClient(t)
    }

    private fun readTarget(): Target? {
        val t = Target(v(owner), v(repo), v(workflow), v(branch))
        if (t.owner.isEmpty() || t.repo.isEmpty() || t.workflow.isEmpty() || t.branch.isEmpty()) {
            toast("Complete repository settings first")
            return null
        }
        return t
    }

    // ---------- actions ----------

    private fun saveAndTest() {
        val c = makeClient() ?: return
        saveAll()
        setStatus("● TESTING GITHUB…", Color.rgb(255, 200, 80))
        worker.execute {
            try {
                val user = org.json.JSONObject(c.validate()).optString("login")
                client = c
                ui {
                    setStatus("● CONNECTED • @$user")
                    toast("GitHub connection successful")
                }
            } catch (e: Exception) {
                ui {
                    setStatus("● CONNECTION ERROR", Color.rgb(255, 100, 100))
                    appendError(e)
                }
            }
        }
    }

    private fun startBuild() {
        val c = makeClient() ?: return
        val t = readTarget() ?: return
        saveAll()
        client = c
        target = t
        currentRun = null
        val gen = pollGen.incrementAndGet()
        val pos = buildType.selectedItemPosition
        val buildValue = buildValues[if (pos in buildValues.indices) pos else 0]

        runButton.isEnabled = false
        cancelButton.isEnabled = false
        retryButton.isEnabled = false
        downloadButton.isEnabled = false
        logView.text = ""
        setStatus("● DISPATCHING WORKFLOW…", Color.rgb(255, 200, 80))

        poller.execute {
            try {
                val baseline = c.latestRunId(t.owner, t.repo, t.workflow, t.branch)
                try {
                    c.dispatch(t.owner, t.repo, t.workflow, t.branch, mapOf("build_type" to buildValue))
                } catch (e: IllegalStateException) {
                    val m = e.message ?: ""
                    if (m.contains("Unexpected inputs", ignoreCase = true)) {
                        // target workflow has no build_type input: dispatch without inputs
                        c.dispatch(t.owner, t.repo, t.workflow, t.branch, emptyMap())
                    } else {
                        throw e
                    }
                }
                ui { setStatus("● WORKFLOW STARTED • waiting for run…", Color.rgb(255, 200, 80)) }

                var found: WorkflowRun? = null
                var tries = 0
                while (alive && found == null && tries < 30) {
                    sleepMs(2000)
                    found = c.newRunAfter(t.owner, t.repo, t.workflow, t.branch, baseline)
                    tries++
                }
                val run = found
                    ?: throw IllegalStateException("Workflow started, but its run was not found yet. Open GitHub → Actions.")
                currentRun = run
                ui {
                    setStatus("● RUN #${run.runNumber} • ${run.status.uppercase()}", Color.rgb(255, 200, 80))
                    cancelButton.isEnabled = true
                }
                poll(c, t, run.id, gen)
            } catch (e: Exception) {
                ui {
                    runButton.isEnabled = true
                    cancelButton.isEnabled = false
                    setStatus("● BUILD ERROR", Color.rgb(255, 100, 100))
                    appendError(e)
                }
            }
        }
    }

    private fun poll(c: GitHubClient, t: Target, id: Long, gen: Int) {
        val deadline = System.currentTimeMillis() + 60L * 60L * 1000L
        var errors = 0
        while (alive && gen == pollGen.get() && System.currentTimeMillis() < deadline) {
            try {
                val r = c.getRun(t.owner, t.repo, id)
                currentRun = r
                val jobs = c.jobs(t.owner, t.repo, id)
                errors = 0

                val lines = jobs.joinToString("\n") { j ->
                    val icon = when (j.conclusion ?: j.status) {
                        "success" -> "✓"
                        "failure" -> "✗"
                        "cancelled" -> "■"
                        "in_progress" -> "●"
                        else -> "○"
                    }
                    val concl = if (j.conclusion != null) " / " + j.conclusion else ""
                    icon + " " + j.name + ": " + j.status + concl
                }
                val conclText = if (r.conclusion != null) " • " + r.conclusion.uppercase() else ""
                val statusText = "● RUN #" + r.runNumber + " • " + r.status.uppercase() + conclText
                val done = r.status == "completed"
                val failed = r.conclusion == "failure" || r.conclusion == "timed_out" || r.conclusion == "cancelled"

                if (!done) {
                    ui {
                        setStatus(statusText, Color.rgb(255, 200, 80))
                        logView.text = if (lines.isBlank()) "Waiting for jobs…" else lines
                        cancelButton.isEnabled = true
                        retryButton.isEnabled = false
                    }
                } else {
                    val sb = StringBuilder(lines)
                    val show = jobs.filter { it.conclusion == "failure" }.ifEmpty { jobs.takeLast(1) }.take(3)
                    for (j in show) {
                        sb.append("\n\n===== ").append(j.name).append(" =====\n")
                        try {
                            sb.append(c.jobLogs(t.owner, t.repo, j.id).takeLast(12000))
                        } catch (ex: Exception) {
                            sb.append("(logs unavailable: ").append(ex.message ?: "error").append(")")
                        }
                    }
                    val arts: List<ArtifactInfo> = try {
                        c.artifacts(t.owner, t.repo, id)
                    } catch (ex: Exception) {
                        emptyList()
                    }
                    val good = arts.filter { !it.expired }
                    val names = good.joinToString("\n") { "📦 " + it.name + " (" + (it.size / 1024) + " KB)" }
                    val finalText = sb.toString() + "\n\nARTIFACTS\n" + (if (names.isBlank()) "No artifact found." else names)
                    val color = if (r.conclusion == "success") Color.rgb(90, 230, 150) else Color.rgb(255, 100, 100)
                    ui {
                        setStatus(statusText, color)
                        logView.text = finalText
                        runButton.isEnabled = true
                        cancelButton.isEnabled = false
                        retryButton.isEnabled = failed
                        downloadButton.isEnabled = good.isNotEmpty()
                    }
                    return
                }
            } catch (e: Exception) {
                errors++
                if (errors == 1) ui { appendError(e) }
            }
            sleepMs(5000)
        }
        if (alive && gen == pollGen.get()) {
            ui {
                runButton.isEnabled = true
                setStatus("● MONITOR TIMEOUT • RUN CONTINUES ON GITHUB", Color.rgb(255, 200, 80))
            }
        }
    }

    private fun cancelBuild() {
        val r = currentRun ?: return
        val c = client ?: return
        val t = target ?: return
        cancelButton.isEnabled = false
        worker.execute {
            try {
                c.cancel(t.owner, t.repo, r.id)
                ui { setStatus("● CANCELLING…", Color.rgb(255, 200, 80)) }
            } catch (e: Exception) {
                ui { appendError(e) }
            }
        }
    }

    private fun retry() {
        val r = currentRun ?: return
        val c = client ?: return
        val t = target ?: return
        retryButton.isEnabled = false
        runButton.isEnabled = false
        val gen = pollGen.incrementAndGet()
        poller.execute {
            try {
                c.rerunFailed(t.owner, t.repo, r.id)
                ui { setStatus("● FAILED JOBS RESTARTED", Color.rgb(255, 200, 80)) }
                sleepMs(4000)
                poll(c, t, r.id, gen)
            } catch (e: Exception) {
                ui {
                    runButton.isEnabled = true
                    appendError(e)
                }
            }
        }
    }

    private fun downloadArtifact() {
        val r = currentRun ?: return
        val c = client ?: return
        val t = target ?: return
        downloadButton.isEnabled = false
        worker.execute {
            try {
                val a = c.artifacts(t.owner, t.repo, r.id).firstOrNull { !it.expired }
                    ?: throw IllegalStateException("No downloadable artifact found.")
                // The API answers with a short-lived pre-signed URL; it must be requested without the token.
                val url = c.artifactDownloadLocation(t.owner, t.repo, a.id)
                val safe = a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val fileName = safe + "-" + (System.currentTimeMillis() / 1000) + ".zip"
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                req.setTitle(fileName)
                req.setDescription("ForgeBuild AI artifact")
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                dm.enqueue(req)
                ui {
                    toast("Download started: $fileName (unzip it to get the APK)")
                    downloadButton.isEnabled = true
                }
            } catch (e: Exception) {
                ui {
                    downloadButton.isEnabled = true
                    appendError(e)
                }
            }
        }
    }

    // ---------- misc ----------

    private fun explain(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        val hint = when {
            e is java.net.UnknownHostException -> "No internet connection."
            m.contains("HTTP 401") -> "Token is invalid or expired."
            m.contains("workflow_dispatch", ignoreCase = true) ->
                "The workflow needs 'on: workflow_dispatch' and must exist on the selected branch."
            m.contains("HTTP 403") -> "Token lacks permission (needs Actions: read & write) or rate limit reached."
            m.contains("HTTP 404") -> "Not found: check owner, repo, workflow file name, and that the token can access the repo."
            m.contains("HTTP 422") -> "GitHub rejected the request: check the branch name and the workflow inputs."
            else -> ""
        }
        return if (hint.isEmpty()) m else m + "\n\n→ " + hint
    }

    private fun appendError(e: Exception) {
        logView.text = "ERROR\n" + explain(e) + "\n\n" + logView.text
    }

    private fun setStatus(s: String, color: Int = Color.rgb(90, 230, 150)) {
        statusView.text = s
        statusView.setTextColor(color)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread { block() }
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            alive = false
        }
    }

    override fun onDestroy() {
        alive = false
        poller.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }
}
