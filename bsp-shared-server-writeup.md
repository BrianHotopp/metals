# Eliminating the sbt/Metals BSP Conflict by Sharing the sbt Server

## Problem

When Metals needs BSP (Build Server Protocol) communication with sbt, it spawns a completely new JVM process (`java ... xsbt.boot.Boot -bsp`). This conflicts with any already-running sbt server (sbtn), because sbt enforces single-server-per-project via portfile locking. The result: you can't use Metals and `sbt` in a terminal simultaneously on the same project.

The spawned `sbt -bsp` process is already a thin client -- it connects to the existing sbt server via the portfile socket and bridges stdio to it. The entire JVM startup (~10-15s) exists purely to run this bridge.

## Solution

Two coordinated changes across sbt and Metals:

1. **sbt side**: Make the server safe to share (don't kill it on `build/exit`), advertise the socket in `.bsp/sbt.json`, and prefer the fast `sbtn` native binary as the BSP bridge.

2. **Metals side**: When sbt is the build server, connect directly to the running sbt server's Unix domain socket instead of spawning a process. Falls back to the `sbtn -bsp` argv path if no server is running.

**Result**: BSP connection time drops from ~10 seconds to ~200 milliseconds.

## Implementation

### sbt changes

**Branch**: [`feat/bsp-shared-server`](https://github.com/BrianHotopp/sbt/tree/feat/bsp-shared-server) on `BrianHotopp/sbt`

Two files changed, 80 insertions, 16 deletions.

#### 1. `BuildServerProtocol.scala` -- Fix `build/exit` and add BSP auth

**File**: `main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala`

**`build/exit` handler** (was line ~541): Changed from `TerminateAction` (kills the entire server) to `DisconnectNetworkChannel` (disconnects only the calling BSP client's channel):

```scala
onNotification = {
  case r if r.method == Method.Exit =>
    val _ = callback.appendExec(
      s"${BasicCommandStrings.DisconnectNetworkChannel} ${callback.name}",
      None
    )
},
```

**`build/initialize` handler** (line ~408): Added optional token authentication for direct socket connections. When `ServerAuthentication.Token` is enabled, the handler extracts the token from `InitializeBuildParams.data` and validates it. When token auth is not enabled (the common case), this block is skipped entirely:

```scala
if (callback.authOptions(ServerAuthentication.Token)) {
  val token = params.data.flatMap {
    case JObject(fields) =>
      fields.collectFirst {
        case f if f.field == "token" =>
          f.value match {
            case JString(t) => t
            case _          => ""
          }
      }
    case _ => None
  }.getOrElse(
    throw LangServerError(ErrorCodes.InvalidRequest, "token required for BSP authentication")
  )
  if (!callback.authenticate(token))
    throw LangServerError(ErrorCodes.InvalidRequest, "invalid BSP authentication token")
}
```

#### 2. `BuildServerConnection.scala` -- sbtn argv and portfile advertisement

**File**: `protocol/src/main/scala/sbt/internal/bsp/BuildServerConnection.scala`

**sbtn preference**: Added `findSbtn()` method that searches `PATH` and the sbt launcher's directory for the `sbtn` native binary. If found, `.bsp/sbt.json` uses `["sbtn", "-bsp"]` as the argv instead of the full java command. This alone cuts Metals' BSP connection time from ~10s to ~100ms for the fallback (process-spawn) path.

```scala
private def findSbtn(): Option[String] = {
  val fileName = if (Properties.isWin) "sbtn.exe" else "sbtn"
  val envPath = sys.env.collectFirst {
    case (k, v) if k.toUpperCase() == "PATH" => v
  }
  val allPaths = envPath match
    case Some(path) => path.split(File.pathSeparator).toList.map(Paths.get(_))
    case _          => Nil
  val sbtScriptDir = Option(System.getProperty("sbt.script"))
    .map(Paths.get(_).getParent)
    .toList
  (allPaths ++ sbtScriptDir)
    .map(_.resolve(fileName))
    .find(file => Files.exists(file) && Files.isExecutable(file))
    .map(_.toString)
}
```

**Portfile advertisement**: After serializing `BspConnectionDetails` to JSON, the code enriches it with a `data` field containing the portfile path. This lets BSP clients discover the running server's socket:

```json
{
  "name": "sbt",
  "version": "2.0.0-RC9-bin-SNAPSHOT",
  "bspVersion": "2.1.0-M1",
  "languages": ["scala"],
  "argv": ["/home/user/.local/share/coursier/bin/sbtn", "-bsp"],
  "data": {
    "sbtPortfile": "project/target/active.json"
  }
}
```

The `data` field is added manually to the JSON AST because sbt's contraband-generated `BspConnectionDetails` class has no `data` field (nor does bsp4j's Java equivalent).

### Metals changes

**Branch**: [`feat/bsp-shared-server`](https://github.com/BrianHotopp/metals/tree/feat/bsp-shared-server) on `BrianHotopp/metals`

Three files changed (plus launcher script).

#### 1. `BspServers.scala` -- Direct socket connection

**File**: `metals/src/main/scala/scala/meta/internal/bsp/BspServers.scala`

Added `tryDirectSbtConnection()` method (~90 lines) that:

1. Reads `.bsp/sbt.json` and extracts the `data.sbtPortfile` path using ujson
2. Reads the portfile (`project/target/active.json`) to get the socket URI and optional token file path
3. Reads the authentication token from the token file (if present)
4. Connects to the sbt server via Unix domain socket (`UnixDomainSocket`), Windows named pipe (`Win32NamedPipeSocket`), or TCP socket based on the URI scheme
5. Returns a `SocketConnection` with the socket's I/O streams and the optional token

In `newServer()`, before spawning a process, the code checks for a direct connection:

```scala
if (details.getName() == "sbt") {
  tryDirectSbtConnection(projectDirectory) match {
    case Some(conn) =>
      scribe.info("Connected to sbt server via direct socket connection")
      return Future.successful(conn)
    case None =>
      scribe.info("No running sbt server found, falling back to spawning process")
  }
}
```

The `cancelables` list only closes the socket -- it does NOT kill the server process. This is the key behavioral difference from the process-spawn path.

#### 2. `BuildServerConnection.scala` -- Token passthrough

**File**: `metals/src/main/scala/scala/meta/internal/metals/BuildServerConnection.scala`

- Added `optToken: Option[String] = None` field to `SocketConnection` case class
- Updated `setupServer()` to extract `optToken` from the connection and pass it to `initialize()`
- Updated `initialize()` to inject the token into the `InitializeBuildParams.data` JSON object when present
- Added a JSONRPC listener monitor thread in `setupServer()` that completes `finishedPromise` when the JSONRPC stream ends, enabling automatic reconnection when the sbt server dies:

```scala
optToken.foreach { token =>
  data.getAsJsonObject.addProperty("token", token)
}
```

#### 3. `bin/metals-launcher` -- Per-project version launcher

A shell script that reads `.metals-version` from the project root (detected via `git rev-parse --show-toplevel`) and launches that version of Metals via Coursier with `ivy2local` repository support. Falls back to a default version when no file is present. This makes Metals version management editor-agnostic and project-local, matching the pattern established by sbt's `project/build.properties`.

Usage: drop a `.metals-version` file in any project root with a version string (e.g., `1.6.6-SNAPSHOT`), and point your editor's LSP client at `metals-launcher` instead of `metals`.

## Design Decisions

### Why not modify `BspConnectionDetails` to add a `data` field?

Both sbt's contraband-generated `BspConnectionDetails` and bsp4j's Java `BspConnectionDetails` lack a `data` field. Adding it to sbt's contraband schema would require regenerating code and potentially breaking the BSP spec contract. Adding it to bsp4j would require a PR to the bsp4j project. Instead, we work around it:

- **sbt side**: Manually enrich the JSON AST after serialization with `JField("data", ...)`
- **Metals side**: Re-read `.bsp/sbt.json` with ujson to extract `data.sbtPortfile`, bypassing bsp4j's parser entirely

### Why `DisconnectNetworkChannel` instead of just ignoring `build/exit`?

Simply ignoring `build/exit` would leave stale channels in the server's channel list. `DisconnectNetworkChannel` properly cleans up the channel resources (closes the socket, removes from the exchange) while keeping the server alive.

### Why fall back to `sbtn -bsp` instead of `java ... -bsp`?

The `sbtn` native binary (~50ms startup) does the exact same socket bridge as the full JVM launcher (~10s startup). Even when the direct socket path isn't used, having `.bsp/sbt.json` point to `sbtn` is a massive improvement for any BSP client that spawns via argv.

### Socket disconnect detection and reconnection

The direct socket path has no process to monitor — unlike the process-spawn path where `proc.complete` fires when the BSP bridge process exits. Instead, we monitor the JSONRPC launcher's `listening` future (returned by `launcher.startListening()`). When the socket closes (server shutdown, crash, network error), the JSONRPC reader thread gets an IOException, the `listening` Java Future completes, and our daemon monitoring thread calls `conn.finishedPromise.trySuccess(())`. This triggers Metals' standard reconnection logic (either automatic or with a user prompt, depending on configuration).

For process-spawned servers this monitoring is redundant (`proc.complete` already handles it), but `trySuccess` is idempotent so it's harmless. For direct socket connections, this is the only detection mechanism.

An earlier version tried to monitor disconnection by reading from the socket's input stream in a background thread, but this conflicted with the JSONRPC launcher reading from the same stream.

### BSP spec compliance

This implementation is technically out of spec in two ways:

1. **`build/exit` behavior**: The BSP spec says the server "must" exit after receiving `build/exit`. We changed it to only disconnect the calling channel. The spec was written assuming one client per server -- it never contemplated shared servers. Killing the server on exit is correct when the client owns the server lifecycle, and wrong when it doesn't.

2. **Direct socket connection**: The spec says clients connect by spawning the `argv` process. We bypass that entirely. The `data` field in `.bsp/sbt.json` is our own extension, not part of the spec.

Practically, this doesn't matter:

- The `argv` fallback path (`sbtn -bsp`) is fully spec-compliant, so any standard BSP client still works
- The `data` field is ignored by clients that don't know about it (additive JSON)
- `build/exit` only behaves differently when there are multiple clients -- a scenario the spec doesn't address
- Bloop also takes liberties with the spec in similar ways

**To upstream properly**: The clean path would be proposing a BSP spec extension for server discovery via socket -- either a `connectionDetails.data` convention or a new `uri` field alongside `argv` in `BspConnectionDetails`. This would require PRs to the [BSP spec](https://github.com/build-server-protocol/build-server-protocol), [bsp4j](https://github.com/build-server-protocol/bsp4j), sbt's contraband schema, and Metals. The `build/exit` semantics would also need a spec discussion around shared server lifecycles.

## State Transition Analysis

A complete analysis of every sbt server lifecycle event and what happens to the Metals BSP connection.

### Scenario Matrix

| # | Event | Portfile | Metals Detection | Reconnects? | UX |
|---|-------|----------|------------------|-------------|-----|
| 1 | `shutdown` from terminal | Deleted | Monitor thread | Yes, via `sbtn -bsp` | Good |
| 2 | `exit` from terminal | Deleted | Monitor thread | Yes, via `sbtn -bsp` | Good |
| 3 | `kill -9` / `kill` | **Stale** | Monitor thread | Yes, socket connect fails then `sbtn -bsp` | Good |
| 4 | Server crash (OOM, exception) | Deleted (hooks run) | Monitor thread | Yes, via `sbtn -bsp` | Good |
| 5 | Metals sends `build/exit` | Stays | Monitor thread | No (`isShuttingDown`) | Good |
| 6 | sbt reload | Stays | N/A (no disconnect) | N/A | Good |
| 7 | Server restarted by user | New | N/A | Already reconnected | Good |

### Detailed Walkthrough

**1-2. Graceful shutdown/exit from terminal sbtn**

The `shutdown` command runs `DisconnectNetworkChannel` for the sbtn channel, then `s.exit(true)`. JVM shutdown hooks fire, calling `Server.shutdown()` which deletes the portfile and closes all sockets. Metals' JSONRPC reader gets IOException, `listening` future completes, monitor thread fires `finishedPromise.trySuccess(())`, triggering `reconnect()`. On reconnection, `tryDirectSbtConnection()` finds no portfile and returns `None`. Falls back to `sbtn -bsp`, which starts a new server. Metals reconnects transparently.

**3. Process killed (SIGKILL/SIGTERM)**

JVM killed immediately -- shutdown hooks do NOT run for SIGKILL. Portfile is **stale** (not deleted). OS closes the socket file descriptor. Metals detects disconnection the same way (IOException → monitor thread → `finishedPromise`). On reconnection, `tryDirectSbtConnection()` finds the stale portfile, reads the socket URI, tries `new UnixDomainSocket(path)` -- this **throws** because nobody is listening. Caught by `NonFatal(e)`, returns `None`. Falls back to `sbtn -bsp`, which starts a new server and replaces the stale portfile.

**4. Server crash (OOM, unhandled exception)**

Unlike SIGKILL, JVM shutdown hooks DO run for normal JVM exit. `Server.shutdown()` deletes the portfile. Same recovery path as scenario 1.

**5. Metals sends `build/exit`**

Our modified handler runs `DisconnectNetworkChannel` for Metals' channel only -- server stays alive. `finishedPromise` completes via monitor thread, but `reconnect()` checks `isShuttingDown.get()` and returns the current connection without acting. No spurious reconnection.

**6. sbt reload**

Server stays alive. All channels stay connected. Portfile unchanged. Metals receives `buildTarget/didChange` notifications. No disconnection occurs. (However, if Metals writes `metals.sbt` with an unresolvable SNAPSHOT, the reload crashes the server -- becomes scenario 4.)

**7. Server restarted by user**

By the time the user manually starts a new server, Metals has already reconnected via scenario 1/3/4. The new sbtn and Metals' `sbtn -bsp` bridge both connect to the new server.

### Edge Cases

**Race condition during sbt startup**: If Metals reconnects while a new server is still loading, `build/initialize` may timeout (60s). `fromSockets()` has `retry: Int = 5`, so it retries up to 5 times with exponential backoff.

**Double trigger**: Both `finishedPromise` and `register()` IOException paths can trigger `reconnect()` simultaneously. Protected by `synchronized` + `connection eq original` check -- the second caller sees the connection future has already changed and returns the in-flight future.

**Mid-request server death**: A pending BSP request (e.g., `buildTarget/compile`) gets a `JsonRpcException` wrapping `IOException`. The `register()` method catches this and calls `reconnect()`, then transparently retries the request on the new connection.

**Stale socket file deleted by OS**: If the Unix socket file is cleaned up but the portfile remains, `new UnixDomainSocket(path)` throws (file not found). Caught by `NonFatal(e)`, falls back to process spawn.

## Testing

### Automated protocol test (Python)

A Python script (`/tmp/test_bsp.py`) connects directly to the sbt server's Unix domain socket and exercises the full BSP lifecycle:

1. `build/initialize` -- server responds with BSP capabilities
2. `build/initialized` notification
3. `workspace/buildTargets` -- returns 3 targets (root/Compile, root/Test, root-build)
4. `build/shutdown` + `build/exit` -- server acknowledges and disconnects the channel
5. **Reconnect** -- server is still alive and accepts new connections
6. Second `build/initialize` succeeds

### Automated sbtn -bsp test (Python)

A second script (`/tmp/test_sbtn_bsp.py`) tests the argv path through `sbtn -bsp`:

1. Spawns `sbtn -bsp` as a subprocess
2. Sends `build/initialize`, `workspace/buildTargets`, `buildTarget/compile`
3. Compile succeeds (status: OK)
4. Clean shutdown

### End-to-end VS Code + Metals test

1. Started sbt server via `sbtn` in the test project
2. Opened project in VS Code with Metals `1.6.6-SNAPSHOT`
3. Switched build server from Bloop to sbt
4. Metals logs confirmed: `Connected to sbt server via direct socket connection`
5. Connection time: **0.19 seconds** (vs ~10s for JVM spawn)
6. Full IDE functionality: build targets, compilation, diagnostics all working
7. sbt server remained alive and responsive to terminal `sbtn` commands throughout

## Artifacts

### Repositories

| Repo | Branch | Changes |
|------|--------|---------|
| [BrianHotopp/sbt](https://github.com/BrianHotopp/sbt/tree/feat/bsp-shared-server) | `feat/bsp-shared-server` | `BuildServerProtocol.scala`, `BuildServerConnection.scala` |
| [BrianHotopp/metals](https://github.com/BrianHotopp/metals/tree/feat/bsp-shared-server) | `feat/bsp-shared-server` | `BspServers.scala`, `BuildServerConnection.scala` |

### Local testing setup

- **sbt**: Published locally as `2.0.0-RC9-bin-SNAPSHOT` via `publishLocalBin`
- **Metals**: Published locally as `1.6.6-SNAPSHOT` (metals, mtags, mtags-java, mtags-shared, mtags-interfaces, sbt-metals)
- **Test project**: `/home/brian/Dropbox/2026/bsp-test-project/` with `.vscode/settings.json` pointing to `metals.serverVersion: 1.6.6-SNAPSHOT`

### Required local publishes for dogfooding

```
# sbt (from sbt repo)
sbt publishLocalBin

# Metals (from metals repo)
sbt "metals/publishLocal; mtags/publishLocal; mtags-java/publishLocal; mtagsShared/publishLocal; interfaces/publishLocal; +sbt-metals/publishLocal"
```

## Gotcha: `project/metals.sbt`

When Metals connects to sbt, it writes `project/metals.sbt` which adds the `sbt-metals` plugin at the same SNAPSHOT version. If `sbt-metals` isn't published locally for sbt 2.0 (`+sbt-metals/publishLocal` to cross-build for both sbt 1.x and 2.x), the sbt server will crash on reload trying to resolve the missing plugin. This was the root cause of the initial `build/initialize` timeout during testing -- the server died during reload, not from a protocol issue.
