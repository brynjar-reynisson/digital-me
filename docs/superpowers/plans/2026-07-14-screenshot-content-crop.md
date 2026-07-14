# Screenshot Main-Content Crop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crop `scripts/screenshot-capture.py`'s screenshots to the page's main-content column (excluding the browser's tab pane and the page's own left/right sidebar columns) before OCR, for Quora, LinkedIn, and Facebook on Chrome/Edge.

**Architecture:** Two-layer crop computed via Windows UI Automation: (1) the window's `Document`-type control's bounding rect excludes browser chrome (tab pane, toolbar) regardless of the user-resizable tab pane's current width; (2) within that, a `main` ARIA landmark's bounding rect (if found) or a fixed-percentage fallback excludes the page's own side columns. Pure rect-math functions are unit tested; UIA tree-walking functions are manually verified against live browser windows, matching this script's existing testing convention.

**Tech Stack:** Python, `uiautomation` (UIA), `pywin32` (`win32gui`), `mss`, `Pillow` — all already dependencies of this script.

## Global Constraints

- All rects in this feature are `(left, top, right, bottom)` tuples in absolute screen pixel coordinates, matching `win32gui.GetWindowRect()`'s convention.
- `CONTENT_CROP_LEFT_PCT = 0.20`, `CONTENT_CROP_RIGHT_PCT = 0.20` — fraction of the `Document` control's width trimmed from each side in the percentage-fallback path.
- `MIN_CROP_WIDTH = 100`, `MIN_CROP_HEIGHT = 100` — below these, a computed crop box is discarded (treated as `None`) rather than used.
- `CROP_CONTENT_SITES = {"quora", "linkedin", "facebook"}`.
- `UIA_LANDMARK_TYPE_PROPERTY_ID = 30157`, `UIA_MAIN_LANDMARK_TYPE_ID = 80002` — standard Win32 UIA constants (`UIAutomationClient.h`), used via `Control.GetPropertyValue()`. Confirmed against the installed `uiautomation` package and Microsoft's "Landmark Type Identifiers" documentation (corrected during Task 2's review — the design spec's original values, 30154/80003, were wrong: 30154 is `UIA_LevelPropertyId` and 80003 is `UIA_NavigationLandmarkTypeId`).
- `MAX_LANDMARK_SEARCH_NODES = 500`, `MAX_LANDMARK_SEARCH_DEPTH = 20` — bound the landmark search's worst-case latency.
- `MAX_LANDMARK_WIDTH_FRACTION = 0.80` — a found landmark is rejected (treated as not found, falling through to the percentage crop) if its width exceeds 80% of the document's width. Added after live testing found LinkedIn's `main` landmark spans its entire three-column layout rather than just the feed column.
- Rename existing `SUBPAGE_CAPABLE_BROWSERS` → `UIA_CAPABLE_BROWSERS` (value unchanged: `{"chrome", "edge"}`); this feature reuses it rather than introducing a second Chrome/Edge-only constant.
- Fail-open, no exceptions: any UIA failure at any stage returns `None` ("no crop, no known content region") rather than raising — the caller then captures the full window exactly as it does today. This mirrors the existing `get_address_bar_url()` pattern.
- `take_screenshot_bmp(hwnd, crop_box=None)` — the default `None` must preserve today's exact full-window behavior for every site not in `CROP_CONTENT_SITES` (Firefox/Opera/Brave, and any other unmatched site or browser).
- `scripts/test_screenshot_logic.py` keeps its own dependency-free inlined copy of pure functions/constants (no imports from `screenshot-capture.py`) — every pure function added to production must be mirrored there.
- Baseline: 26/26 tests passing on `scripts/test_screenshot_logic.py` before Task 1.

---

### Task 1: Rename constant + pure crop-math functions

**Files:**
- Modify: `scripts/screenshot-capture.py`
- Modify: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Produces (both files): `percentage_fallback_rect(doc_rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]`
- Produces (both files): `content_rect_to_crop_box(content_rect: tuple[int, int, int, int], window_rect: tuple[int, int, int, int]) -> tuple[int, int, int, int] | None`
- Produces (`screenshot-capture.py` only): constants `CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT`, `MIN_CROP_WIDTH`, `MIN_CROP_HEIGHT`; renamed `UIA_CAPABLE_BROWSERS` (was `SUBPAGE_CAPABLE_BROWSERS`)
- Consumes: nothing from other tasks (this is the first task)

