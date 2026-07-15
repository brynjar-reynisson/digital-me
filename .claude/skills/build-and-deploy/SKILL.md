---
name: build-and-deploy
description: Use when the user asks to build and deploy digital-me, rebuild and restart it, or redeploy after code changes — stops the running app, packages it with Maven, and starts it back up.
allowed-tools: Bash(npx kill-port *) Bash(powershell.exe *)
---

# Build and Deploy (digital-me)

## Steps

1. **Stop** — same as `stop.cmd`:
   ```
   npx kill-port 5174 8080
   ```
2. **Build** — full backend + frontend package (per `docs/tooling.md`, `mvn` is not on PATH, use the IntelliJ-bundled Maven):
   ```bash
   cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" package
   ```
   Run from the project root (`C:\Users\Lenovo\IdeaProjects\digital-me`). This runs `frontend-maven-plugin` (npm install + build) and produces `target/digital-me-0.1.jar`.
3. **Start** — same as `start.cmd`:
   ```
   powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Users\Lenovo\restart-digital-me.ps1"
   ```
   This script itself kills ports 5174/8080 again, then starts the jar (`--data.dir=C:\Users\Lenovo\DigitalMe`), the frontend dev server (`npm run dev`), and the `screenshot-capture.ps1` watcher loop, all as hidden background processes.

## Notes

- If `mvn package` fails, stop and report the build error — do not proceed to start the app with a stale/missing jar.
- Steps 1 and 3 both kill the ports; that's intentional redundancy carried over from `stop.cmd`/`start.cmd`, not a bug to "fix."
- After starting, give it a few seconds before checking `http://localhost:8080` (backend) or `http://localhost:5174` (frontend dev server) — both take a moment to boot.
