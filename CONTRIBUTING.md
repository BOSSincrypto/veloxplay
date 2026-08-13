# Contributing

## Ground rules

**Do not put work on the frame path.** Anything that runs per frame, per touch event or per progress tick gets scrutinised. Allocation inside those paths is a review blocker.

**Every new dependency needs a reason.** The whole app has six. If a few dozen lines of platform API do the job, write the few dozen lines. "It is the standard library for this" is not on its own an argument.

**Do not rebuild the player.** `PlayerHolder` owns the single `ExoPlayer`. Creating another one, or routing playback commands through a `MediaController` from the Activity, undoes the main performance decision in the project.

**Deliberate simplifications carry a `ponytail:` comment** naming the ceiling and the upgrade path, so the next reader can tell a shortcut from an oversight.

## Before opening a PR

```bash
./gradlew assembleDebug lintDebug
```

Both must pass. Lint failures block CI.

Test on a real device — an emulator will not surface decoder or overlay behaviour. Say which Android version you used in the PR description.

## Commit messages

Conventional commits: `feat:`, `fix:`, `perf:`, `refactor:`, `docs:`, `build:`, `ci:`.

The subject line of the last commit on `main` shows up in the release notes, so make it readable.

## Releases

You do not cut releases by hand. Merging into `main` builds, lints, versions and publishes an APK automatically. The version is `1.0.<workflow run number>`.

## Scope

Things that fit: playback correctness, latency, battery, memory, accessibility, translations.

Things that need discussion first: subtitle and audio track selection UI (deliberately not built yet), a plugin or scripting system, analytics of any kind, anything that adds a permission.