- [ ] **Step 1: Write the failing tests**

Add to `scripts/test_screenshot_logic.py`, after the existing `is_subpage_exempt` inlined copy (around line 35) and before `def test_detect_quora():`:

```python
CONTENT_CROP_LEFT_PCT = 0.20
CONTENT_CROP_RIGHT_PCT = 0.20
MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100

def percentage_fallback_rect(doc_rect: tuple) -> tuple:
    left, top, right, bottom = doc_rect
    width = right - left
    new_left = left + int(width * CONTENT_CROP_LEFT_PCT)
    new_right = right - int(width * CONTENT_CROP_RIGHT_PCT)
    return (new_left, top, new_right, bottom)

def content_rect_to_crop_box(content_rect: tuple, window_rect: tuple):
    c_left, c_top, c_right, c_bottom = content_rect
    w_left, w_top, w_right, w_bottom = window_rect
    box_left = max(c_left - w_left, 0)
    box_top = max(c_top - w_top, 0)
    box_right = min(c_right - w_left, w_right - w_left)
    box_bottom = min(c_bottom - w_top, w_bottom - w_top)
    if box_right - box_left < MIN_CROP_WIDTH or box_bottom - box_top < MIN_CROP_HEIGHT:
        return None
    return (box_left, box_top, box_right, box_bottom)
```

Then add these test functions, placed with the other tests (e.g. after `test_is_subpage_exempt_neither_site`, before `test_state_roundtrip`):

```python
def test_percentage_fallback_rect_default():
    assert percentage_fallback_rect((0, 0, 1000, 800)) == (200, 0, 800, 800)

def test_percentage_fallback_rect_nonzero_origin():
    assert percentage_fallback_rect((100, 50, 1100, 850)) == (300, 50, 900, 850)

def test_content_rect_to_crop_box_inside_window():
    content_rect = (150, 100, 850, 700)
    window_rect = (100, 50, 1000, 800)
    assert content_rect_to_crop_box(content_rect, window_rect) == (50, 50, 750, 650)

def test_content_rect_to_crop_box_clamped():
    content_rect = (50, 30, 1050, 820)
    window_rect = (100, 50, 1000, 800)
    assert content_rect_to_crop_box(content_rect, window_rect) == (0, 0, 900, 750)

def test_content_rect_to_crop_box_degenerate_returns_none():
    content_rect = (100, 50, 150, 800)
    window_rect = (100, 50, 1000, 800)
    assert content_rect_to_crop_box(content_rect, window_rect) is None
```

This step writes the pure functions *and* their tests together (both are trivial, pinned-down math from the spec) — there is no meaningful "implementation not yet written" red state to observe beyond a typo check, so proceed straight to Step 2 to confirm the file is at least syntactically consistent before moving on.

- [ ] **Step 2: Run the new tests, verify they pass**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all tests pass, including the 5 new ones (31 total: 26 existing + 5 new).

- [ ] **Step 3: Apply the same rename + constants + functions to production**

In `scripts/screenshot-capture.py`:

1. Rename `SUBPAGE_CAPABLE_BROWSERS` to `UIA_CAPABLE_BROWSERS` at its definition (currently line 28: `SUBPAGE_CAPABLE_BROWSERS = {"chrome", "edge"}`) and at its one usage inside `main()` (currently line 139: `if pagename in SUBPAGE_GATED_SITES and browser in SUBPAGE_CAPABLE_BROWSERS:`). Do not rename `SUBPAGE_GATED_SITES` — only the browser-capability constant.

2. Add the following constants near the existing `SUBPAGE_CAPABLE_BROWSERS`/`SUBPAGE_GATED_SITES` block:

```python
CONTENT_CROP_LEFT_PCT = 0.20
CONTENT_CROP_RIGHT_PCT = 0.20
MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100
```

3. Add the same two pure functions used in Step 1 (`percentage_fallback_rect`, `content_rect_to_crop_box`) as module-level functions, placed after `has_subpath()` and before `LINKEDIN_FEED_PATTERN = re.compile(...)`.

- [ ] **Step 4: Smoke-test the production file still imports**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 5: Run full test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `31 passed`

- [ ] **Step 6: Commit**

