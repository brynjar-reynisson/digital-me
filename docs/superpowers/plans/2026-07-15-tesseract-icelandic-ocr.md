# Tesseract Icelandic OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Windows OCR (`Windows.Media.Ocr`, which has no Icelandic support) with Tesseract OCR in `scripts/screenshot-capture.py`, so Icelandic text on captured Facebook/Quora/LinkedIn pages is recognized correctly.

**Architecture:** Full engine replacement, not a fallback. `pytesseract` with a fixed `"isl+eng"` combined language model replaces the async WinRT OCR calls. The line-extraction output shape stays identical (`list[tuple[left, top, text]]`), so the existing crop/gap-filter pipeline (`find_gap_threshold`, `filter_and_sort_lines`, `content_rect_to_crop_box`) requires zero changes.

**Tech Stack:** Python, `pytesseract`, Tesseract OCR binary (Windows, via winget), `Pillow`.

## Global Constraints

- Full replacement of Windows OCR — no dual-engine fallback logic.
- Language model is a fixed constant `"isl+eng"` — not configurable per site or per capture.
- `find_gap_threshold`, `filter_and_sort_lines`, `content_rect_to_crop_box`, `main()`, state/dedup logic, and `send_to_digital_me` must not change.
- Tesseract binary installed via `winget install --id UB-Mannheim.TesseractOCR -e`; assume install path `C:\Program Files\Tesseract-OCR\tesseract.exe` unless verification shows otherwise.
- New pure functions must be duplicated (not imported) into `scripts/test_screenshot_logic.py`, matching this file's existing convention (see its header comment — it inlines pure functions rather than importing from the hyphenated `screenshot-capture.py` filename).
- Tesseract's actual OCR output is verified manually against real captures, not via automated tests (matching the `DeepseekSummarizeClientTest` precedent in `docs/testing.md` — subprocess/engine integrations aren't unit-tested in CI).

---

### Task 1: Install Tesseract and the `pytesseract` package

**Files:**
- Modify: `scripts/requirements.txt`

**Interfaces:**
- Produces: a working `tesseract.exe` at `C:\Program Files\Tesseract-OCR\tesseract.exe` with `eng` and `isl` language data, and an importable `pytesseract` package, for all later tasks to call.

- [ ] **Step 1: Install Tesseract via winget**

Run: `winget install --id UB-Mannheim.TesseractOCR -e`
Expected: install completes successfully (exit code 0).

- [ ] **Step 2: Verify the binary and check bundled languages**

Run: `& "C:\Program Files\Tesseract-OCR\tesseract.exe" --list-langs`
Expected: a version banner followed by a list of language codes. Check whether `isl` appears in the list.

- [ ] **Step 3: If `isl` is missing, download the Icelandic trained-data file**

Download `isl.traineddata` from `https://github.com/tesseract-ocr/tessdata/raw/main/isl.traineddata` and save it to `C:\Program Files\Tesseract-OCR\tessdata\isl.traineddata`. Re-run Step 2's command and confirm `isl` now appears.

- [ ] **Step 4: Add `pytesseract` to `scripts/requirements.txt` and remove `winsdk`**

`scripts/requirements.txt` currently reads:
```
pywin32
winsdk
Pillow
requests
uiautomation
```

Change to:
```
pywin32
Pillow
requests
uiautomation
pytesseract
```

- [ ] **Step 5: Install the updated Python dependencies**

Run: `pip install -r scripts/requirements.txt`
Expected: `pytesseract` installs successfully; no errors.

- [ ] **Step 6: Verify pytesseract can see the binary and languages**

Run:
```
python -c "import pytesseract; pytesseract.pytesseract.tesseract_cmd = r'C:\Program Files\Tesseract-OCR\tesseract.exe'; print(pytesseract.get_tesseract_version()); print(pytesseract.get_languages())"
```
Expected: prints a version number and a list of languages including both `eng` and `isl`.

- [ ] **Step 7: Commit**

```bash
git add scripts/requirements.txt
git commit -m "chore: add pytesseract dependency, remove unused winsdk"
```

---

### Task 2: Pure function to group Tesseract word data into lines

**Files:**
- Modify: `scripts/screenshot-capture.py`
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure function, no I/O).
- Produces: `group_words_into_lines(data: dict) -> list[tuple[float, float, str]]` — Task 4 calls this with the dict returned by `pytesseract.image_to_data(..., output_type=Output.DICT)`.

