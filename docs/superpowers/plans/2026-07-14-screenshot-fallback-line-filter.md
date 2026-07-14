# Screenshot Fallback OCR Line-Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the screenshot-cropping fallback path (Quora, and LinkedIn when its landmark is rejected) from truncating real content. Replace the fixed 20%/20% percentage *image* crop with: crop only to browser chrome (tab pane/toolbar excluded, nothing else), OCR the full remaining width, then filter the OCR engine's own recognized *lines* by position to drop a left-side sidebar column — never slicing pixels/glyphs.

**Architecture:** `get_main_content_rect(hwnd)` starts returning `(crop_box, needs_line_filtering)` instead of just `crop_box`. When a real `main` landmark is found (Facebook today), behavior is unchanged — precise pixel crop, plain OCR. When no usable landmark exists, the crop box becomes doc-rect-only (chrome excluded, everything else kept) and `main()` routes through a new OCR-then-filter path: OCR returns per-line `(left, top, text)` tuples, a pure gap-scan function finds where a left-side sidebar cluster ends (if one exists), and a second pure function drops those lines and sorts survivors into reading order.

**Tech Stack:** Python, `winsdk.windows.media.ocr` (already a dependency) — no new packages.

## Global Constraints

- `MIN_LINES_FOR_SPLIT = 4` — fewer recognized lines than this means "not enough evidence to split," keep everything.
- `MIN_GAP_FOR_SPLIT_PX = 80.0` — minimum gap (in OCR-reported pixels) between consecutive distinct line left-edges to treat as a genuine sidebar boundary, not paragraph-indent noise.
- `find_gap_threshold(lefts)` returns the midpoint of the **first** (leftmost, ascending-order) gap that meets `MIN_GAP_FOR_SPLIT_PX` — **not** the largest gap anywhere in the data. A real captured page has wider gaps between scattered header-bar buttons than the actual sidebar boundary; picking the largest gap globally would wrongly threshold away real content. This is the single most important correctness property in this plan — every task touching this function must preserve it.
- `percentage_fallback_rect`, `CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT` are removed entirely (from both `screenshot-capture.py` and `scripts/test_screenshot_logic.py`, plus their two existing tests) — dead code once the fallback stops doing a percentage image crop. Do not leave them in place "just in case."
- `get_main_content_rect(hwnd) -> tuple[tuple[int, int, int, int] | None, bool]` — the second element (`needs_line_filtering`) is `True` only when the returned crop box is doc-rect-only (no landmark). `False` covers both the landmark-precise case and the "nothing found, `None`" fail-open case.
- `run_ocr(bmp_bytes)` (existing, landmark path) and `_ocr_async` must keep their exact current external behavior — the `_decode_to_bitmap` extraction is a pure refactor, not a logic change.
- Fail-open throughout: any exception anywhere in this pipeline must degrade to "capture without filtering" or "capture without cropping," never raise, matching this script's existing convention.
- `scripts/test_screenshot_logic.py` keeps its own dependency-free inlined copy of pure functions/constants — every pure function added to or removed from production must be mirrored there.
- Baseline: 36/36 tests passing on `scripts/test_screenshot_logic.py` before Task 1.

---

### Task 1: Remove percentage fallback, add gap-scan pure functions

**Files:**
- Modify: `scripts/screenshot-capture.py`
- Modify: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Removes (both files): `percentage_fallback_rect`, `CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT`
- Produces (both files): `MIN_LINES_FOR_SPLIT = 4`, `MIN_GAP_FOR_SPLIT_PX = 80.0`
- Produces (both files): `find_gap_threshold(lefts: list[float]) -> float | None`
- Produces (both files): `filter_and_sort_lines(lines: list[tuple[float, float, str]], threshold: float | None) -> list[tuple[float, float, str]]`
- Consumes: nothing from other tasks (first task)

- [ ] **Step 1: Remove the percentage-fallback code from the test file**

In `scripts/test_screenshot_logic.py`, delete these (they currently sit between the inlined `content_rect_to_crop_box` copy and `test_detect_quora`):

```python
CONTENT_CROP_LEFT_PCT = 0.20
CONTENT_CROP_RIGHT_PCT = 0.20

def percentage_fallback_rect(doc_rect: tuple) -> tuple:
    left, top, right, bottom = doc_rect
    width = right - left
    new_left = left + int(width * CONTENT_CROP_LEFT_PCT)
    new_right = right - int(width * CONTENT_CROP_RIGHT_PCT)
    return (new_left, top, new_right, bottom)
```