```bash
git add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git commit -m "feat: add pure crop-math functions, rename UIA_CAPABLE_BROWSERS"
```

---

### Task 2: `find_main_landmark` and `get_main_content_rect`

**Files:**
- Modify: `scripts/screenshot-capture.py`
- Modify: `scripts/test_screenshot_logic.py` (for `landmark_too_wide` only — the impure functions are not mirrored here)

**Interfaces:**
- Consumes: `percentage_fallback_rect`, `content_rect_to_crop_box` (Task 1); existing `win32gui` and `auto` (`uiautomation`) imports; existing `UIA_CAPABLE_BROWSERS` constant (Task 1, informational — not referenced by these functions directly, only by `main()` in Task 3)
- Produces: `find_main_landmark(doc_control) -> tuple[int, int, int, int] | None`
- Produces: `landmark_too_wide(landmark_rect: tuple[int, int, int, int], doc_rect: tuple[int, int, int, int]) -> bool` (pure, unit tested — see Tests)
- Produces: `get_main_content_rect(hwnd: int) -> tuple[int, int, int, int] | None`

`find_main_landmark` and `get_main_content_rect` are impure (UIA tree-walking) and are not unit tested, consistent with `get_address_bar_url()` — see `docs/testing.md`'s note that subprocess/OS-interaction correctness is verified manually, not in CI. This task's "test" is live manual verification against real browser windows (Steps 3-4). `landmark_too_wide` is pure and must be mirrored into `scripts/test_screenshot_logic.py` with tests, per this project's inlined-copy convention (Task 1's precedent).

- [ ] **Step 1: Add the constants**

In `scripts/screenshot-capture.py`, add near the other new constants from Task 1:

```python
UIA_LANDMARK_TYPE_PROPERTY_ID = 30157  # UIA_LandmarkTypePropertyId
UIA_MAIN_LANDMARK_TYPE_ID = 80002      # UIA_MainLandmarkTypeId
MAX_LANDMARK_SEARCH_NODES = 500
MAX_LANDMARK_SEARCH_DEPTH = 20
```

- [ ] **Step 2: Add `find_main_landmark` and `get_main_content_rect`**

Add after `content_rect_to_crop_box()` (from Task 1):

```python
def find_main_landmark(doc_control) -> tuple[int, int, int, int] | None:
    queue = [(doc_control, 0)]
    visited = 0
    while queue:
        control, depth = queue.pop(0)
        visited += 1
        if visited > MAX_LANDMARK_SEARCH_NODES:
            return None
        try:
            landmark_type = control.GetPropertyValue(UIA_LANDMARK_TYPE_PROPERTY_ID)
        except Exception:
            landmark_type = None
        if landmark_type == UIA_MAIN_LANDMARK_TYPE_ID:
            try:
                rect = control.BoundingRectangle
                return (rect.left, rect.top, rect.right, rect.bottom)
            except Exception:
                return None
        if depth < MAX_LANDMARK_SEARCH_DEPTH:
            try:
                children = control.GetChildren()
            except Exception:
                children = []
            for child in children:
                queue.append((child, depth + 1))
    return None


MAX_LANDMARK_WIDTH_FRACTION = 0.80


def landmark_too_wide(landmark_rect: tuple[int, int, int, int], doc_rect: tuple[int, int, int, int]) -> bool:
    landmark_width = landmark_rect[2] - landmark_rect[0]
    doc_width = doc_rect[2] - doc_rect[0]
    if doc_width <= 0:
        return False
    return (landmark_width / doc_width) > MAX_LANDMARK_WIDTH_FRACTION


def get_main_content_rect(hwnd: int) -> tuple[int, int, int, int] | None:
    try:
        window_rect = win32gui.GetWindowRect(hwnd)
        window = auto.ControlFromHandle(hwnd)
        # window.DocumentControl() alone matches an empty "WebView" shell element on both
        # Chrome and Edge, not the real content DOM -- confirmed live: it has no landmark
        # children, so find_main_landmark() could never succeed searching it. The real
        # content lives under a sibling pane with the stable Chromium internal class name
        # "Chrome_RenderWidgetHostHWND"; anchor the search there when present.
        render_host = window.PaneControl(ClassName="Chrome_RenderWidgetHostHWND")
        doc = render_host.DocumentControl() if render_host.Exists(0, 0) else window.DocumentControl()
        if not doc.Exists(0, 0):
            return None
        r = doc.BoundingRectangle
        doc_rect = (r.left, r.top, r.right, r.bottom)
        landmark_rect = find_main_landmark(doc)
        if landmark_rect is not None and landmark_too_wide(landmark_rect, doc_rect):
            landmark_rect = None
        content_rect = landmark_rect or percentage_fallback_rect(doc_rect)
        return content_rect_to_crop_box(content_rect, window_rect)
    except Exception:
        return None
```