`pytesseract.image_to_data(..., output_type=Output.DICT)` returns a dict of parallel lists — keys include `level`, `page_num`, `block_num`, `par_num`, `line_num`, `word_num`, `left`, `top`, `width`, `height`, `conf`, `text` — with one entry per row (rows exist for page/block/paragraph/line/word levels; non-word rows have empty `text`). This function groups the word-level entries into one `(left, top, text)` tuple per visual line, in reading order, matching the shape `find_gap_threshold`/`filter_and_sort_lines` already consume.

- [ ] **Step 1: Add the test to `scripts/test_screenshot_logic.py`**

Add near the other pure-function tests (this file inlines implementations rather than importing from `screenshot-capture.py` — see its header comment):

```python
def group_words_into_lines(data: dict) -> list:
    groups = {}
    for i, text in enumerate(data["text"]):
        if not text.strip():
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        groups.setdefault(key, []).append((data["left"][i], data["top"][i], text))
    lines = []
    for words in groups.values():
        left = min(w[0] for w in words)
        top = min(w[1] for w in words)
        text = " ".join(w[2] for w in words)
        lines.append((float(left), float(top), text))
    return lines

def test_group_words_into_lines_joins_words_on_same_line():
    data = {
        "block_num": [1, 1, 1],
        "par_num": [1, 1, 1],
        "line_num": [1, 1, 1],
        "left": [10, 60, 110],
        "top": [5, 6, 5],
        "text": ["Hello", "there", "world"],
    }
    assert group_words_into_lines(data) == [(10.0, 5.0, "Hello there world")]

def test_group_words_into_lines_splits_separate_lines():
    data = {
        "block_num": [1, 1],
        "par_num": [1, 1],
        "line_num": [1, 2],
        "left": [10, 15],
        "top": [5, 40],
        "text": ["First", "Second"],
    }
    assert group_words_into_lines(data) == [(10.0, 5.0, "First"), (15.0, 40.0, "Second")]

def test_group_words_into_lines_skips_empty_and_whitespace_text():
    data = {
        "block_num": [1, 1, 1],
        "par_num": [1, 1, 1],
        "line_num": [1, 1, 1],
        "left": [10, 999, 60],
        "top": [5, 999, 6],
        "text": ["Hello", "", "there"],
    }
    assert group_words_into_lines(data) == [(10.0, 5.0, "Hello there")]

def test_group_words_into_lines_preserves_first_seen_order():
    data = {
        "block_num": [2, 1],
        "par_num": [1, 1],
        "line_num": [1, 1],
        "left": [300, 10],
        "top": [50, 5],
        "text": ["Second", "First"],
    }
    assert group_words_into_lines(data) == [(300.0, 50.0, "Second"), (10.0, 5.0, "First")]

def test_group_words_into_lines_empty_input_returns_empty_list():
    data = {"block_num": [], "par_num": [], "line_num": [], "left": [], "top": [], "text": []}
    assert group_words_into_lines(data) == []
```

- [ ] **Step 2: Run the new tests to verify they pass against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k group_words_into_lines -v`
Expected: all 5 new tests PASS (this inlined copy is the reference implementation, so this confirms the test cases themselves are correct before porting).

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Add this function near `filter_and_sort_lines` (after the `MIN_LINES_FOR_SPLIT`/`MIN_GAP_FOR_SPLIT_PX`/`filter_and_sort_lines` block, before `landmark_too_wide`):

```python
def group_words_into_lines(data: dict) -> list[tuple[float, float, str]]:
    groups: dict[tuple[int, int, int], list[tuple[int, int, str]]] = {}
    for i, text in enumerate(data["text"]):
        if not text.strip():
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        groups.setdefault(key, []).append((data["left"][i], data["top"][i], text))
    lines = []
    for words in groups.values():
        left = min(w[0] for w in words)
        top = min(w[1] for w in words)
        text = " ".join(w[2] for w in words)
        lines.append((float(left), float(top), text))
    return lines
