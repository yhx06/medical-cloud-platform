# Project artifact location

The user requires **all files belonging to this project** to remain under:

`E:/云服务工程/云服务项目-医疗云`

- The active course implementation is this linked worktree, `.worktrees/course-environment`; preserve other checkouts and their unrelated changes.
- New generated artifacts belong in the project's `.local/tests`, `.local/builds`, `.local/runtime`, or `.local/evidence` directories. These are local-only and must not be committed.
- Do not create project test, build, runtime, download, log, backup, or temporary directories directly under `E:/`, under `C:/Users/yhx`, or under system temporary directories. For tools that need TEMP/TMP, use a unique project-local directory and restore the prior environment in `finally`.
- Existing scattered artifacts are being inventoried and relocated. Consult the latest clinical SDD progress and relocation manifests before relying on a historical absolute path. Historical evidence must not be rewritten to pretend it originally used the new paths.
- Only the root coordinator may relocate artifacts or operate real services. Do not move a running database or service directory. Check exact source/destination paths, ownership, process identities, listeners and database UUID before operations; preserve data and verify recovery.
- Installed third-party software and shared Codex/system files are not project artifacts. Do not move or modify teacher installations or other projects.
- Keep credentials and clinical content private; do not print them, put them in Git, or weaken ACLs during relocation.
