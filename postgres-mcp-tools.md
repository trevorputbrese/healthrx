# MCP Tool Reference

This server exposes PostgreSQL read tools through Spring AI's MCP server support.
The tool callbacks are registered in `McpServerConfig` by passing the
`PostgresService` bean to `MethodToolCallbackProvider`; every public method in
that service annotated with `@Tool` becomes an MCP tool.

The tools operate against the single Spring `DataSource` configured for the
application, currently via `spring.datasource.*` in
`src/main/resources/application.yml`. They do not accept database host,
database, username, or password arguments per tool call.

All four tools are annotated with `returnDirect = true`, so Spring AI returns
the tool result directly to the MCP caller.

## Tool Names

The `@Tool` annotations in this code do not set explicit names. Spring AI
therefore uses the Java method names as tool names:

| Tool | Purpose |
| --- | --- |
| `listSchemas` | List non-system schemas in the configured database. |
| `listTables` | List base tables and views in a schema. |
| `executeQuery` | Execute a read-only SQL statement and return result rows. |
| `select` | Build and execute a `SELECT *` query from structured arguments. |

Some MCP clients may display or alias tool names differently, but these are the
names derived from the current server code.

## `listSchemas`

Lists database schemas visible to the configured connection, excluding the
standard PostgreSQL schemas `information_schema`, `pg_catalog`, and `pg_toast`.

Source method:
`PostgresService.listSchemas()`

Arguments: none.

Returns: an ordered JSON array of schema names.

Example call:

```json
{}
```

Example result:

```json
[
  "inventory",
  "public",
  "sales"
]
```

Implementation notes:

- Queries `information_schema.schemata`.
- Sorts by `schema_name`.
- Wraps `SQLException` failures as runtime errors with the message
  `Failed to list schemas: ...`.

## `listTables`

Lists base tables and views for a schema.

Source method:
`PostgresService.listTables(String schema)`

Arguments:

| Argument | Type | Required by schema | Behavior |
| --- | --- | --- | --- |
| `schema` | string | Yes | Schema to inspect. If Java receives `null`, the service defaults to `public`. |

Returns: an ordered JSON array of objects.

Each object contains:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | string | Table or view name. |
| `type` | string | PostgreSQL information schema table type, currently `BASE TABLE` or `VIEW`. |
| `comment` | string or null | Table comment from `obj_description`, when available. |

Example call:

```json
{
  "schema": "public"
}
```

Example result:

```json
[
  {
    "name": "products",
    "type": "BASE TABLE",
    "comment": "Product catalog with pricing information"
  },
  {
    "name": "users",
    "type": "BASE TABLE",
    "comment": "User accounts and profile information"
  }
]
```

Implementation notes:

- Queries `information_schema.tables`.
- Includes only `BASE TABLE` and `VIEW` rows.
- Sorts by `table_name`.
- Looks up table comments through `pg_class`/`obj_description`.

## `executeQuery`

Executes a SQL statement and returns the rows produced by JDBC
`PreparedStatement.executeQuery()`.

Source method:
`PostgresService.executeQuery(String query)`

Arguments:

| Argument | Type | Required | Behavior |
| --- | --- | --- | --- |
| `query` | string | Yes | SQL text to execute. |

Returns: a JSON array of row objects. Each row maps column names from JDBC
metadata to their returned values.

Example call:

```json
{
  "query": "SELECT id, username, email FROM users ORDER BY id LIMIT 10"
}
```

Example result:

```json
[
  {
    "id": 1,
    "username": "john_doe",
    "email": "john@example.com"
  }
]
```

Read-only validation:

Before execution, the service trims and lowercases the SQL string and allows it
only when it starts with one of these prefixes:

- `select`
- `with`
- `show`
- `explain`
- `describe`
- `\d`

If the query starts with any other prefix, the service throws
`Only SELECT queries are allowed`.

Implementation notes:

- The SQL text is passed directly to `Connection.prepareStatement(query)`.
- There is no parameter binding API for user-supplied values.
- The method does not set a statement timeout or maximum row count.
- Allowed prefixes such as `describe` and `\d` are accepted by the validator,
  but may still fail through JDBC/PostgreSQL if they are not valid SQL in that
  context.

## `select`

Builds a `SELECT *` query from structured arguments, then delegates to
`executeQuery`.

Source method:
`PostgresService.select(SelectRequest request)`

Top-level arguments:

| Argument | Type | Required | Behavior |
| --- | --- | --- | --- |
| `request` | object | Yes | Structured select request. |

`request` fields:

| Field | Type | Required by model | Behavior |
| --- | --- | --- | --- |
| `table` | string | Yes | Table name. Must not be null or blank. The implementation double-quotes and escapes this value. |
| `schema` | string | No | Schema name. Defaults to `public` in the record constructor when omitted or null. |
| `conditions` | string | Yes by generated schema | Optional SQL expression appended after `WHERE` when nonblank. |
| `orderBy` | string | Yes by generated schema | Optional SQL expression appended after `ORDER BY` when nonblank. |
| `limit` | integer | Yes by generated schema | Optional row limit. When present, must be positive. |

Even though the generated JSON schema treats unannotated record fields as
required by default, the service code handles `conditions`, `orderBy`, and
`limit` as optional when they arrive as null.

Example call:

```json
{
  "request": {
    "table": "users",
    "schema": "public",
    "conditions": "id > 1",
    "orderBy": "username ASC",
    "limit": 10
  }
}
```

Generated SQL:

```sql
SELECT * FROM public."users" WHERE id > 1 ORDER BY username ASC LIMIT 10
```

Example result:

```json
[
  {
    "id": 2,
    "username": "jane_smith",
    "email": "jane@example.com",
    "created_at": "2026-07-01T12:00:00"
  }
]
```

Implementation notes:

- The table name is quoted with double quotes and embedded double quotes are
  escaped.
- The schema value is inserted before the quoted table name and is not quoted.
- `conditions` and `orderBy` are appended directly to the generated SQL.
- The generated SQL is still passed through `executeQuery`, so it must pass the
  same read-only prefix validation.

## Security And Operational Behavior

The server is intended for read-oriented PostgreSQL access, but the current
guardrails are lightweight:

- Read-only enforcement is prefix-based, not a full SQL parser.
- The JDBC connection is not explicitly marked read-only.
- `conditions`, `orderBy`, `schema`, and raw `query` text are not parameterized.
- There is no built-in result-size cap unless callers use `limit` or include
  `LIMIT` in SQL.
- There is no statement timeout configured in the tool methods.

For production use, run the server with a PostgreSQL role that has only the
minimum read privileges required, and prefer database-level protections over
tool-level validation alone.

## Sample Database Objects

The repository's `docker-compose.yml` starts a local PostgreSQL 16 container and
loads `init.sql`. That sample script creates:

| Schema | Objects |
| --- | --- |
| `public` | `users`, `products` |
| `sales` | `orders`, `order_items` |
| `inventory` | `stock` |

These objects are useful for exercising the four tools locally.
