package com.forgebuild.ai

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
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
    private lateinit var liveLog: LiveLogView
    private lateinit var stepper: PipelineStepper
    private lateinit var runButton: Button
    private lateinit var pickButton: Button
    private lateinit var privateSwitch: Switch
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
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

    // Pipeline steps shown in the stepper. Earlier steps light up in later phases;
    // the existing "build an existing repo" flow drives the Build step for now.
    private val stepLabels = listOf("Pick ZIP", "Analyse", "Icon", "Create repo", "Upload", "Build", "Download")
    private val stepBuildIndex = 5
    private val REQ_PICK_ZIP = 4101
    private val stepDownloadIndex = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        buildUi()
        restore()
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 14f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Theme.TEXT)
        t.setPadding(dp(4), dp(5), dp(4), dp(5))
        return t
    }

    private fun edit(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setTextColor(Theme.TEXT)
        e.setHintTextColor(Theme.TEXT_MUTED)
        e.setSingleLine(true)
        e.setPadding(dp(12), dp(4), dp(12), dp(4))
        e.background = Theme.editBg(this)
        return e
    }

    private fun button(text: String, primary: Boolean = false): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        if (primary) {
            b.background = Theme.primaryButtonBg(this)
            b.setTextColor(Theme.ON_PRIMARY)
            b.typeface = Typeface.DEFAULT_BOLD
        } else {
            b.background = Theme.ghostButtonBg(this)
            b.setTextColor(Theme.TEXT)
        }
        Theme.addPressAnimation(b)
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
        root.setBackgroundColor(Theme.BG)

        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val title = tv("⚡ ForgeBuild AI", 28f)
        title.setTextColor(Theme.PRIMARY_GLOW)
        title.typeface = Typeface.DEFAULT_BOLD
        content.addView(title)
        val subtitle = tv("GitHub Actions • Android CI/CD Control Center", 13f)
        subtitle.setTextColor(Theme.TEXT_MUTED)
        content.addView(subtitle)
        content.addView(space(6))

        stepper = PipelineStepper(this)
        stepper.setSteps(stepLabels)
        val stepLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(66)
        )
        stepLp.setMargins(0, dp(2), 0, dp(6))
        content.addView(stepper, stepLp)

        // ---- one-tap flow: ZIP -> new repo -> upload -> build ----
        val zipCard = LinearLayout(this)
        zipCard.orientation = LinearLayout.VERTICAL
        zipCard.background = Theme.card(this)
        zipCard.setPadding(dp(12), dp(10), dp(12), dp(10))
        val zipLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        zipLp.setMargins(0, dp(4), 0, dp(8))
        content.addView(zipCard, zipLp)

        pickButton = button("📦 Pick project ZIP → Create repo → Build", primary = true)
        pickButton.setOnClickListener { pickZip() }
        zipCard.addView(pickButton, lp(56))

        val zipHint = tv(
            "The ZIP is unpacked on this phone, a new GitHub repo named after the file is created, everything is uploaded in one commit and the build starts automatically. Use a classic token with 'repo' + 'workflow' scopes.",
            11f
        )
        zipHint.setTextColor(Theme.TEXT_MUTED)
        zipCard.addView(zipHint)

        privateSwitch = Switch(this)
        privateSwitch.text = "Private repository"
        privateSwitch.setTextColor(Theme.TEXT)
        privateSwitch.isChecked = true
        zipCard.addView(privateSwitch, lp(44))

        progressLabel = tv("", 12f)
        progressLabel.setTextColor(Theme.PRIMARY_GLOW)
        progressLabel.visibility = View.GONE
        zipCard.addView(progressLabel)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.progressTintList = ColorStateList.valueOf(Theme.PRIMARY)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(Theme.STROKE)
        progressBar.indeterminateTintList = ColorStateList.valueOf(Theme.PRIMARY)
        progressBar.visibility = View.GONE
        zipCard.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)))

        // ---- settings card ----
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Theme.card(this)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        val cardLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cardLp.setMargins(0, dp(4), 0, dp(8))
        content.addView(card, cardLp)

        token = edit("GitHub Personal Access Token")
        token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        card.addView(token, lp())
        val tokenHint = tv(
            "Token is stored encrypted (Android Keystore) on this device. It needs Actions read & write on the target repo.",
            11f
        )
        tokenHint.setTextColor(Theme.TEXT_MUTED)
        card.addView(tokenHint)

        owner = edit("Repository owner")
        repo = edit("Repository name")
        workflow = edit("Workflow file, e.g. build.yml")
        branch = edit("Branch, e.g. main")
        card.addView(owner, lp())
        card.addView(repo, lp())
        card.addView(workflow, lp())
        card.addView(branch, lp())

        buildType = Spinner(this)
        buildType.adapter =
            ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, buildLabels)
        buildType.background = Theme.editBg(this)
        buildType.setPadding(dp(10), 0, dp(4), 0)
        card.addView(buildType, lp())

        val save = button("💾 Save & Test GitHub")
        save.setOnClickListener { saveAndTest() }
        card.addView(save, lp())

        // ---- actions ----
        runButton = button("🚀 Start Build", primary = true)
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
        statusView.setTextColor(Theme.PRIMARY)
        content.addView(statusView)

        liveLog = LiveLogView(this)
        val logLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
        )
        logLp.setMargins(0, dp(4), 0, dp(4))
        content.addView(liveLog, logLp)

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
        privateSwitch.isChecked = p.getBoolean("private_repo", true)
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
        ed.putBoolean("private_repo", privateSwitch.isChecked)
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
        setStatus("● TESTING GITHUB…", Theme.WARN)
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
                    setStatus("● CONNECTION ERROR", Theme.ERROR)
                    appendError(e)
                }
            }
        }
    }

    private fun startBuild(fromUpload: Boolean = false) {
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

        pickButton.isEnabled = false
        cancelButton.isEnabled = false
        retryButton.isEnabled = false
        downloadButton.isEnabled = false
        if (!fromUpload) {
            liveLog.clearLog()
            stepper.reset()
        }
        val retries = if (fromUpload) 10 else 0
        liveLog.setLive(true)
        stepper.setState(stepBuildIndex, PipelineStepper.StepState.ACTIVE)
        liveLog.log("Dispatching '${t.workflow}' on ${t.owner}/${t.repo}@${t.branch} (build_type=$buildValue)", LiveLogView.Level.STEP)
        setStatus("● DISPATCHING WORKFLOW…", Theme.WARN)

        poller.execute {
            try {
                // A workflow pushed seconds ago may not be registered yet: retry 404s after an upload.
                val baseline = retryNotFound(retries) { c.latestRunId(t.owner, t.repo, t.workflow, t.branch) }
                retryNotFound(retries) {
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
                }
                ui {
                    liveLog.log("Workflow dispatched", LiveLogView.Level.OK)
                    setStatus("● WORKFLOW STARTED • waiting for run…", Theme.WARN)
                }

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
                    liveLog.log("Run #${run.runNumber} found (id=${run.id})", LiveLogView.Level.OK)
                    setStatus("● RUN #${run.runNumber} • ${run.status.uppercase()}", Theme.WARN)
                    cancelButton.isEnabled = true
                }
                poll(c, t, run.id, gen)
            } catch (e: Exception) {
                ui {
                    runButton.isEnabled = true
                    pickButton.isEnabled = true
                    cancelButton.isEnabled = false
                    liveLog.setLive(false)
                    stepper.setState(stepBuildIndex, PipelineStepper.StepState.FAILED)
                    setStatus("● BUILD ERROR", Theme.ERROR)
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
                        setStatus(statusText, Theme.WARN)
                        liveLog.setBody(if (lines.isBlank()) "Waiting for jobs…" else lines)
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
                    val color = if (r.conclusion == "success") Theme.PRIMARY else Theme.ERROR
                    ui {
                        setStatus(statusText, color)
                        liveLog.setBody(finalText)
                        liveLog.setLive(false)
                        stepper.setState(
                            stepBuildIndex,
                            if (r.conclusion == "success") PipelineStepper.StepState.DONE
                            else PipelineStepper.StepState.FAILED
                        )
                        if (good.isNotEmpty()) {
                            stepper.setState(stepDownloadIndex, PipelineStepper.StepState.ACTIVE)
                        }
                        liveLog.log(
                            "Run finished: ${r.conclusion ?: "unknown"}",
                            if (r.conclusion == "success") LiveLogView.Level.OK else LiveLogView.Level.ERR
                        )
                        runButton.isEnabled = true
                        pickButton.isEnabled = true
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
                pickButton.isEnabled = true
                liveLog.setLive(false)
                setStatus("● MONITOR TIMEOUT • RUN CONTINUES ON GITHUB", Theme.WARN)
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
                ui { setStatus("● CANCELLING…", Theme.WARN) }
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
        pickButton.isEnabled = false
        val gen = pollGen.incrementAndGet()
        poller.execute {
            try {
                c.rerunFailed(t.owner, t.repo, r.id)
                ui {
                    setStatus("● FAILED JOBS RESTARTED", Theme.WARN)
                    liveLog.setLive(true)
                    stepper.setState(stepBuildIndex, PipelineStepper.StepState.ACTIVE)
                    liveLog.log("Failed jobs restarted", LiveLogView.Level.STEP)
                }
                sleepMs(4000)
                poll(c, t, r.id, gen)
            } catch (e: Exception) {
                ui {
                    runButton.isEnabled = true
                    pickButton.isEnabled = true
                    liveLog.setLive(false)
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
                    stepper.setState(stepDownloadIndex, PipelineStepper.StepState.DONE)
                    liveLog.log("Artifact download started: $fileName", LiveLogView.Level.OK)
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

    // ---------- ZIP -> new repo -> upload -> build ----------

    private fun pickZip() {
        if (v(token).isEmpty()) {
            toast("Enter GitHub token first")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
        try {
            startActivityForResult(i, REQ_PICK_ZIP)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_ZIP && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            startUploadFlow(uri)
        }
    }

    private fun displayNameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val n = cur.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "project.zip"
    }

    private fun showProgress(label: String, done: Int, total: Int) {
        progressLabel.text = label
        progressLabel.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        if (total <= 0) {
            progressBar.isIndeterminate = true
        } else {
            progressBar.isIndeterminate = false
            progressBar.max = total
            progressBar.progress = done
        }
    }

    private fun hideProgress() {
        progressLabel.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun setControlsBusy(busy: Boolean) {
        pickButton.isEnabled = !busy
        runButton.isEnabled = !busy
        if (busy) {
            cancelButton.isEnabled = false
            retryButton.isEnabled = false
            downloadButton.isEnabled = false
        }
    }

    private fun startUploadFlow(uri: Uri) {
        val c = makeClient() ?: return
        saveAll()
        client = c
        val priv = privateSwitch.isChecked
        val fileName = displayNameOf(uri)
        pollGen.incrementAndGet()
        currentRun = null
        setControlsBusy(true)
        liveLog.clearLog()
        liveLog.setLive(true)
        stepper.reset()
        stepper.setState(0, PipelineStepper.StepState.DONE)
        stepper.setState(1, PipelineStepper.StepState.ACTIVE)
        setStatus("● ANALYSING ZIP…", Theme.WARN)
        showProgress("Reading $fileName…", 0, 0)
        liveLog.log("Selected: $fileName", LiveLogView.Level.STEP)

        worker.execute {
            try {
                val project = ZipReader.read(
                    { contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open the selected file") },
                    { m -> liveLog.log(m, LiveLogView.Level.WARN) }
                )
                val kind = project.kind() ?: throw IllegalStateException(
                    "No build system found at the project root (expected pubspec.yaml, buildozer.spec or build.gradle). " +
                        "Make sure the project files are directly inside the ZIP (or inside a single folder)."
                )
                val files = ArrayList<ProjectFile>(project.files)

                // Use the project's own dispatchable workflow, otherwise add the bundled one.
                val wfFile = files.firstOrNull {
                    it.path.startsWith(".github/workflows/") &&
                        it.path.count { ch -> ch == '/' } == 2 &&
                        (it.path.endsWith(".yml") || it.path.endsWith(".yaml")) &&
                        String(it.bytes, Charsets.UTF_8).contains("workflow_dispatch")
                }
                val wf: String
                var injected = false
                if (wfFile != null) {
                    wf = wfFile.path.substringAfterLast('/')
                } else {
                    val tpl = assets.open("build.yml").use { it.readBytes() }
                    files.removeAll { it.path == ".github/workflows/forgebuild.yml" }
                    files.add(ProjectFile(".github/workflows/forgebuild.yml", tpl))
                    wf = "forgebuild.yml"
                    injected = true
                }

                val kb = files.sumOf { it.bytes.size.toLong() } / 1024
                ui {
                    liveLog.log("Project type: $kind • ${files.size} files • $kb KB", LiveLogView.Level.OK)
                    if (project.strippedRoot != null) {
                        liveLog.log("Removed wrapper folder '${project.strippedRoot}/'", LiveLogView.Level.INFO)
                    }
                    for (n in project.skippedNote) liveLog.log(n, LiveLogView.Level.WARN)
                    liveLog.log(
                        if (injected) "No workflow in the ZIP: added .github/workflows/forgebuild.yml"
                        else "Using the project's workflow: $wf",
                        LiveLogView.Level.INFO
                    )
                    stepper.setState(1, PipelineStepper.StepState.DONE)
                    stepper.setState(3, PipelineStepper.StepState.ACTIVE)
                    setStatus("● CREATING REPOSITORY…", Theme.WARN)
                }

                val login = c.login()
                val name = RepoUploader.freeName(c, login, RepoUploader.repoNameFromFile(fileName))
                ui { liveLog.log("Repository name: $login/$name", LiveLogView.Level.STEP) }

                val uploadStarted = java.util.concurrent.atomic.AtomicBoolean(false)
                val result = RepoUploader.upload(
                    c, files, name, priv,
                    { label, done, total ->
                        ui {
                            if (!label.startsWith("Creating") && uploadStarted.compareAndSet(false, true)) {
                                stepper.setState(3, PipelineStepper.StepState.DONE)
                                stepper.setState(4, PipelineStepper.StepState.ACTIVE)
                            }
                            showProgress(label, done, total)
                            setStatus("● $label", Theme.WARN)
                        }
                    },
                    { line -> liveLog.log(line, LiveLogView.Level.OK) }
                )

                ui {
                    stepper.setState(3, PipelineStepper.StepState.DONE)
                    stepper.setState(4, PipelineStepper.StepState.DONE)
                    hideProgress()
                    owner.setText(result.owner)
                    repo.setText(result.repo)
                    workflow.setText(wf)
                    branch.setText(result.branch)
                    saveAll()
                    liveLog.log("Uploaded ${result.fileCount} files → ${result.url}", LiveLogView.Level.OK)
                    startBuild(fromUpload = true)
                }
            } catch (e: Exception) {
                ui {
                    hideProgress()
                    liveLog.setLive(false)
                    setControlsBusy(false)
                    for (i in 0 until stepLabels.size) {
                        if (stepper.stateOf(i) == PipelineStepper.StepState.ACTIVE) {
                            stepper.setState(i, PipelineStepper.StepState.FAILED)
                        }
                    }
                    setStatus("● UPLOAD ERROR", Theme.ERROR)
                    appendError(e)
                }
            }
        }
    }

    /** A workflow pushed seconds ago may answer 404/422 until GitHub registers it: retry a few times. */
    private fun <T> retryNotFound(times: Int, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IllegalStateException) {
                val m = e.message ?: ""
                val stale = m.contains("HTTP 404") ||
                    (m.contains("HTTP 422") && m.contains("workflow_dispatch", ignoreCase = true))
                if (!alive || attempt >= times || !stale) throw e
                attempt++
                sleepMs(3000)
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
            m.contains("HTTP 403") -> "Token lacks permission (classic token needs 'repo' + 'workflow'; fine-grained needs Actions read & write) or a rate limit was reached. Wait a minute and retry."
            m.contains("HTTP 404") -> "Not found: check owner, repo, workflow file name, and that the token can access the repo."
            m.contains("HTTP 422") -> "GitHub rejected the request: check the branch name and the workflow inputs."
            else -> ""
        }
        return if (hint.isEmpty()) m else m + "\n\n→ " + hint
    }

    private fun appendError(e: Exception) {
        liveLog.error("ERROR\n" + explain(e))
    }

    private fun setStatus(s: String, color: Int = Theme.PRIMARY) {
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