Also delete these two test functions (search for them — they're with the other rect-math tests):

```python
def test_percentage_fallback_rect_default():
    assert percentage_fallback_rect((0, 0, 1000, 800)) == (200, 0, 800, 800)

def test_percentage_fallback_rect_nonzero_origin():
    assert percentage_fallback_rect((100, 50, 1100, 850)) == (300, 50, 900, 850)
```

And their two calls in the `if __name__ == "__main__":` block at the bottom of the file (`test_percentage_fallback_rect_default()` and `test_percentage_fallback_rect_nonzero_origin()`).

Do NOT remove `CONTENT_CROP_LEFT_PCT`/`CONTENT_CROP_RIGHT_PCT`'s neighbor `MIN_CROP_WIDTH`/`MIN_CROP_HEIGHT`, or `content_rect_to_crop_box` itself — those stay, still used.

- [ ] **Step 2: Add the new pure functions and tests to the test file**

Add near where `percentage_fallback_rect` used to be (after `content_rect_to_crop_box`, before `test_detect_quora`):

```python
MIN_LINES_FOR_SPLIT = 4
MIN_GAP_FOR_SPLIT_PX = 80.0

def find_gap_threshold(lefts: list) -> float:
    if len(lefts) < MIN_LINES_FOR_SPLIT:
        return None
    distinct = sorted(set(lefts))
    if len(distinct) < 2:
        return None
    for a, b in zip(distinct, distinct[1:]):
        gap = b - a
        if gap >= MIN_GAP_FOR_SPLIT_PX:
            return (a + b) / 2
    return None

def filter_and_sort_lines(lines: list, threshold) -> list:
    kept = [line for line in lines if threshold is None or line[0] >= threshold]
    return sorted(kept, key=lambda line: (line[1], line[0]))
```

Add these test functions, placed with the other rect-math tests:

```python
def test_find_gap_threshold_real_data_picks_first_gap_not_largest():
    lefts = [91.0, 101.0, 106.0, 133.0, 134.0, 281.0, 282.0, 337.0, 345.0, 388.0,
              573.0, 615.0, 627.0, 874.0, 967.0, 1060.0, 1305.0]
    # Real captured Quora line lefts. The sidebar/content boundary is 134->281 (gap 147).
    # Gaps further right are larger (627->874 = 247, 1060->1305 = 245) but must NOT win --
    # the first qualifying gap wins, not the largest anywhere.
    assert find_gap_threshold(lefts) == (134.0 + 281.0) / 2

def test_find_gap_threshold_too_few_lines():
    assert find_gap_threshold([100.0, 200.0, 300.0]) is None

def test_find_gap_threshold_no_qualifying_gap():
    assert find_gap_threshold([100.0, 110.0, 120.0, 130.0, 140.0]) is None

def test_find_gap_threshold_gap_exactly_at_minimum():
    lefts = [100.0, 110.0, 120.0, 130.0, 210.0]
    assert find_gap_threshold(lefts) == (130.0 + 210.0) / 2

def test_find_gap_threshold_early_gap_not_largest_synthetic():
    lefts = [100.0, 110.0, 120.0, 130.0, 220.0, 230.0, 240.0, 250.0, 900.0]
    # First qualifying gap is 130->220 (90px); 250->900 (650px) is larger but comes later.
    assert find_gap_threshold(lefts) == (130.0 + 220.0) / 2

def test_filter_and_sort_lines_no_threshold_keeps_all_sorted():
    lines = [(300.0, 50.0, "second line"), (100.0, 10.0, "first line")]
    assert filter_and_sort_lines(lines, None) == [
        (100.0, 10.0, "first line"),
        (300.0, 50.0, "second line"),
    ]

def test_filter_and_sort_lines_threshold_drops_left_lines():
    lines = [(50.0, 10.0, "sidebar"), (300.0, 10.0, "content")]
    assert filter_and_sort_lines(lines, 200.0) == [(300.0, 10.0, "content")]

def test_filter_and_sort_lines_sorts_out_of_order_input():
    lines = [(300.0, 50.0, "c"), (300.0, 10.0, "a"), (100.0, 30.0, "b")]
    assert filter_and_sort_lines(lines, None) == [
        (300.0, 10.0, "a"),
        (100.0, 30.0, "b"),
        (300.0, 50.0, "c"),
    ]
```

Add all 8 new test calls to the `if __name__ == "__main__":` block, in the same location the removed `percentage_fallback_rect` test calls were.

- [ ] **Step 3: Run the test suite, verify the new tests pass**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `42 passed` (36 baseline − 2 removed + 5 `find_gap_threshold` tests + 3 `filter_and_sort_lines` tests)

- [ ] **Step 4: Apply the same removal + additions to production**

In `scripts/screenshot-capture.py`:

1. Delete `CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT` (constants) and the `percentage_fallback_rect` function entirely. Leave `MIN_CROP_WIDTH`, `MIN_CROP_HEIGHT`, and `content_rect_to_crop_box` in place.

2. Add the constants and both functions from Step 2 (identical bodies; production version may keep full type hints, e.g. `def find_gap_threshold(lefts: list[float]) -> float | None:` and `def filter_and_sort_lines(lines: list[tuple[float, float, str]], threshold: float | None) -> list[tuple[float, float, str]]:`), placed where `percentage_fallback_rect` used to be (after `content_rect_to_crop_box`, before `find_main_landmark`).

- [ ] **Step 5: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 6: Run full test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `42 passed`

- [ ] **Step 7: Commit**

```bash
git add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git commit -m "feat: replace percentage crop with gap-scan line-filter pure functions"
```

---

### Task 2: OCR line extraction and filtered-text composition

**Files:**
- Modify: `scripts/screenshot-capture.py`

**Interfaces:**
- Consumes: `find_gap_threshold`, `filter_and_sort_lines` (Task 1)
- Produces: `_decode_to_bitmap(bmp_bytes)` (async, extracted from existing `_ocr_async`)
- Produces: `_ocr_lines_async(bmp_bytes) -> list[tuple[float, float, str]]` (async)
- Produces: `run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]`
- Produces: `run_ocr_filtered(bmp_bytes: bytes) -> str`
- Modifies (behavior-preserving): `_ocr_async` — now calls `_decode_to_bitmap` instead of inlining the same steps; `run_ocr`'s external behavior is unchanged

These are all impure (UIA/OCR-touching) except none — this task adds no new pure functions, so no test-file changes. Not unit tested, consistent with this file's existing convention for OS-interaction code. Live manual verification is this task's "test" (Steps 3-4).

- [ ] **Step 1: Extract `_decode_to_bitmap` and rewrite `_ocr_async` to use it**

Replace the current `_ocr_async` (which inlines stream/decoder/bitmap setup):

```python
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
```

with:

```python
async def _decode_to_bitmap(bmp_bytes: bytes):
    stream = InMemoryRandomAccessStream()
    writer = DataWriter(stream)
    writer.write_bytes(bmp_bytes)
    await writer.store_async()
    writer.detach_stream()
    stream.seek(0)
    decoder = await BitmapDecoder.create_async(stream)
    # OcrEngine requires Bgra8 / Premultiplied format
    return await decoder.get_software_bitmap_async(
        BitmapPixelFormat.BGRA8, BitmapAlphaMode.PREMULTIPLIED
    )


async def _ocr_async(bmp_bytes: bytes) -> str:
    bitmap = await _decode_to_bitmap(bmp_bytes)
    engine = OcrEngine.try_create_from_user_profile_languages()
    if engine is None:
        return ""
    result = await engine.recognize_async(bitmap)
    return result.text
```

`_ocr_async`'s behavior for any given input is identical before and after this change — same steps, same order, same return value. `run_ocr(bmp_bytes)` (the `asyncio.run(_ocr_async(bmp_bytes))` wrapper) is untouched.

- [ ] **Step 2: Add `_ocr_lines_async`, `run_ocr_lines`, `run_ocr_filtered`**

Add directly after `run_ocr`:

```python
async def _ocr_lines_async(bmp_bytes: bytes) -> list[tuple[float, float, str]]:
    bitmap = await _decode_to_bitmap(bmp_bytes)
    engine = OcrEngine.try_create_from_user_profile_languages()
    if engine is None:
        return []
    result = await engine.recognize_async(bitmap)
    lines = []
    for line in result.lines:
        words = list(line.words)
        if not words:
            continue
        left = min(w.bounding_rect.x for w in words)
        top = min(w.bounding_rect.y for w in words)
        lines.append((left, top, line.text))
    return lines


def run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]:
    return asyncio.run(_ocr_lines_async(bmp_bytes))


def run_ocr_filtered(bmp_bytes: bytes) -> str:
    lines = run_ocr_lines(bmp_bytes)
    threshold = find_gap_threshold([left for left, _, _ in lines])
    kept = filter_and_sort_lines(lines, threshold)
    return "\n".join(text for _, _, text in kept)
```

- [ ] **Step 3: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 4: Manual live verification**

Against a real, currently-open Quora window in the operator's actual browser (not a freshly-launched synthetic window — a fresh window's tab pane is closed/different width and won't reproduce the truncation bug this plan exists to fix; ask the operator which window/hwnd to use if none is obviously already open):

1. Bring the window to the foreground (single Python process invocation, per this project's established foreground-focus finding).
2. Compute `doc_rect` and `window_rect` the same way `get_main_content_rect` does internally (render-host-anchored `Document` control lookup — this task does not yet change `get_main_content_rect` itself, so call the pieces directly): `crop_box = content_rect_to_crop_box(doc_rect, window_rect)`.
3. Capture with `take_screenshot_bmp(hwnd, crop_box)`.
4. Call the real `run_ocr_filtered(bmp_bytes)` and print the result.
5. Confirm: no sidebar space/subscription names appear in the output; no sentence starts mid-word (the specific truncation bug being fixed); the text reads in a sensible top-to-bottom order.
6. Also call the real `run_ocr(bmp_bytes)` (unchanged path) once on the same `bmp_bytes` or on any other capture, to confirm the `_decode_to_bitmap` extraction didn't change its behavior — compare against what `run_ocr` produced before this task's changes if you have a prior capture to diff against, or simply confirm it still returns non-empty, sensible text.

Record what was observed (the filtered text, confirmation the sidebar is gone and no truncation) in the task report.

- [ ] **Step 5: Run full test suite (regression check)**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `42 passed` (this task adds no new unit tests)

- [ ] **Step 6: Clean up and commit**

Delete any throwaway verification scripts/output (do not commit them). Confirm `git status --porcelain -- scripts/` shows only the intended change.

```bash
git add scripts/screenshot-capture.py
git commit -m "feat: add OCR line extraction and gap-filtered text composition"
```

---

### Task 3: Wire line-filtering into `get_main_content_rect` and `main()`

**Files:**
- Modify: `scripts/screenshot-capture.py`

**Interfaces:**
- Consumes: `run_ocr_filtered` (Task 2); `find_main_landmark`, `landmark_too_wide`, `content_rect_to_crop_box` (already shipped)
- Produces: `get_main_content_rect(hwnd: int) -> tuple[tuple[int, int, int, int] | None, bool]` (changed return type from the already-shipped `tuple[int, int, int, int] | None`)

- [ ] **Step 1: Change `get_main_content_rect`'s return type and fallback branch**

Replace the current function body:

```python
def get_main_content_rect(hwnd: int) -> tuple[int, int, int, int] | None:
    try:
        # win32gui.GetWindowRect and UIA's BoundingRectangle are assumed to report the same
        # pixel coordinate space -- true for a single-monitor or uniform-DPI setup (verified
        # live). A mixed-DPI multi-monitor setup could in principle make them diverge and
        # produce a misaligned crop that's still large enough to pass content_rect_to_crop_box's
        # min-size check, rather than failing open to None.
        window_rect = win32gui.GetWindowRect(hwnd)
        window = auto.ControlFromHandle(hwnd)
        # window.DocumentControl() alone matches an empty "WebView" shell element on both
        # Chrome and Edge, not the real content DOM -- confirmed live: it has no landmark
        # children, so find_main_landmark() could never succeed searching it. The real
        # content lives under a sibling pane with the stable Chromium internal class name
        # "Chrome_RenderWidgetHostHWND"; anchor the search there when present. This takes the
        # first match in the subtree, assumed to be the active tab's render host -- a window
        # could in principle contain more than one (out-of-process iframes, prerendered tabs),
        # but a wrong match just yields an empty/mismatched doc, which fails open below.
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

with:

```python
def get_main_content_rect(hwnd: int) -> tuple[tuple[int, int, int, int] | None, bool]:
    try:
        # win32gui.GetWindowRect and UIA's BoundingRectangle are assumed to report the same
        # pixel coordinate space -- true for a single-monitor or uniform-DPI setup (verified
        # live). A mixed-DPI multi-monitor setup could in principle make them diverge and
        # produce a misaligned crop that's still large enough to pass content_rect_to_crop_box's
        # min-size check, rather than failing open to None.
        window_rect = win32gui.GetWindowRect(hwnd)
        window = auto.ControlFromHandle(hwnd)
        # window.DocumentControl() alone matches an empty "WebView" shell element on both
        # Chrome and Edge, not the real content DOM -- confirmed live: it has no landmark
        # children, so find_main_landmark() could never succeed searching it. The real
        # content lives under a sibling pane with the stable Chromium internal class name
        # "Chrome_RenderWidgetHostHWND"; anchor the search there when present. This takes the
        # first match in the subtree, assumed to be the active tab's render host -- a window
        # could in principle contain more than one (out-of-process iframes, prerendered tabs),
        # but a wrong match just yields an empty/mismatched doc, which fails open below.
        render_host = window.PaneControl(ClassName="Chrome_RenderWidgetHostHWND")
        doc = render_host.DocumentControl() if render_host.Exists(0, 0) else window.DocumentControl()
        if not doc.Exists(0, 0):
            return None, False
        r = doc.BoundingRectangle
        doc_rect = (r.left, r.top, r.right, r.bottom)
        landmark_rect = find_main_landmark(doc)
        if landmark_rect is not None and landmark_too_wide(landmark_rect, doc_rect):
            landmark_rect = None
        if landmark_rect is not None:
            return content_rect_to_crop_box(landmark_rect, window_rect), False
        return content_rect_to_crop_box(doc_rect, window_rect), True
    except Exception:
        return None, False
```

- [ ] **Step 2: Update `main()`'s wiring**

Replace:

```python
    crop_box = None
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
    current_hash = hash_bytes(bmp_bytes)

    state = load_state()
    if current_hash == state.get("last_hash"):
        return

    ocr_text = run_ocr(bmp_bytes).strip().replace("\r\n", "\n")
```

with:

```python
    crop_box = None
    needs_line_filtering = False
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box, needs_line_filtering = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
    current_hash = hash_bytes(bmp_bytes)

    state = load_state()
    if current_hash == state.get("last_hash"):
        return

    if needs_line_filtering:
        ocr_text = run_ocr_filtered(bmp_bytes).strip().replace("\r\n", "\n")
    else:
        ocr_text = run_ocr(bmp_bytes).strip().replace("\r\n", "\n")
```

- [ ] **Step 3: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 4: Run full test suite (regression check)**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: `42 passed`

- [ ] **Step 5: End-to-end manual verification**

1. **Quora (fallback path)**: on the operator's real, already-open Quora window (same one used in Task 2, or the operator's current real window — not a freshly-launched one), call the real `main()` with `send_to_digital_me` monkey-patched to capture the text passed to it instead of POSTing. Confirm: no truncated sentences, no sidebar space names, `needs_line_filtering` was `True` for this call.
2. **Facebook (landmark path)**: same approach on a real Facebook window. Confirm the crop is still the precise landmark-derived region (unaffected by this task's changes) and `needs_line_filtering` was `False`.
3. **LinkedIn (fallback-when-landmark-rejected)**: if a real, logged-in LinkedIn window is available, same approach on the feed. Confirm `needs_line_filtering` was `True` (landmark rejected for spanning too much width, per the already-shipped `landmark_too_wide` check) and the filtered text no longer includes the left profile-card sidebar content that the unfiltered fallback previously left in.

Record what was observed for each case in the task report — this is the definitive verification that the original bug report (Quora sentences cut off) is actually fixed, not just that the code compiles.

- [ ] **Step 6: Clean up and commit**

Delete any throwaway verification scripts (do not commit them). Confirm `git status --porcelain -- scripts/` shows only the intended change.

```bash
git add scripts/screenshot-capture.py
git commit -m "feat: use gap-filtered OCR text for the fallback crop path"
```
