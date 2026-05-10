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
| `archive/<YYYY-MM-DD>/` | Immutable dated snapshot of the three files plus a `SHA256SUMS` manifest, written each time the snapshot script detects a change. |

`M3_Specification.pdf` at the repo root was an early reference snapshot.
**The website (mirrored here) is authoritative** if the two ever diverge.

## Refreshing — stealth-change detection

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

**Recommended cadence:** run at the start of every M3 work session. The skill
`/loop 30m ./scripts/snapshot-m3.sh` will keep it polling automatically.

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
