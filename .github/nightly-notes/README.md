# Nightly notes

Every pull request with a change users can notice adds one file here. On each push to `main`, CI
merges all of them into the body of the rolling `nightly` release, grouped under `# Run N` by the
nightly that first shipped each file. The in-app update screen shows nightly testers only the runs
newer than the build they have installed. The run map lives in the release asset
`nightly-note-runs.json`.

- Name the file `YYYY-MM-DD-<branch>.md`: the date the PR is opened, then the branch name in
  lowercase with `/` replaced by `-`. One file per PR, and never edit another PR's file, so open
  PRs never conflict.
- Use only `## New`, `## Improved` and `## Fixed`, each followed by one short `- ` bullet per change.
- Write for users, in one line per bullet, with issue numbers as `(#123)` and no em-dashes.
- CI, test and refactor-only PRs add no file.

```markdown
## Fixed
- Music no longer stays in audio offload after a long pause (#1364)
```

When a stable version is released, these notes are folded into `release-notes.md` and the
`app/src/main/assets/changelog/v*.txt` changelog, and every file here except this README is deleted.

`python3 .github/scripts/nightly_notes.py --check` validates the files; the Checks job runs it on
every PR.
