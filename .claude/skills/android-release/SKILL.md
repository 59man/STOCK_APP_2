---
description: Cut and publish a new Android debug APK — bump the version, build, tag, and upload to GitHub Releases. Use when asked to release, publish, or ship the Android app, cut a new APK, or bump the Android version.
---

# Release the Android companion app

The APK is distributed as a GitHub Release asset on the public repo `github.com/59man/STOCK_APP_2`.
Users download it from `/releases/latest`, which the README links from the top. **Pushing commits to
`main` does nothing to the APK a user downloads** — forgetting the release step leaves the public link
serving a stale, possibly buggy build.

One fresh tag per version bump: `android-v<versionName>`. Never clobber a prior tag.

## Step 0 — find the real current version

Do not trust a remembered tag number, this file, or CLAUDE.md. Check both sources:

```bash
gh release list --limit 5
grep -n "versionCode\|versionName" android/app/build.gradle.kts
```

These are not auto-synced. The release tag should match `versionName`; if they have drifted, say so
before picking the next number.

## Step 1 — bump the version

Edit `android/app/build.gradle.kts`: increment `versionCode` by 1 and set `versionName` to the new
version. Both, every time — an unbumped `versionCode` makes Android refuse the install as a downgrade
over an existing copy.

Commit the bump before building, so the tag points at the code the APK was built from:

```bash
git add android/app/build.gradle.kts && git commit -m "chore: bump Android to v<version>"
```

## Step 2 — run the tests, then build

`core:calc` holds the ported money and chart math, and its tests are plain JVM — no emulator needed, so
there is no excuse to skip them:

```bash
cd android
./gradlew :core:calc:test
./gradlew :app:assembleDebug
```

If layout or screenshot work is part of the release, also run
`./gradlew :feature:portfolio:verifyRoborazziDebug`. A golden diff is a real finding — regenerate with
`recordRoborazziDebug` only when the change was intended.

## Step 3 — name the asset and publish

The asset filename convention is `stock-tracker-v<version>.apk`, not the build's default
`app-debug.apk` and not the older `stock-tracker-android-v<version>-debug.apk` form. Copy it to the
scratchpad directory rather than the repo, so it never gets committed:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /tmp/.../stock-tracker-v<version>.apk
git push origin main        # push the commit before creating the tag
gh release create android-v<version> /tmp/.../stock-tracker-v<version>.apk \
  --title "Android v<version>" \
  --notes "..."
```

Write the release notes as a short list of user-visible changes, in normal prose. They are read by
whoever installs the APK, not by the terminal.

## Step 4 — verify

```bash
gh release view android-v<version>
```

Confirm the tag is marked `Latest` and the asset is attached under the right filename. A release with
no asset, or with `app-debug.apk` attached, is not done.

## If CLAUDE.md is now wrong

CLAUDE.md's Android "Releases" section documents this flow and has gone stale before (it once still
named `android-v0.1.0` and described clobbering one tag). If the version numbers or conventions there
no longer match what you just did, fix it in the same commit.
