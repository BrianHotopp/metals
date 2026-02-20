# Fork Setup Guide

This fork adds direct socket BSP connections to a shared sbt server, eliminating the 10-second JVM spawn and compile state conflicts between Metals and terminal sbt.

## What this fork changes

### sbt fork ([BrianHotopp/sbt](https://github.com/BrianHotopp/sbt/tree/feat/bsp-shared-server))

- `build/exit` disconnects the BSP client channel instead of killing the server
- `.bsp/sbt.json` prefers `sbtn` native binary as the BSP bridge
- `.bsp/sbt.json` advertises the server's portfile path for direct socket connections
- BSP `build/initialize` supports token authentication for direct socket clients

### Metals fork ([BrianHotopp/metals](https://github.com/BrianHotopp/metals/tree/feat/bsp-shared-server))

- Connects directly to a running sbt server's Unix domain socket (~200ms vs ~10s)
- Falls back to `sbtn -bsp` process spawn if no server is running
- Auto-reconnects when the sbt server dies (via JSONRPC listener monitoring)
- Includes `bin/metals-launcher` for per-project Metals version pinning

## Setup on a new machine

### 1. Prerequisites

- JDK 17+
- Coursier (`cs`) on PATH
- sbt launcher (`cs install sbt`)
- sbtn (`cs install sbtn` or included with sbt)

### 2. Build and publish sbt fork

```bash
git clone -b feat/bsp-shared-server git@github.com:BrianHotopp/sbt.git ~/src/sbt-fork
cd ~/src/sbt-fork
sbt publishLocalBin
```

### 3. Build and publish Metals fork

```bash
git clone -b feat/bsp-shared-server git@github.com:BrianHotopp/metals.git ~/src/metals-fork
cd ~/src/metals-fork
sbt "metals/publishLocal; mtags/publishLocal; mtags-java/publishLocal; mtagsShared/publishLocal; interfaces/publishLocal; +sbt-metals/publishLocal"
```

### 4. Install the launcher

```bash
cp ~/src/metals-fork/bin/metals-launcher ~/.local/bin/metals-launcher
chmod +x ~/.local/bin/metals-launcher
```

Ensure `~/.local/bin` is on your PATH.

### 5. Configure your editor

#### Emacs (Eglot)

Add to `~/.emacs.d/init.el`:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(scala-mode . ("metals-launcher"))))
```

#### VS Code

In `.vscode/settings.json` per workspace:

```json
{
  "metals.serverVersion": "1.6.6-SNAPSHOT"
}
```

### 6. Configure each project

Set the sbt version in `project/build.properties`:

```
sbt.version=2.0.0-RC9-bin-SNAPSHOT
```

Create `.metals-version` in the project root:

```
1.6.6-SNAPSHOT
```

### 7. Usage

```bash
cd your-project
sbtn              # starts sbt server (writes portfile)
emacs .            # Metals connects via direct socket in ~200ms
```

Both Metals and `sbtn` in the terminal share the same sbt server. Compiles from either side use the same Zinc state -- no conflicts, no invalidation.

## Updating the fork

After pulling new changes from either fork:

```bash
# sbt
cd ~/src/sbt-fork && git pull && sbt publishLocalBin

# Metals
cd ~/src/metals-fork && git pull && sbt "metals/publishLocal; mtags/publishLocal; mtags-java/publishLocal; mtagsShared/publishLocal; interfaces/publishLocal; +sbt-metals/publishLocal"
```

## Full technical writeup

See `bsp-shared-server-writeup.md` in the repo root (if present) or the detailed commit messages in this branch's git log.
