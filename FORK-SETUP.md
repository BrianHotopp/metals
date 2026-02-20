# Fork Setup Guide

## The problem

sbt enforces one server per project, but every tool that needs BSP (Metals, IntelliJ, sbtn) tries to talk to that server in its own way. The result: sbtn hangs waiting for a lock, IntelliJ fails to BSP import, Metals times out connecting, IDE compiles and terminal compiles stomp on each other's incremental compilation state. General fuckery.

## The fix

Make all tools share one sbt server properly. Two coordinated changes across sbt and Metals:

- **sbt**: Don't kill the server when a BSP client disconnects (`build/exit` disconnects only that client's channel). Advertise the server's socket in `.bsp/sbt.json` so clients can connect directly instead of spawning a JVM.
- **Metals**: Connect to the running sbt server's Unix domain socket (~200ms) instead of spawning `sbt -bsp` (~10s). Fall back to `sbtn -bsp` if no server is running. Auto-reconnect when the server dies.

Now sbtn in the terminal, Metals in Emacs, and any other BSP client all talk to the same sbt server. One Zinc state, one compilation pipeline, no conflicts.

## Repos

| Repo | Branch |
|------|--------|
| [BrianHotopp/sbt](https://github.com/BrianHotopp/sbt/tree/feat/bsp-shared-server) | `feat/bsp-shared-server` |
| [BrianHotopp/metals](https://github.com/BrianHotopp/metals/tree/feat/bsp-shared-server) | `feat/bsp-shared-server` |

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

### 5. Configure Emacs

Add to `~/.emacs.d/init.el`:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(scala-mode . ("metals-launcher"))))
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

See `bsp-shared-server-writeup.md` for the complete implementation details, design decisions, state transition analysis, and BSP spec compliance notes.
