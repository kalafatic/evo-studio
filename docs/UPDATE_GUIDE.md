# EVO-Studio — Update Guide

Git is controlled by the user (Eclipse EGit). Safe update workflow (also exposed by UPM):

1. Inspect status; **stop on local changes** (never discard).
2. `git fetch --all --prune`.
3. Divergence analysis (outgoing/incoming).
4. Stop on unpushed local commits.
5. Require explicit confirmation.
6. `git pull --ff-only` only.
7. Update dependencies: `./mvnw -B -U -DskipTests clean verify` when requested.
8. Validate: `./mvnw -B validate`.

No resets, no rebases, no forced pushes, no silent branch switches. Verified on a disposable copy: dirty-tree refusal, missing-confirmation refusal, fast-forward integration, unpushed-commit refusal.
