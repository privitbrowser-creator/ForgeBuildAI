# ForgeBuild AI

A lightweight Android control center for GitHub Actions Android builds.

## What it does

- **One tap: pick a project ZIP from the phone → the app unpacks it, creates a GitHub repository named after the file (`My App (1).zip` → `My-App-1`, a number is added if the name is taken), uploads everything in a single commit and starts the build automatically.**
- If the ZIP has no workflow with `workflow_dispatch`, `.github/workflows/forgebuild.yml` is added automatically (a copy of `app/src/main/assets/build.yml` — keep both in sync).
- A single wrapper folder inside the ZIP is removed; `.git`, `__MACOSX`, `node_modules`, `.gradle`, `.idea`, `local.properties` and top-level `build/` outputs are ignored. Files over 95 MB are skipped.
- Stores a GitHub token encrypted with the Android Keystore.
- Tests GitHub authentication.
- Dispatches a workflow on any selected branch with a build type input (Debug APK / Release APK / Release AAB / Auto).
- Polls the workflow run and jobs (up to 60 minutes) and shows job status and logs.
- Cancels a running workflow, retries failed jobs, opens the run in GitHub.
- Downloads the built artifact (a .zip containing the APK/AAB) to Downloads.

## Build the APK

Push this repository to GitHub, then open:

Actions → ForgeBuild AI - Android → Run workflow

The APK is uploaded as a GitHub Actions artifact (unzip it, install the APK).

## Using it on another repository

Copy `.github/workflows/build.yml` into that repository (it must exist on the branch you build).
If the workflow has no `build_type` input, the app automatically dispatches without inputs.

## GitHub token

Creating repositories needs a **classic token** with scopes `repo` and `workflow` (fine-grained tokens cannot create user repositories).
Building an existing repo only: a fine-grained token with **Actions: Read and write** is enough.

## Notes

- Release APKs without a signing config are signed with a throwaway key by the workflow so they are installable. Use real signing for distribution.
- Requires Android 10 (API 29) or newer.