```

- [ ] **Step 4: Run the full pure-function test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all tests pass (42 existing + 5 new = 47).

- [ ] **Step 5: Commit**

```bash
git add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git commit -m "feat: add group_words_into_lines for Tesseract line extraction"
```

---

### Task 3: Pure image preprocessing function

**Files:**
- Modify: `scripts/screenshot-capture.py`
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `preprocess_for_ocr(image: Image.Image) -> Image.Image` — Task 4 calls this before every `pytesseract` call.

Tesseract is tuned for ~300 DPI document scans; screen-captured UI text is much smaller, so grayscale + 2x upscaling measurably improves recognition.

- [ ] **Step 1: Add the test to `scripts/test_screenshot_logic.py`**

Add near the top (needs `from PIL import Image`, alongside the existing `import hashlib` etc. — add `from PIL import Image` to this test file's imports):

```python
def preprocess_for_ocr(image):
    grayscale = image.convert("L")
    return grayscale.resize((grayscale.width * 2, grayscale.height * 2))

def test_preprocess_for_ocr_converts_to_grayscale_and_upscales():
    original = Image.new("RGB", (10, 20), color=(255, 0, 0))
    result = preprocess_for_ocr(original)
    assert result.mode == "L"
    assert result.size == (20, 40)
```

- [ ] **Step 2: Run the test to verify it passes against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k preprocess_for_ocr -v`
Expected: PASS.

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Add this function directly above the current `run_ocr`/`run_ocr_lines`/`run_ocr_filtered` block:

```python
def preprocess_for_ocr(image: Image.Image) -> Image.Image:
    grayscale = image.convert("L")
    return grayscale.resize((grayscale.width * 2, grayscale.height * 2))
```

- [ ] **Step 4: Run the full pure-function test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 48 tests pass (47 from Task 2 + 1 new).

- [ ] **Step 5: Commit**

```bash
git add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git commit -m "feat: add preprocess_for_ocr grayscale/upscale helper"
```

---

### Task 4: Replace Windows OCR with Tesseract in `screenshot-capture.py`

**Files:**
- Modify: `scripts/screenshot-capture.py`

**Interfaces:**
- Consumes: `group_words_into_lines(data: dict) -> list[tuple[float, float, str]]` (Task 2), `preprocess_for_ocr(image: Image.Image) -> Image.Image` (Task 3).
- Produces: `run_ocr(bmp_bytes: bytes) -> str` and `run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]` with unchanged signatures — `run_ocr_filtered()` (already defined, calls `run_ocr_lines`) needs no changes.

- [ ] **Step 1: Remove the Windows OCR imports**

In `scripts/screenshot-capture.py`, remove these lines from the top of the file:

```python
import asyncio
```
and
```python
from winsdk.windows.graphics.imaging import (
    BitmapAlphaMode,
    BitmapDecoder,
    BitmapPixelFormat,
)
from winsdk.windows.media.ocr import OcrEngine
from winsdk.windows.storage.streams import DataWriter, InMemoryRandomAccessStream
```

Add in their place:
```python
import pytesseract
from pytesseract import Output
```

- [ ] **Step 2: Add the Tesseract configuration constant and binary path**

Add near the top of the file, alongside the other constants (e.g. near `PW_RENDERFULLCONTENT`):

```python
TESSERACT_LANG = "isl+eng"
pytesseract.pytesseract.tesseract_cmd = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
```

- [ ] **Step 3: Remove the old async OCR functions**

Delete `_decode_to_bitmap()` and `_ocr_async()` and `_ocr_lines_async()` entirely (the `run_ocr`/`run_ocr_lines`/`run_ocr_filtered` functions that call them are replaced in the next step, not deleted).

- [ ] **Step 4: Replace `run_ocr` and `run_ocr_lines` with Tesseract-based implementations**

Replace:
```python
def run_ocr(bmp_bytes: bytes) -> str:
    return asyncio.run(_ocr_async(bmp_bytes))
```
and
```python
def run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]:
    return asyncio.run(_ocr_lines_async(bmp_bytes))
```

with:
```python
def run_ocr(bmp_bytes: bytes) -> str:
    image = preprocess_for_ocr(Image.open(io.BytesIO(bmp_bytes)))
    return pytesseract.image_to_string(image, lang=TESSERACT_LANG)


def run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]:
    image = preprocess_for_ocr(Image.open(io.BytesIO(bmp_bytes)))
    data = pytesseract.image_to_data(image, lang=TESSERACT_LANG, output_type=Output.DICT)
    return group_words_into_lines(data)
```

(`run_ocr_filtered()` immediately below already calls `run_ocr_lines()` and needs no edits.)

- [ ] **Step 5: Run the full pure-function test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 48 tests still pass (this file doesn't import `screenshot-capture.py`, so this just confirms Tasks 2–3 didn't regress).

