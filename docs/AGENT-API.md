# Agent API

The local Trellis agent API exposes read/query and change-staging operations over
the assembled Squeak image. AI agents use it to inspect the current graph branch
and append validated `Delta.Op` values to the open workspace delta.

Start the services:

```bash
./scripts/run-squeak.sh
```

| Port | Service |
|------|---------|
| 8421 | Web shell; proxies `/api/*` to the runtime |
| 8422 | Scala agent runtime |

## MCP (Cursor and other hosts)

For AI agents in Cursor, enable the project MCP server in `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "trellis": {
      "command": "/absolute/path/to/trellis-bootstrap/scripts/run-trellis-mcp.sh"
    }
  }
}
```

Or run the stdio server directly:

```bash
./scripts/run-trellis-mcp.sh
```

Tools exposed: `list_entities`, `get_entity`, `navigate`, `entity_history`,
`workspace_status`, `apply_ops`, `commit_workspace`, `publish_workspace`, `execute`.

The MCP server embeds the same runtime as port 8422. Do not run MCP and
`SqueakServer` concurrently against the same workspace file.

## Workspace persistence

The open workspace delta, sealed local frontier, and stored changes persist to
`.trellis/workspace.json` (override with `TRELLIS_WORKSPACE`). The runtime saves:

- after every mutation
- on process exit (shutdown hook)

Restarting `SqueakServer` or the MCP server restores the saved branch overlay.
Delete the file to reset to a clean assembled image.

All endpoints return JSON. Read endpoints accept `GET`. Write endpoints accept
`POST` with a JSON body and still accept the older query-string `GET` form where
noted for the browser shell.

## Read

### `GET /entities`

List entity paths without dumping the full graph.

Query parameters:

- `prefix` — optional path prefix filter
- `kind` — optional node kind filter
- `limit` — max results (default 256, max 4096)

Example:

```bash
curl 'http://127.0.0.1:8422/entities?prefix=example.&limit=16'
```

### `GET /entity?path=ENTITY.PATH`

Return one entity's node, attrs, ports, and incident edges.

### `GET /navigate?center=entity:ENTITY.PATH`

Run the graph-defined Squeak navigator from a semantic selection. Selection
forms:

- `entity:example.tailrec.fibonacci`
- `node:CONTENT_ID`
- `edge:CONTENT_ID`

### `GET /history?entity=ENTITY.PATH`

Return closed change ids that touched the entity footprint on the local branch.

### `GET /workspace/status`

Branch metadata, dirty flag, open operation count, transcript, and current graph root.

### `GET /workspace/graph`

Full graph dump for the current overlay (`assembled basis + closed frontier + open delta`).

### `GET /lsp/document`

Semantic Code View text and symbol table when the assembly includes LSP policy.

### `GET /execute?workspace=ENTITY&arg=NAT&engine=ENGINE`

Evaluate stored Trellis IR (`deltanet` or `ceskr`).

## Write

Writes never mutate closed history directly. They append to the open workspace
delta; `commit` validates and seals it as one immutable change.

### `POST /workspace/ops`

Append one or more operations.

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

Each operation may also be:

- a canon-encoded op string from `Delta.encodeOp`
- an object with `"canon": "(op....)"`
- structured objects for `removeEntity`, `addNode`, `bindEntity`, `connect`,
  `disconnect`, `addRoot`, `removeRoot`, and `refineHole`

When `transcript` is omitted, one entry is generated per operation.

### `POST /workspace/commit`

```json
{ "message": "agent-authored change" }
```

### `POST /workspace/publish`

Requires a clean workspace (no open operations).

```json
{
  "package": "trellis/application/default",
  "branch": "workspace",
  "publisher": "trellis-foundation"
}
```

All fields are optional; defaults match the browser shell.

### Legacy browser routes

These remain for the web UI:

- `GET /workspace/edit?entity=...&kind=...&source=...`
- `GET /workspace/commit?message=...`
- `GET /workspace/publish`

Prefer the POST forms for agents.

## Contract

- Reads observe the same overlay as Trellis Squeak.
- Writes use `EntityId` paths, not content or change ids, for semantic targets.
- `connect` / `disconnect` / `bindEntity` use content ids returned by read endpoints.
- Commit runs graph validation before sealing.
- The API is loopback-only and in-memory today; persistence across restarts is not implemented yet.
