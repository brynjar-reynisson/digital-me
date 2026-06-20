# Screenshot OCR Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an always-running PowerShell + Python background process that screenshots the active browser window every 10 seconds, runs Windows OCR when LinkedIn/Facebook/Quora is detected in the window title, and POSTs changed content to Digital Me at `localhost:8080/addContent`; verified by a standalone Node.js Playwright e2e test.

**Architecture:** A PowerShell watchdog (`screenshot-capture.ps1`) enforces single-instance execution via `Win32_Process` inspection and loops every 10 seconds calling the Python worker. The Python worker (`screenshot-capture.py`) checks the active foreground window title, takes a full-screen BMP via `mss`, MD5-hashes the raw bytes to skip unchanged frames, runs Windows built-in OCR via the `winsdk` package, compares OCR text with the last-sent text, and POSTs to `/addContent` only when content changes. A JSON state file persists hash and last-sent text between 10-second invocations.

**Tech Stack:** Python 3.13, pywin32, mss, winsdk, Pillow, requests; PowerShell 5.1; Node.js, @playwright/test 1.49+

## Global Constraints

- Digital Me backend must be running at `localhost:8080` for all send/verify steps
- `python` must be on PATH (the watchdog calls `python screenshot-capture.py`)
- Windows 10/11 required — `pywin32`, `mss`, and `winsdk` are Windows-only
- Playwright tests run **manually only** — never part of `mvn test` or any CI pipeline
- State file location: `scripts/screenshot-capture-state.json` (gitignored)
- Log file location: `scripts/screenshot-capture.log` (gitignored)
- Source `name` format: `screenshot_<pagename>_<YYYYMMDD_HHMMSS>`
- `source` field sent to `/addContent` = the raw window title string
- `/addContent` does NOT strip content for non-http sources (window title doesn't start with `http`)

---

### Task 1: Python dependencies

**Files:**
- Create: `scripts/requirements.txt`

**Interfaces:**
- Produces: installable Python environment required by Task 2

- [ ] **Step 1: Create requirements.txt**

Create `scripts/requirements.txt`:
```
pywin32
mss
winsdk
Pillow
requests
```

> **Note:** `winsdk` is the Microsoft-maintained Windows Runtime Python binding (PyPI: `winsdk`). If `pip install winsdk` fails on Python 3.13, try `pip install winsdk --pre` for a pre-release build. The package imports as `from winsdk.windows.media.ocr import OcrEngine`.

- [ ] **Step 2: Install dependencies**

Run:
```
pip install -r scripts/requirements.txt
```

Expected: all five packages install without error. If `winsdk` fails, install `winrt` instead (`pip install winrt`) and update imports in Task 2 from `winsdk.windows.*` to `winrt.windows.*`.

- [ ] **Step 3: Commit**

```bash
git add scripts/requirements.txt
git commit -m "feat: add Python requirements for screenshot OCR capture"
```

---

### Task 2: Python worker

**Files:**
- Create: `scripts/screenshot-capture.py`
- Create: `scripts/test_screenshot_logic.py`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `http://localhost:8080/addContent` — POST JSON `{"source": str, "name": str, "content": str}`
- Produces: `scripts/screenshot-capture-state.json` at runtime (gitignored)
- Produces: `detect_site(title: str) -> tuple[str | None, str]` — pure function used in tests
- Produces: `hash_bytes(data: bytes) -> str` — pure function used in tests

- [ ] **Step 1: Write the logic tests**

Create `scripts/test_screenshot_logic.py`:
```python
import hashlib
import json
import tempfile
from pathlib import Path

# Inline the two pure functions so this file has no external imports
SITE_KEYWORDS = {"linkedin": "linkedin", "facebook": "facebook", "quora": "quora"}

def detect_site(window_title: str) -> tuple:
    lower = window_title.lower()
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, window_title
    return None, window_title

def hash_bytes(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()

def test_detect_quora():
    pagename, title = detect_site("Quora - A place to share knowledge - Google Chrome")
    assert pagename == "quora", f"expected quora, got {pagename}"
    assert "Quora" in title

def test_detect_linkedin():
    pagename, _ = detect_site("Feed | LinkedIn - Google Chrome")
    assert pagename == "linkedin"

def test_detect_facebook():
    pagename, _ = detect_site("Facebook - Google Chrome")
    assert pagename == "facebook"

def test_detect_no_match():
    pagename, _ = detect_site("GitHub - Microsoft Edge")
    assert pagename is None

def test_detect_case_insensitive():
    pagename, _ = detect_site("QUORA - SOME TITLE")
    assert pagename == "quora"

def test_hash_deterministic():
    assert hash_bytes(b"hello") == hash_bytes(b"hello")

def test_hash_distinct():
    assert hash_bytes(b"hello") != hash_bytes(b"world")

def test_state_roundtrip():
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        path = Path(f.name)
    state = {"last_hash": "abc123", "last_sent_text": "some ocr text"}
    path.write_text(json.dumps(state), encoding="utf-8")
    loaded = json.loads(path.read_text(encoding="utf-8"))
    assert loaded == state
    path.unlink()

def test_load_state_missing_file():
    path = Path(tempfile.mktemp(suffix=".json"))
    assert not path.exists()
    state = {"last_hash": None, "last_sent_text": None}
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
    assert state == {"last_hash": None, "last_sent_text": None}

if __name__ == "__main__":
    test_detect_quora()
    test_detect_linkedin()
    test_detect_facebook()
    test_detect_no_match()
    test_detect_case_insensitive()
    test_hash_deterministic()
    test_hash_distinct()
    test_state_roundtrip()
    test_load_state_missing_file()
    print("All tests passed.")
```

- [ ] **Step 2: Run the tests**

```
python scripts/test_screenshot_logic.py
```

Expected output:
```
All tests passed.
```

- [ ] **Step 3: Write the Python worker**

Create `scripts/screenshot-capture.py`:
```python
import asyncio
import datetime
import hashlib
import io
import json
import sys
from pathlib import Path

import mss
import requests
import win32gui
from PIL import Image
from winsdk.windows.graphics.imaging import (
    BitmapAlphaMode,
    BitmapDecoder,
    BitmapPixelFormat,
)
from winsdk.windows.media.ocr import OcrEngine
from winsdk.windows.storage.streams import DataWriter, InMemoryRandomAccessStream

STATE_FILE = Path(__file__).parent / "screenshot-capture-state.json"
DIGITAL_ME_URL = "http://localhost:8080/addContent"
SITE_KEYWORDS = {"linkedin": "linkedin", "facebook": "facebook", "quora": "quora"}


def detect_site(window_title: str) -> tuple[str | None, str]:
    lower = window_title.lower()
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, window_title
    return None, window_title


def get_active_window_title() -> str:
    return win32gui.GetWindowText(win32gui.GetForegroundWindow())


def take_screenshot_bmp() -> bytes:
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        pil = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
        buf = io.BytesIO()
        pil.save(buf, format="BMP")
        return buf.getvalue()


def hash_bytes(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"last_hash": None, "last_sent_text": None}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state), encoding="utf-8")


async def _ocr_async(bmp_bytes: bytes) -> str:
    stream = InMemoryRandomAccessStream()
    writer = DataWriter(stream)
    writer.write_bytes(bmp_bytes)
    await writer.store_async()
    writer.detach_stream()
    stream.seek(0)
    decoder = await BitmapDecoder.create_async(stream)
    # OcrEngine requires Bgra8 / Premultiplied format
    bitmap = await decoder.get_software_bitmap_async(
        BitmapPixelFormat.BGRA8, BitmapAlphaMode.PREMULTIPLIED
    )
    engine = OcrEngine.try_create_from_user_profile_languages()
    if engine is None:
        return ""
    result = await engine.recognize_async(bitmap)
    return result.text


def run_ocr(bmp_bytes: bytes) -> str:
    return asyncio.run(_ocr_async(bmp_bytes))


def send_to_digital_me(source: str, name: str, content: str) -> None:
    requests.post(
        DIGITAL_ME_URL,
        json={"source": source, "name": name, "content": content},
        timeout=10,
    )


def main() -> None:
    title = get_active_window_title()
    pagename, window_title = detect_site(title)
    if pagename is None:
        return

    bmp_bytes = take_screenshot_bmp()
    current_hash = hash_bytes(bmp_bytes)

    state = load_state()
    if current_hash == state.get("last_hash"):
        return

    ocr_text = run_ocr(bmp_bytes)

    if ocr_text == state.get("last_sent_text"):
        state["last_hash"] = current_hash
        save_state(state)
        return

    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    entry_name = f"screenshot_{pagename}_{timestamp}"
    send_to_digital_me(window_title, entry_name, ocr_text)

    state["last_hash"] = current_hash
    state["last_sent_text"] = ocr_text
    save_state(state)


if __name__ == "__main__":
    main()
```

> **Troubleshooting `BitmapPixelFormat.BGRA8`:** If this raises `AttributeError`, try `BitmapPixelFormat.Bgra8` (camelCase). The exact attribute name depends on the installed `winsdk` version.

- [ ] **Step 4: Extend .gitignore**

Open `.gitignore` and append:
```
scripts/screenshot-capture-state.json
scripts/screenshot-capture.log
e2e/node_modules/
```

- [ ] **Step 5: Manual smoke test**

Prerequisites: Digital Me running at `localhost:8080`, Chrome open to `https://www.quora.com` as the active window.

```
python scripts/screenshot-capture.py
```

Expected:
- No errors printed
- `scripts/screenshot-capture-state.json` created with `last_hash` and `last_sent_text` set
- `GET http://localhost:8080/search?keywords=Quora` returns a result whose `name` starts with `screenshot_quora_`

Run again immediately (without moving the browser):
- Expected: silent exit, state file unchanged (same hash)

Scroll the Quora page slightly, run again:
- Expected: a second POST sent, state file updated with new hash and text

- [ ] **Step 6: Commit**

```bash
git add scripts/screenshot-capture.py scripts/test_screenshot_logic.py .gitignore
git commit -m "feat: add Python OCR screenshot worker"
```

---

### Task 3: PowerShell watchdog

**Files:**
- Create: `scripts/screenshot-capture.ps1`

**Interfaces:**
- Consumes: `scripts/screenshot-capture.py` (resolved relative to the `.ps1` file's own directory)
- Produces: `scripts/screenshot-capture.log` for non-zero Python exits

- [ ] **Step 1: Create the PowerShell script**

Create `scripts/screenshot-capture.ps1`:
```powershell
$scriptName = [System.IO.Path]::GetFileName($MyInvocation.MyCommand.Path)
$currentPid = $PID

# Kill any other PowerShell process running this same script name
Get-CimInstance Win32_Process -Filter "name = 'powershell.exe'" |
    Where-Object { $_.ProcessId -ne $currentPid -and $_.CommandLine -like "*$scriptName*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$scriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyScript   = Join-Path $scriptDir "screenshot-capture.py"
$logFile    = Join-Path $scriptDir "screenshot-capture.log"

while ($true) {
    try {
        $output = & python $pyScript 2>&1
        if ($LASTEXITCODE -ne 0) {
            $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
            "$ts [ERROR exit=$LASTEXITCODE] $output" | Out-File -FilePath $logFile -Append -Encoding utf8
        }
    } catch {
        $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        "$ts [EXCEPTION] $_" | Out-File -FilePath $logFile -Append -Encoding utf8
    }
    Start-Sleep -Seconds 10
}
```

- [ ] **Step 2: Manual single-instance test**

1. Open a PowerShell terminal and run:
   ```
   powershell -File C:\<full-path>\scripts\screenshot-capture.ps1
   ```
2. Open a second PowerShell terminal and run the same command.
3. Expected: the second instance kills the first and becomes the sole running instance.
4. Verify with `Get-CimInstance Win32_Process -Filter "name='powershell.exe'"` — only one entry should show `screenshot-capture.ps1` in its command line.

- [ ] **Step 3: Commit**

```bash
git add scripts/screenshot-capture.ps1
git commit -m "feat: add PowerShell watchdog for screenshot capture"
```

---

### Task 4: Playwright e2e scaffolding

**Files:**
- Create: `e2e/package.json`
- Create: `e2e/playwright.config.ts`

**Interfaces:**
- Produces: `npx playwright test` command runnable from the `e2e/` directory

- [ ] **Step 1: Create package.json**

Create `e2e/package.json`:
```json
{
  "name": "digital-me-e2e",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "test": "playwright test"
  },
  "devDependencies": {
    "@playwright/test": "^1.49.0"
  }
}
```

- [ ] **Step 2: Create playwright.config.ts**

Create `e2e/playwright.config.ts`:
```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  use: {
    headless: false,
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
```

- [ ] **Step 3: Install dependencies**

```
cd e2e
npm install
npx playwright install chromium
```

Expected: `node_modules/` populated, Chromium browser binary downloaded.

- [ ] **Step 4: Commit**

```bash
git add e2e/package.json e2e/playwright.config.ts
git commit -m "feat: scaffold Playwright e2e test setup"
```

---

### Task 5: Playwright e2e test

**Files:**
- Create: `e2e/tests/quora-capture.spec.ts`

**Interfaces:**
- Consumes: `screenshot-capture.ps1` must already be running before this test starts (it's a separate background process; the test does not start it)
- Consumes: Digital Me at `http://localhost:8080`
- Consumes: `GET /search?keywords=Quora` → `{ results: Array<{ source: string; name: string }> }`

**Prerequisites before running:**
1. `screenshot-capture.ps1` is running in a PowerShell window
2. Digital Me backend is running at `localhost:8080`
3. The Playwright-controlled Chromium window will become the active foreground window while the test runs, so the screenshot script will detect it

- [ ] **Step 1: Create the test**

Create `e2e/tests/quora-capture.spec.ts`:
```typescript
import { test, expect } from '@playwright/test';

test('screenshot capture sends two distinct captures while browsing Quora', async ({ page }) => {
  // Snapshot the names already in Digital Me so we only count new captures from this run
  const beforeRes = await page.request.get('http://localhost:8080/search?keywords=Quora');
  const beforeData = await beforeRes.json() as { results: { source: string; name: string }[] };
  const existingNames = new Set(
    beforeData.results
      .filter(r => r.name.startsWith('screenshot_quora_'))
      .map(r => r.name)
  );

  await page.goto('https://www.quora.com', { waitUntil: 'domcontentloaded' });

  // Click the first visible (more) link
  const moreLinks = page.getByText('(more)');
  await moreLinks.first().waitFor({ state: 'visible', timeout: 20_000 });
  await moreLinks.first().click();

  // Wait 10 s — one full screenshot cycle fires here
  await page.waitForTimeout(10_000);

  // Click the second (more) link; scroll into view if needed
  const allMore = page.getByText('(more)');
  const count = await allMore.count();
  if (count >= 2) {
    await allMore.nth(1).scrollIntoViewIfNeeded();
    await allMore.nth(1).click();
  } else {
    await page.evaluate(() => window.scrollBy(0, 800));
    await page.waitForTimeout(1_000);
    await page.getByText('(more)').first().scrollIntoViewIfNeeded();
    await page.getByText('(more)').first().click();
  }

  // Wait another 10 s — second screenshot cycle fires here
  await page.waitForTimeout(10_000);

  // Assert at least two new distinct captures were sent during this test run
  const afterRes = await page.request.get('http://localhost:8080/search?keywords=Quora');
  const afterData = await afterRes.json() as { results: { source: string; name: string }[] };
  const newNames = afterData.results
    .filter(r => r.name.startsWith('screenshot_quora_') && !existingNames.has(r.name))
    .map(r => r.name);

  expect(
    newNames.length,
    `Expected ≥2 new screenshot_quora_ captures, got: ${JSON.stringify(newNames)}`
  ).toBeGreaterThanOrEqual(2);
});
```

- [ ] **Step 2: Run the test**

Prerequisites: `screenshot-capture.ps1` running, Digital Me running at `localhost:8080`.

```
cd e2e
npx playwright test
```

Expected: test passes; Chromium opens Quora, clicks two `(more)` links, and Digital Me search returns at least 2 new `screenshot_quora_*` entries.

If the test fails because no captures were sent, check `scripts/screenshot-capture.log` for Python errors — the most likely cause is a `winsdk` import error (see Task 1 troubleshooting note).

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/quora-capture.spec.ts
git commit -m "feat: add Playwright e2e test for screenshot OCR capture"
```