- [ ] **Step 6: Sanity-check the module imports and runs standalone**

Run: `python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'scripts/screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('OK')"`
Expected: prints `OK` with no import errors.

- [ ] **Step 7: Manual end-to-end verification — English page (regression check)**

Use the `browser-screenshot-ocr` skill (or run its steps manually) against `https://www.linkedin.com/feed/` in `msedge`. After it completes, read `scripts/screenshot-capture-state.json`'s `last_sent_text` field and confirm it contains coherent, correctly-spelled English text with no dropped leading characters.

- [ ] **Step 8: Manual end-to-end verification — Icelandic page (the actual fix)**

Use the `browser-screenshot-ocr` skill against a Facebook or LinkedIn page currently showing Icelandic text (e.g. the user's own feed, which was the source of the original garbled capture referenced in the design spec). After it completes, read `scripts/screenshot-capture-state.json`'s `last_sent_text` field and confirm Icelandic characters (þ, ð, æ, ö, accented vowels) are now recognized correctly rather than substituted with similar Latin characters.

- [ ] **Step 9: Commit**

```bash
git add scripts/screenshot-capture.py
git commit -m "feat: replace Windows OCR with Tesseract for Icelandic support"
```

---

### Task 5: Document the OCR pipeline in `docs/architecture.md`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: nothing consumed by other tasks — this is the doc-update step CLAUDE.md requires before finishing a feature branch.

`docs/architecture.md` currently has no section on `scripts/screenshot-capture.py` at all (it documents the Java/Spring Boot backend only). Add one.

- [ ] **Step 1: Add a new section to `docs/architecture.md`**

Add this new section at the end of the file:

```markdown
---

## Screenshot OCR capture (`scripts/`)

`scripts/screenshot-capture.py` watches the active foreground browser window (Chrome/Edge/Firefox/Opera/Brave) and, when it's a recognized site (LinkedIn, Quora, Facebook — see `SITE_KEYWORDS`), captures and OCRs its content into digital-me via `/addContent`.

- **Capture:** `take_screenshot_bmp()` uses the Win32 `PrintWindow` API (`PW_RENDERFULLCONTENT`) to render directly from the target window's own surface, rather than copying on-screen pixels — this makes it immune to other windows visually overlapping the target at capture time.
- **Crop:** for Quora/LinkedIn/Facebook, `get_main_content_rect()` uses UI Automation to find the page's main content landmark (falling back to the whole document if none is found) and crops to it, via `content_rect_to_crop_box()`.
- **OCR:** `run_ocr()` / `run_ocr_lines()` use Tesseract (`pytesseract`) with a fixed `"isl+eng"` combined language model, after a grayscale + 2x upscale preprocessing step (`preprocess_for_ocr()`) tuned for small screen-rendered UI text. Requires Tesseract installed via `winget install --id UB-Mannheim.TesseractOCR -e` with both `eng.traineddata` and `isl.traineddata` present in its `tessdata` folder.
- **Line filtering:** when no landmark crop was found (`needs_line_filtering`), `run_ocr_filtered()` calls `run_ocr_lines()` to get per-line `(left, top, text)` positions, then `find_gap_threshold()` + `filter_and_sort_lines()` drop sidebar/nav text by finding the horizontal gap between the sidebar and main content columns.
- **Dedup:** `screenshot-capture-state.json` tracks the last screenshot's hash and OCR'd text; unchanged captures are skipped rather than re-sent.
- **Watcher loop:** `screenshot-capture.ps1` re-runs the script every 3 seconds, restarting itself if killed.
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: document the screenshot OCR capture pipeline"
```

---

### Task 6: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 48 tests pass.

- [ ] **Step 2: Confirm no leftover references to the removed Windows OCR code**

Run: `grep -rn "winsdk\|asyncio\|OcrEngine" scripts/screenshot-capture.py`
Expected: no output (no matches).

- [ ] **Step 3: Review the full diff against `main`**

Run: `git diff main...feature/tesseract-icelandic-ocr --stat`
Expected: shows changes to `scripts/screenshot-capture.py`, `scripts/requirements.txt`, `scripts/test_screenshot_logic.py`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Hand off for merge decision**

Use the `superpowers:finishing-a-development-branch` skill to decide how to integrate the branch (merge, PR, or further cleanup) — don't merge or push unilaterally.
