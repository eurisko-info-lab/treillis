---
name: trellis-agent
description: Query and edit the local Trellis graph through the Agent API or project MCP tools. Use when reading entities, navigating the assembled squeak-debug image, staging Delta.Op changes, committing or publishing the workspace, executing Trellis IR, or debugging MCP/workspace persistence.
---

# Trellis Agent Graph Access

## Prefer MCP tools when available

Project MCP namespace tools (names may be prefixed by the host):

| Tool | Use |
|------|-----|
| `list_entities` | Filtered entity index (`prefix`, `kind`, `limit`) |
| `get_entity` | One entity with attrs, ports, incident edges |
| `navigate` | Semantic BFS from `entity:PATH`, `node:ID`, or `edge:ID` |
| `entity_history` | Closed change ids touching an entity |
| `workspace_status` | Dirty flag, open ops, transcript, graph root |
| `apply_ops` | Append `Delta.Op` values to the open delta |
| `commit_workspace` | Seal the open delta as one immutable change |
| `publish_workspace` | Publish only when the workspace is clean |
| `execute` | Run stored IR (`deltanet` or `ceskr`) |

If MCP is unavailable, use the HTTP Agent API on `127.0.0.1:8422` (proxied as
`/api/*` on `8421` when Squeak is running). See `docs/AGENT-API.md`.

## Read before write

1. Call `workspace_status` to see dirty state and graph root.
2. Use `list_entities` / `get_entity` / `navigate` instead of dumping the full graph.
3. Keep semantic targets as `EntityId` paths. Read content ids from entity detail
   before `connect`, `disconnect`, or `bindEntity`.

## Write only through changes

Stage operations, then commit:

```json
{
  "operations": [
    {
      "replaceEntity": {
        "entity": "workspace.scratch",
        "kind": "app.function",
        "attrs": { "source": "42" }
      }
    }
  ],
  "transcript": ["workspace.scratch := 42"]
}
```

Also accepted: canon-encoded op strings, or objects with `"canon": "(op....)"`.
Supported structured tags include `removeEntity`, `addNode`, `bindEntity`,
`connect`, `disconnect`, `addRoot`, `removeRoot`, and `refineHole`.

After staging, `commit_workspace` with a non-empty message. Publication is a
separate step and requires a clean workspace.

## Runtime and persistence rules

- Overlay observed by agents: `assembled basis + closed frontier + open delta`.
- Snapshot path: `.trellis/workspace.json` (or `TRELLIS_WORKSPACE`).
- Saved after mutations and on process exit; restored on startup.
- Do not run MCP and `SqueakServer` against the same workspace file at once.
- Do not commit `.trellis/` files.
- Launcher: `scripts/run-trellis-mcp.sh`; Cursor config: `.cursor/mcp.json`.

## Limits

- This is graph change / IR evaluation infrastructure, not a Smalltalk VM.
- Persistence is local and loopback-only; it is not a distributed repository yet.
