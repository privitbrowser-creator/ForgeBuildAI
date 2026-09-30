# ForgeBuild AI Architecture

The Android app is a GitHub Actions controller.

1. User enters a GitHub token and repository/workflow.
2. App records the latest dispatch run id, calls workflow_dispatch, then finds the new run (id greater than the recorded one).
3. App polls the run and jobs; on completion it reads failed-job logs and lists artifacts.
4. Artifact download: the API answers 302 to a pre-signed URL; the app requests it without the token and hands the URL to DownloadManager.
5. GitHub Actions performs the real compilation on its runner.

Threads: `poller` (dispatch + monitoring) and `worker` (cancel, test, download) are separate so Cancel works during a build.

Automatic project detection (workflow):
- pubspec.yaml -> Flutter
- buildozer.spec -> Python/Buildozer
- Gradle files -> Android Gradle

ZIP upload flow (phase 2):
1. `ZipReader` reads the ZIP twice (names, then contents) so junk/oversized files never sit in memory.
2. `RepoUploader` creates the repo (auto_init), sends text files inline in the Git tree, binary files as blobs (4 in parallel), commits once and moves the branch with a PATCH (OkHttp, because HttpURLConnection has no PATCH).
3. The app then dispatches the workflow, retrying 404s for ~30 s while GitHub registers a freshly pushed workflow.
