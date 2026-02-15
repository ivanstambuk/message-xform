# Multi-Agent Coordination on a Single Branch (No Worktrees)

## Metadata
- Date: 2026-02-15
- Status: Complete
- Scope: Practical coordination patterns for multiple AI agents editing the same repository and same branch concurrently, without branches/worktrees.

## Context and Constraints

Owner constraints for this repo/session:
- Multiple AI agents can run at the same time (Codex, Opus, GLM).
- All agents work on the same branch and same working tree.
- No feature branches and no git worktrees.
- Coordination should be machine-readable and low-friction.

Current local mechanism:
- Active lock board: `.agent/session/active-work.yaml`
- Governance rule: `AGENTS.md` Rule 21 (Concurrent Agent Coordination Board).

## What Common Systems Do

### 1) Isolate work per task, then integrate through review gates

Common pattern in AI coding products is to execute each task in an isolated environment and integrate through PR-like review:
- OpenAI Codex cloud runs tasks in sandboxed containers and supports parallel background tasks.
- GitHub Copilot coding agent works in a GitHub Actions-powered environment and opens PRs.

Implication for this repo:
- You intentionally chose not to use branches/worktrees, so you cannot use the same isolation primitive.
- The equivalent safeguard in a shared tree is explicit lock coordination + strict integration checks.

### 2) Serialize integration on busy mainlines

GitHub merge queue exists specifically to keep busy branches from breaking and to serialize merges with required checks.

Implication:
- Even without PR branches, the same idea applies: maintain a lightweight "integration queue" behavior by ensuring one mutating writer per locked scope and running required checks before commit.

### 3) Use explicit ownership boundaries for scope routing

GitHub CODEOWNERS formalizes path ownership and review routing.

Implication:
- For multi-agent execution, path ownership is useful as a routing hint before lock acquisition (which agent should take which area), reducing contention.

### 4) Use lock + lease semantics for shared resources

Distributed systems patterns:
- Kubernetes Lease objects coordinate leadership and heartbeats.
- etcd lock API binds lock ownership to leases and auto-releases on lease expiry.
- ZooKeeper lock recipe avoids herd effects and avoids busy polling.
- Terraform state locking blocks concurrent writers; if lock fails, operation stops; force-unlock is exceptional and guarded by lock ID.

Implication:
- Your active-work file should stay as explicit lock registry.
- Add lease-like metadata (timestamps + owner/task ID) and explicit force-release policy (human mediated) for crashed/stale sessions.
- Keep "no busy waiting" (already required).

### 5) Apply pessimistic locking where merge is painful

Git LFS locking is used when merges are hard (especially binary files): only one editor at a time, with explicit lock/unlock.

Implication:
- Treat high-risk shared assets similarly (scripts that control infra, generated config, lockfiles): require exclusive lock in `active-work.yaml` before mutation.

## Recommended Improvements to Current Workflow

These improvements fit your constraints (same branch, same working tree).

### A) Keep `active-work.yaml` as the single source of truth

Keep the current design:
- YAML only
- active entries only
- remove on completion
- no busy waiting

This is already implemented in `AGENTS.md` Rule 21 and `.agent/session/active-work.yaml`.

### B) Add lock taxonomy conventions (small but important)

Standardize `locks.resources` values to reduce ambiguity:
- `workflow:retro`
- `workflow:init`
- `infra:docker:pa-e2e`
- `infra:gradle:quality-gate`
- `artifact:binaries`

Standardize `locks.paths` usage:
- Prefer exact files first.
- Use directory/glob only when truly needed.
- Avoid root-level broad locks unless doing sweeping refactors.

### C) Add deterministic conflict check before mutation

Before any write:
1. Read `.agent/session/active-work.yaml`.
2. If overlap on `paths` or `resources`, do not mutate that scope.
3. If `workflow:retro` exists, block mutating workflow actions.

This is policy today; automate it with a helper script to reduce human error.

### D) Add a tiny lock helper CLI (recommended)

Add script (example): `scripts/agent-lock.sh`:
- `acquire` (adds entry)
- `touch` (updates `updated_at`)
- `release` (removes entry)
- `check` (returns non-zero on conflict)

Reason:
- Removes manual YAML editing mistakes.
- Makes behavior consistent across Codex/Opus/GLM.

### E) Add explicit force-release policy (human-only)

Because you do not want auto timeouts:
- No automatic stale lock stealing.
- If an agent appears dead, human may force-remove entry after confirmation.
- Record that cleanup in session notes (`docs/_current-session.md` / retro), not in active board.

### F) Add "integration gate" discipline for same-branch concurrency

For shared-branch operation, keep this sequence strict:
1. Acquire lock
2. Edit/test
3. Run required quality checks
4. Commit atomic unit
5. Release lock

This mirrors merge-queue intent but inside one working tree.

### G) Optional: add path ownership hints (lightweight CODEOWNERS-like)

Not mandatory, but useful for routing:
- Keep a machine-readable map (for agents only), e.g. `.agent/session/path-owners.yaml`.
- Use it as advisory routing before lock acquisition.

## Suggested Next Step for message-xform

1. Implement `scripts/agent-lock.sh` and switch all agents to use it (instead of manual YAML edits).
2. Add lock resource naming conventions to `AGENTS.md` Rule 21.
3. Optionally add `.agent/session/path-owners.yaml` for predictable task partitioning.

## Sources

1. OpenAI Codex cloud docs — sandboxed parallel task environments
   - https://platform.openai.com/docs/codex
2. OpenAI Introducing Codex — each task runs in isolated cloud sandbox
   - https://openai.com/index/introducing-codex/
3. GitHub Copilot coding agent docs — background agent, PR workflow, Actions-powered environment
   - https://docs.github.com/en/copilot/using-github-copilot/using-copilot-coding-agent-to-work-on-tasks/about-assigning-tasks-to-copilot
4. GitHub merge queue docs — serialized safe integration for busy branches
   - https://docs.github.com/pull-requests/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/adding-a-pull-request-to-the-merge-queue
5. GitHub CODEOWNERS docs — path ownership model
   - https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners
6. Kubernetes Lease docs — lock/coordination and leader election primitives
   - https://kubernetes.io/docs/concepts/architecture/leases/
7. etcd concurrency lock API — lock ownership tied to lease, unlock semantics
   - https://etcd.io/docs/v3.2/dev-guide/api_concurrency_reference_v3/
8. ZooKeeper lock recipe — orderly lock acquisition, no polling/herd effect
   - https://zookeeper.apache.org/doc/r3.1.2/recipes.html
9. Terraform state locking — writer exclusion and guarded force-unlock pattern
   - https://developer.hashicorp.com/terraform/language/state/locking
10. Git LFS locking behavior (manual pages)
   - https://manpages.debian.org/testing/git-lfs/git-lfs-lock.1.en.html
   - https://docs.gitlab.com/user/project/file_lock/
