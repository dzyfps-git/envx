# ADR 0003: Hand-rolled MCP stdio server; few tools, capped output

Status: accepted (2026-09-23)

## Decision
- MCP here is newline-delimited JSON-RPC over stdio with four methods: `initialize`, `tools/list`,
  `tools/call` and `ping`. That fits in about 150 lines (`tools/McpServer.java`), so the official SDK and its
  Reactor/Jackson dependency tree are not used.
- There are **8 tools** (`env`, `find`, `outline`, `source`, `refs`, `mixins`, `check_mixins`, `grep`), each with
  a one-line description. Tool schemas are re-sent with every agent request, so every extra tool is a recurring cost.
- **Every answer is capped** at about 6000 characters by default (`query/Out.java`). When a cap is hit, the answer
  says how much was omitted and how to narrow the query.
- The CLI runs **the same tool registry** (`tools/Tools.java`). A shell call costs no schema tokens, and agents
  can use it where MCP is unavailable.

## Consequences
When Claude Code or Codex change their MCP config formats, only `cli/AgentSetup.java` needs updating.