`landmark_too_wide` is pure and unit-tested (see the Tests section). Added after live verification found LinkedIn's `main` landmark spans its entire three-column layout (~96% of document width) rather than just the feed column, which would otherwise produce a worse crop than the percentage fallback.

- [ ] **Step 2b: Mirror `landmark_too_wide` into the test file, with tests**

Add to `scripts/test_screenshot_logic.py`, after the inlined `content_rect_to_crop_box` copy and before `def test_detect_quora():`:

```python
MAX_LANDMARK_WIDTH_FRACTION = 0.80

def landmark_too_wide(landmark_rect: tuple, doc_rect: tuple) -> bool:
    landmark_width = landmark_rect[2] - landmark_rect[0]
    doc_width = doc_rect[2] - doc_rect[0]
    if doc_width <= 0:
        return False
    return (landmark_width / doc_width) > MAX_LANDMARK_WIDTH_FRACTION
```

Add these tests, placed with the other rect-math tests (e.g. after `test_content_rect_to_crop_box_degenerate_returns_none`, before `test_state_roundtrip`):

```python
def test_landmark_too_wide_true():
    doc_rect = (0, 0, 1000, 800)
    landmark_rect = (0, 0, 900, 800)
    assert landmark_too_wide(landmark_rect, doc_rect) is True

def test_landmark_too_wide_false():
    doc_rect = (0, 0, 1000, 800)
    landmark_rect = (200, 0, 800, 800)
    assert landmark_too_wide(landmark_rect, doc_rect) is False

def test_landmark_too_wide_exact_threshold_not_too_wide():
    doc_rect = (0, 0, 1000, 800)
    landmark_rect = (0, 0, 800, 800)
    assert landmark_too_wide(landmark_rect, doc_rect) is False

def test_landmark_too_wide_zero_doc_width():
    doc_rect = (500, 0, 500, 800)
    landmark_rect = (500, 0, 500, 800)
    assert landmark_too_wide(landmark_rect, doc_rect) is False
```

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `35 passed` (31 from Task 1 + 4 new)

- [ ] **Step 3: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 4: Manual live verification against real browser windows**

For each of Quora, LinkedIn, and Facebook, in both Chrome and Edge (6 combinations; reuse whichever live-testing approach Task 3/4 of the prior `screenshot-subpage-skip` feature used — explicit `hwnd` targeting, since window titles can be ambiguous substrings of each other):

