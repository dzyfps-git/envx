# Benchmark harness

Does Sevli save agents work without costing correctness? Three tiers answer that:

| Tier | When | What |
|---|---|---|
| 1: tool checks | every release | `check.py run`: each answer key's Sevli calls must surface the facts its question needs. No agent runs. |
| 2: agent runs | milestones | `agents.py run`: the same question headless with and without Sevli (`codex exec`, `claude -p`); tokens, requests, tool calls and answer facts. |
| 3: replays | after answer changes | `check.py replay`: recorded Sevli calls re-run on a new version, sizes compared. |

## 1.0 result
18 questions from real modding work on a 446-mod Fabric 1.20.1 server (Codex GPT-6 Sol at High; Claude Code at its
default effort). Like-for-like pairs (the same model and effort, with and without Sevli): median **−88%** tokens, at
least as correct on all five and more correct on four. With Sevli, Codex answered 17 of 18 correctly (the partial
one was also partial without Sevli); every Claude Code run used Sevli, and 5 of 6 were correct. The questions and keys
describe that private server and are not published; the ADRs refer to them as Q1-Q18.

## Your own benchmark
1. Copy `local.example.json` to `local.json` (ignored) and map workspace names to folders; `env` names the
   environment golden tests and checks use.
2. Write `questions.md` with a table row per question: ``| 1 | the question text | `{workspace}` |``.
3. Write a key per question in `expected/NN.md` **before** any agent run: the answer, then a ```check block (the
   format is at the top of `check.py`) with the Sevli calls that should find it, `expect:` facts and `answer:` facts.
4. `python bench/check.py run` until every fact is found.
5. For agent runs: `sevli off` (status must say `OFF (clean baseline)`); `python bench/agents.py plan <Q...>`
   shows the exact commands, `run` executes them, `report` compares; then `sevli on`. ON runs add Sevli per run
   and change nothing else. Agent runs cost credits: use them for milestones.

`extract_tokens.py` turns Codex and Claude Code session files, plus Sevli's call log, into per-session numbers.
`analyze_codex_sessions.py` finds recurring discovery work (unzipping jars, `javap`, grepping decompiled code) in
local Codex sessions, a source of benchmark questions. Everything runs locally; nothing is sent anywhere.
