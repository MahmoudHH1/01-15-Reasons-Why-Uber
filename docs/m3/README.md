# M3 Specification — Live Archive

The M3 specification lives at a public docsify site, **not** as a static PDF
upload like M1 and M2:

- **Rendered:** https://scalable-docs.netlify.app/#/uber-m3
- **Raw Markdown:** https://scalable-docs.netlify.app/uber-m3.md

Because the site is docsify (hash-routed, client-rendered from `*.md` files),
those `.md` files can be fetched directly with `curl` — perfect-fidelity,
byte-for-byte diffable archives. This directory is that archive.

## What's here

| Path | Purpose |
|---|---|
| `uber-m3.md` | Uber-theme M3 specification (DB isolation, OpenFeign, RabbitMQ, API Gateway, Kubernetes). The canonical, latest copy. |
| `Uber_Tests_Description.md` | Auto-grader test scenarios for the Uber theme. |
| `Grader_Run_Guide.md` | How to run the grader locally. |
| `archive/<YYYY-MM-DD-HHMM>/` | Immutable timestamped snapshot of all three files plus a `SHA256SUMS` manifest, written every time the snapshot script detects a change. See "Naming conventions" below. |

`M3_Specification.pdf` at the repo root was an early reference snapshot.
**The website (mirrored here) is authoritative** if the two ever diverge.

## Naming conventions

Archive directories and bot PR branches share a **UTC timestamp prefix**
(`YYYY-MM-DD-HHMM`) so a given PR and its corresponding immutable snapshot
can be paired at a glance.

### Archive directories

```
docs/m3/archive/YYYY-MM-DD-HHMM/
                └──────┬──────┘
                       └─ UTC date + HHMM the snapshot was captured
                          (e.g. `2026-05-15-0600` = 06:00 UTC, 15 May 2026)
```

Format: hyphens throughout, no colons (Windows-safe), no `T`/`Z` markers
(UTC is documented; readability beats strict ISO-8601). Sorted
lexicographically = sorted chronologically.

### Bot branch

When the workflow detects an upstream change, it pushes:

```
chore/cc/m3-snapshot-bot-YYYY-MM-DD-HHMM-<run_id>/55-25085
└──┬──┘ └──────────┬───────────────────┘ └───┬──┘ └──┬───┘
  type      descriptor + UTC prefix           │       student ID
            (matches the archive dir)         │       (project convention)
                                              └─ github.run_id
                                                 (uniqueness for
                                                  same-minute re-runs)
```

Example:

```
chore/cc/m3-snapshot-bot-2026-05-15-0600-15482910493/55-25085
                          ─────┬──────── ─────┬─────
                               │              └─ run_id; never repeats
                               └─ shared with `docs/m3/archive/2026-05-15-0600/`
```

That shared `YYYY-MM-DD-HHMM` prefix is the audit-trail bridge: paste a
PR's timestamp into `docs/m3/archive/<prefix>/` to find the immutable
snapshot it created, and vice versa.

The workflow generates the timestamp once (in a `Compute snapshot
timestamp` step) and passes it both to the script (via `SNAPSHOT_TIMESTAMP`
env var) and to peter-evans (in the `branch:` input), so the two are
guaranteed identical for any given run.

## Refreshing — stealth-change detection

`.github/workflows/m3-snapshot.yml` runs `scripts/snapshot-m3.sh` daily at
**06:00 UTC (~09:00 Cairo)**. When the upstream site has changed, it opens
(or updates) a single PR titled *"chore(cc): m3 docsify spec changed
upstream"* with the new content + dated archive snapshot. Review the diff,
then merge to update `docs/m3/` on `main`. No direct pushes — the PR is the
review gate.

You can also run the script locally for an immediate check:

```bash
./scripts/snapshot-m3.sh
```

The script:

1. Fetches all three files from the live site.
2. Compares each to the canonical copy under `docs/m3/`.
3. If **anything** changed: overwrites the canonical copy, writes a dated
   immutable snapshot under `archive/<today>/` with a SHA256 manifest, prints
   a unified diff for each changed file, and exits **2**.
4. If nothing changed: exits **0** and writes nothing.

Either way, `git status` and `git diff docs/m3/` show exactly what shifted
since the last commit. The `archive/` directory is the long-term audit trail —
even if someone overwrites the canonical copy without committing, the
immutable dated copies still exist.

The Action can also be triggered on-demand from the GitHub Actions tab
(*"Run workflow"*) — useful right before starting M3 work.

## Adding pages to the archive

If `https://scalable-docs.netlify.app/#/` (see `_sidebar.md` on that site)
adds a new page worth tracking, edit the `FILES=` array at the top of
`scripts/snapshot-m3.sh` and re-run.

## When the script reports a change

It's one of three things:

- **Bug fix / clarification** — staff corrected published wording. Read the
  diff, update any `docs/m2/*.md` companion docs that depend on changed
  wording, and commit with `docs(cc): refresh m3 spec — <summary> (<id>)`.
- **Stealth feature change** — staff silently added/removed/altered a
  requirement. Same commit, plus flag it on the team channel and update
  affected `docs/m2/*` and any in-flight branches.
- **Cosmetic only** (whitespace / typo / formatting) — commit anyway. The
  snapshot is the audit trail; consistency matters more than the size of the
  diff.

Project conventions still apply: every commit carries the developer's student
ID and lands through a PR — no direct pushes to `main`.
