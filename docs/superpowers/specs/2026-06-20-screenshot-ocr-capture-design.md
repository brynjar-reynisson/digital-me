# Screenshot OCR Capture — Design Spec
Date: 2026-06-20

## Problem

The Chrome extension cannot capture content from LinkedIn, Facebook, and Quora because these sites block extension content scripts via CSP or restricted environments. An alternative capture path is needed that works at the OS level.

## Solution Overview

An always-running background process (PowerShell watchdog → Python worker) takes screenshots every 10 seconds, detects when a browser is showing one of the target sites via window title matching, runs Windows built-in OCR, and sends changed content to Digital Me via the existing `/addContent` REST endpoint.

A Node.js Playwright test in a standalone `e2e/` folder verifies the pipeline end-to-end against `localhost:8080`.

---

## Files and Locations

```
digital-me/
├── scripts/
│   ├── screenshot-capture.ps1      # PowerShell launcher/watchdog
│   └── screenshot-capture.py       # Python OCR + send logic
└── e2e/
    ├── package.json
    ├── playwright.config.ts
    └── tests/
        └── quora-capture.spec.ts
```

- `scripts/` sits at the project root; the user adds `screenshot-capture.ps1` to the Windows Startup folder.
- `e2e/` is fully standalone — its own `package.json`, not connected to `frontend/` or Maven. Tests run manually only via `npx playwright test`.

---

## PowerShell Script (`screenshot-capture.ps1`)

**Responsibility:** Single-instance watchdog that invokes the Python worker on a 10-second loop.

**Behaviour:**
1. On startup, find all other PowerShell processes running a script with the same filename and kill them, ensuring only one instance runs.
2. Enter an infinite loop:
   - Call `python <path>\screenshot-capture.py`
   - If Python exits non-zero, log the error to a local log file and continue (never crash the watchdog)
   - Sleep 10 seconds

**Startup folder placement:** User creates a shortcut to `screenshot-capture.ps1` in `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup`.

---

## Python Script (`screenshot-capture.py`)

**Responsibility:** Single-shot worker — checks, captures, OCRs, compares, sends.

**Dependencies (to be installed via pip):**
- `pywin32` — active foreground window title
- `mss` — lightweight screen capture
- `winrt` — Windows built-in OCR (`Windows.Media.Ocr`)
- `Pillow` — image conversion for the OCR engine
- `requests` — HTTP POST to `/addContent`

**State file:** `scripts/screenshot-capture-state.json`
```json
{
  "last_hash": "<md5 hex>",
  "last_sent_text": "<ocr text of last sent capture>"
}
```

**Execution flow:**

1. **Window title check** — `win32gui.GetForegroundWindow()` + `win32gui.GetWindowText()`. If the title does not contain `"LinkedIn"`, `"Facebook"`, or `"Quora"` (case-insensitive), exit 0 immediately.
2. **Screenshot** — capture full primary screen via `mss`.
3. **Pixel hash gate** — MD5 of raw screenshot bytes. Load state file; if hash matches `last_hash`, exit 0 (no visual change).
4. **OCR** — convert screenshot to a format accepted by `Windows.Media.Ocr.OcrEngine`, run recognition, join all word strings into a single text block.
5. **Text comparison** — if OCR text matches `last_sent_text`, update `last_hash` in state file but do not send.
6. **Send** — POST to `http://localhost:8080/addContent`:
   - `source`: active window title string
   - `name`: `screenshot_<pagename>_<YYYYMMDD_HHMMSS>` where `<pagename>` is `linkedin`, `facebook`, or `quora` (derived from which keyword matched)
   - `content`: full OCR text
7. **State update** — write new `last_hash` and `last_sent_text` to state file.

**Error handling:** Any unhandled exception is printed to stderr and exits non-zero so the PowerShell watchdog can log it.

---

## Change Detection Logic

| Pixel hash same? | Text same? | Action |
|---|---|---|
| Yes | — | Exit, skip OCR |
| No | Yes | Update hash only, no send |
| No | No | Send + update hash + update sent text |

---

## Target Site Detection

Window title keywords (case-insensitive match):

| Keyword | `pagename` |
|---|---|
| `linkedin` | `linkedin` |
| `facebook` | `facebook` |
| `quora` | `quora` |

---

## Playwright Test (`e2e/tests/quora-capture.spec.ts`)

**Responsibility:** End-to-end verification that the screenshot pipeline sends at least two distinct captures to Digital Me while browsing Quora.

**Configuration:**
- `headless: false` — avoids bot detection on Quora
- Realistic user-agent string
- Generous test timeout: 90 seconds
- `baseURL`: `http://localhost:8080`

**Steps:**
1. Navigate to `https://www.quora.com`
2. Find and click the first visible `(more)` link/button
3. Wait 10 seconds (allows one screenshot cycle to fire)
4. Find and click the second `(more)` link — scroll down if needed to bring it into view
5. Wait another 10 seconds (allows a second cycle)
6. GET `http://localhost:8080/search?keywords=quora` and assert at least 2 results with distinct `name` fields (confirming two separate captures were sent)

**Not run automatically** — excluded from CI and Maven. Run manually:
```bash
cd e2e && npx playwright test
```

---

## Dependencies Summary

### Python (pip install)
```
pywin32
mss
winrt
Pillow
requests
```

### Node.js (e2e/package.json)
```
@playwright/test
```

---

## Out of Scope

- Multi-monitor support (captures primary screen only)
- Non-English OCR language packs
- Configurable site list (hardcoded to LinkedIn, Facebook, Quora)
- Running as a Windows Service (Startup folder shortcut is sufficient)
