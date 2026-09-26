# ADR 0004: Local measurement, one-command A/B toggle, scoping inside queries

Status: accepted (2026-09-24)

## Context
The first benchmark (15 questions, Codex, GPT-6 Sol at Extra High) showed three things:
- Sevli gave more complete answers on pack-wide questions.
- Tokens could not be compared: most benchmark threads had been deleted.
- The agent over-used tools on exploratory questions. Q14 took about 69 calls, and Q7 and Q13 re-read sources.

Isolating the baseline was also fragile. A leftover Sevli block in CLAUDE.md contaminated one run, because Codex
reads CLAUDE.md too.

At Extra High reasoning, a run costs roughly *model requests × context size*. So the goal is fewer requests
per answered question. Lowering the reasoning level is not an option.

## Decisions
1. **Local call log.** Every tool call, MCP or CLI, appends one JSON line to `<home>/logs/calls-YYYY-MM.jsonl`:
   tool, arguments as sent, answer size, time taken and session. Logging failures are swallowed, and the cost is
   one small append. `bench/extract_tokens.py` reports per-session tokens, model requests and sevli/shell calls
   from Codex's and Claude Code's local stores, and summarizes the call log.
2. **`sevli agents on|off|status`** switches Codex (`enabled`), the Claude Code registration and the instruction
   blocks in both AGENTS.md and CLAUDE.md together. `status` searches the linked projects, their sibling folders
   and one level below them for stray blocks.
   - `setup` installs into `app/<version>` and repoints the registrations **without changing** whether each tool
     is on or off.
   - Files that Sevli created are deleted again when the block was their only content. The user's own text is always kept.
3. **Scope goes inside the query, not in a new schema parameter.** Writing `mod:<id>[,<id>]` in any query
   narrows the results to those mods and their nested jars. The target is still resolved against the whole
   environment. This is why `refs ServerTickEvents mod:openpartiesandclaims` works.
   - A term matches an exact mod id first, then the initials of a mod's name (`opac`), then a substring.
   - Inside a mod scope, a bare keyword or a `*wildcard*` searches class and member names.
4. **Answers that leave less to re-check.**
   - `outline` with a filter also searches superclasses and interfaces.
   - `source Class.a,b,c` returns several members in one call.
   - `refs` on a class lists which members each user touches.
   - `refs` and `mixins` state what they cover, so a complete answer can be recognized as complete. The
     instructions do **not** tell the model to skip verification: at Extra High that would risk correctness.
     The fix is to remove the reasons it re-checks.
   - `env filter=a,b,c` answers several mods in one call, including loader-level ids such as `fabricloader`.

## Consequences
- The tool schemas grew from about 3.9k to 4.25k characters (~80 tokens per request). This is worth it if one
  avoided call per session repays it. Check that with the rerun subset (Q5, Q7, Q13, Q14).
- Historical pack snapshots, needed for Q10 and Q12, are deliberately deferred.