1. Launch a fresh window on the site's real content page (not the bare root) — e.g. a Quora question page, a LinkedIn feed or profile page, a Facebook post/group page — via `Start-Process chrome -ArgumentList "--new-window", "<url>"` / `Start-Process msedge -ArgumentList "--new-window", "<url>"`.
2. Bring the window to the foreground and get its `hwnd` (do this in a single Python process invocation, per the prior feature's finding that separate invocations lose foreground focus).
3. Call the real `get_main_content_rect(hwnd)` and the real `win32gui.GetWindowRect(hwnd)`; print both.
4. Sanity-check the printed crop box is plausible: its left edge should be well to the right of the window's left edge (excluding the vertical tab pane), and its width should be meaningfully narrower than the full window width (excluding at least one side column). If a crop box came back `None`, investigate whether that's expected (e.g. `Document` control genuinely not found) or a bug.
5. To directly *see* what will be OCR'd (not just trust the numbers), write a small throwaway script (do not commit it) that does `mss.mss().grab(window_region)` → `PIL.Image.frombytes(...)` → `.crop(crop_box)` → `.save("preview.png")`, then view `preview.png`. Confirm visually that the tab pane and at least one side column are excluded, and the main feed/article text is intact.
6. Close extra windows opened for testing.

Record what was found for each of the 6 combinations (landmark found vs. percentage fallback used, and whether the crop looked correct) — this becomes part of the task report, the same way the prior feature's Task 3/4 reports documented live-verification evidence.

- [ ] **Step 5: Run full test suite (regression check)**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `35 passed` (31 from Task 1 + the 4 `landmark_too_wide` tests from Step 2b)

- [ ] **Step 6: Clean up and commit**

Delete any throwaway verification script/PNG created in Step 4 (do not commit them — consistent with how the prior feature's `verify_harness.py` was deleted before its final commit). Confirm `git status --porcelain -- scripts/` shows only the intended change.

```bash
git add scripts/screenshot-capture.py
git commit -m "feat: add UIA main-landmark search and main-content rect lookup"
```

---

### Task 3: Wire cropping into `take_screenshot_bmp` and `main()`

**Files:**
- Modify: `scripts/screenshot-capture.py`

**Interfaces:**
- Consumes: `get_main_content_rect` (Task 2), `UIA_CAPABLE_BROWSERS` (Task 1)
- Produces: `take_screenshot_bmp(hwnd: int, crop_box: tuple[int, int, int, int] | None = None) -> bytes` (extended signature; `crop_box=None` default preserves prior behavior for every existing caller)
- Produces: new constant `CROP_CONTENT_SITES = {"quora", "linkedin", "facebook"}`

- [ ] **Step 1: Add `CROP_CONTENT_SITES`**

In `scripts/screenshot-capture.py`, add near `SUBPAGE_GATED_SITES`:

```python
CROP_CONTENT_SITES = {"quora", "linkedin", "facebook"}
```

- [ ] **Step 2: Extend `take_screenshot_bmp` to accept an optional crop box**

Replace the current `take_screenshot_bmp` (lines 59-67):

```python
def take_screenshot_bmp(hwnd: int) -> bytes:
    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    region = {"left": left, "top": top, "width": right - left, "height": bottom - top}
    with mss.mss() as sct:
        img = sct.grab(region)
        pil = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
        buf = io.BytesIO()
        pil.save(buf, format="BMP")
        return buf.getvalue()
```

with:

```python
def take_screenshot_bmp(hwnd: int, crop_box: tuple[int, int, int, int] | None = None) -> bytes:
    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    region = {"left": left, "top": top, "width": right - left, "height": bottom - top}
    with mss.mss() as sct:
        img = sct.grab(region)
        pil = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
        if crop_box is not None:
            pil = pil.crop(crop_box)
        buf = io.BytesIO()
        pil.save(buf, format="BMP")
        return buf.getvalue()
```

- [ ] **Step 3: Wire crop-box computation into `main()`**

In `main()`, replace the current line:

```python
    bmp_bytes = take_screenshot_bmp(hwnd)
```

with:

```python
    crop_box = None
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
```

This must stay after the existing sub-page gating `if` block (which may `return` early) and before the `current_hash = hash_bytes(bmp_bytes)` line — i.e. in the same position the old `bmp_bytes = take_screenshot_bmp(hwnd)` line occupied.

- [ ] **Step 4: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 5: Run full test suite (regression check)**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `35 passed`

- [ ] **Step 6: End-to-end manual verification**

For at least one Quora, one LinkedIn, and one Facebook window (real content pages, Chrome or Edge):

1. Bring the window to the foreground.
2. Call the real `main()` with `send_to_digital_me` monkey-patched to observe rather than actually POST (mirroring the prior feature's Task 4 verification approach), and with `run_ocr` left real (or also monkey-patched to just return the cropped image's OCR text) so you can inspect the resulting text.
3. Confirm the OCR'd text reads as the page's main content — not tab-pane labels or obvious sidebar/suggestion-widget text.
4. Separately, confirm a non-cropped case is unaffected: bring a non-Quora/LinkedIn/Facebook site (e.g. GitHub) to the foreground in Chrome, call `main()` (monkey-patched the same way), and confirm the OCR'd text still covers the full window as before (no cropping applied, since `pagename not in CROP_CONTENT_SITES`).
5. Close extra windows opened for testing; delete any throwaway scripts (do not commit them).

Record what was observed for each case in the task report.

- [ ] **Step 7: Commit**

```bash
git add scripts/screenshot-capture.py
git commit -m "feat: crop screenshots to main content for Quora, LinkedIn, Facebook"
```
