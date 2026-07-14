# Screenshot Fallback OCR Line-Filter — Design Spec
Date: 2026-07-14

## Problem

`scripts/screenshot-capture.py`'s `get_main_content_rect()` falls back to `percentage_fallback_rect()` (a fixed 20%/20% pixel trim) whenever no usable `main` ARIA landmark is found. Live testing in a real, everyday browser window (vertical tab pane open, narrower content area than a fresh full-width test window) found this fixed percentage cuts into real content: on Quora, the actual left sidebar is narrower than 20% of the document width in the user's real window, so the 20% trim lands ~49px past the sidebar and slices into paragraph text, producing mid-sentence truncation ("uld proponents..." instead of "Would proponents...").

A fixed percentage cannot be correct for every window width/layout combination — it will always either cut into content (too aggressive) or leave sidebar junk in (too conservative) for some case.

## Solution Overview

Replace the percentage-based *image* crop in the fallback case with a two-step approach that never touches pixels:

1. Crop only to the browser's content DOM (`doc_rect`, excluding tab pane/toolbar — the existing render-host-anchored lookup, unchanged) — no percentage trim.
2. OCR the *entire* doc-cropped image, then filter the OCR engine's own per-line results by horizontal position, discarding only whole recognized lines that fall in a detected left-side sidebar column, before reconstructing the final text.

Because this operates on already-recognized lines (each with its own real bounding box from the OCR engine), it can never slice through a glyph or word the way a blind pixel crop can. It also doesn't need to assume any percentage — the split point is derived per-capture from the actual line positions.

This only replaces the *fallback* path. The landmark-found path (proven precise on Facebook) is unchanged.

### Evidence

Captured real OCR line data from the user's actual browser window (`doc_rect` width 1575px — narrower than a fresh test window's ~1860-1920px, because the user's vertical tab pane is open and takes real width). Sidebar lines (Quora logo, "Create Space", subscribed spaces, footer links) all had `left` between 91 and 134. Every other line (nav bar, question, answer paragraphs, footer) had `left` at 281 or higher. Gap: 147px, with nothing in between.

A naive "largest gap in the whole line set" was tried and rejected: the header row has widely-spaced isolated buttons ("Try Quora+" at x=1060, "Add question" at x=1305 — a 245px gap), wider than the 147px sidebar gap. Picking the single largest gap anywhere would target that instead and wrongly threshold away almost all real content. The correct approach is to scan gaps in ascending (left-to-right) order and take the *first* one that clears a minimum size — this lands on the sidebar boundary specifically, and any larger gaps further right (between content elements we want to keep regardless) never get considered.

---

## Changes to `scripts/screenshot-capture.py`

### Remove: `percentage_fallback_rect`, `CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT`

Dead code once the fallback stops doing a percentage image crop. Delete the function and both constants from `screenshot-capture.py`, and their mirrored copies (plus `test_percentage_fallback_rect_default`/`test_percentage_fallback_rect_nonzero_origin`) from `scripts/test_screenshot_logic.py`.

### New constants

```python
MIN_LINES_FOR_SPLIT = 4
MIN_GAP_FOR_SPLIT_PX = 80.0
```

`MIN_LINES_FOR_SPLIT`: below this many recognized lines, there's not enough evidence to trust a split — keep everything (fail open). `MIN_GAP_FOR_SPLIT_PX`: the minimum gap between consecutive distinct line left-edges to treat as a genuine column boundary rather than normal paragraph-indent noise (observed noise gaps in real data: 1-43px; observed real boundary: 147px; 80px sits comfortably between).

### `find_gap_threshold(lefts)` (new, pure)

```python
def find_gap_threshold(lefts: list[float]) -> float | None:
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
```

Scans consecutive distinct left-edges in ascending order and returns the midpoint of the *first* gap that meets `MIN_GAP_FOR_SPLIT_PX`. Returns `None` (meaning "don't filter, keep every line") if there are too few lines to judge, or no qualifying gap exists anywhere — the same fail-open principle used throughout this script.

### `filter_and_sort_lines(lines, threshold)` (new, pure)

```python
def filter_and_sort_lines(
    lines: list[tuple[float, float, str]],
    threshold: float | None,
) -> list[tuple[float, float, str]]:
    kept = [line for line in lines if threshold is None or line[0] >= threshold]
    return sorted(kept, key=lambda line: (line[1], line[0]))
```

Each line is a `(left, top, text)` tuple. Drops lines left of `threshold` (or keeps everything if `threshold is None`), then sorts survivors by `(top, left)` to guarantee top-to-bottom, left-to-right reading order regardless of the OCR engine's internal block-grouping order (observed non-monotonic in real data — lines come back grouped by spatial region, not strictly y-sorted).

### `_decode_to_bitmap(bmp_bytes)` (refactor, extracted from existing `_ocr_async`)

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
```

Existing `_ocr_async` is rewritten to call this instead of inlining the same steps, so the new `_ocr_lines_async` doesn't duplicate them. `_ocr_async`'s own behavior/return value is unchanged — this is a pure extraction, not a logic change, low risk to the already-shipped landmark-path OCR flow:

```python
async def _ocr_async(bmp_bytes: bytes) -> str:
    bitmap = await _decode_to_bitmap(bmp_bytes)
    engine = OcrEngine.try_create_from_user_profile_languages()
    if engine is None:
        return ""
    result = await engine.recognize_async(bitmap)
    return result.text
```

### `_ocr_lines_async(bmp_bytes)` / `run_ocr_lines(bmp_bytes)` (new, impure)

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
```

A line's position is derived from its words' bounding rectangles (`OcrLine` has no bounding-rect property of its own in this API) — confirmed working against a live capture during design. Lines with no words are skipped (defensive; not observed in practice but costs nothing to guard).

### `run_ocr_filtered(bmp_bytes)` (new, impure — composes the above)

```python
def run_ocr_filtered(bmp_bytes: bytes) -> str:
    lines = run_ocr_lines(bmp_bytes)
    threshold = find_gap_threshold([left for left, _, _ in lines])
    kept = filter_and_sort_lines(lines, threshold)
    return "\n".join(text for _, _, text in kept)
```

### `get_main_content_rect(hwnd)` — changed return type

```python
def get_main_content_rect(hwnd: int) -> tuple[tuple[int, int, int, int] | None, bool]:
    try:
        window_rect = win32gui.GetWindowRect(hwnd)
        window = auto.ControlFromHandle(hwnd)
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

Second element of the tuple is `needs_line_filtering`: `True` means the crop box only excludes browser chrome and the caller must OCR the full width and filter by line position; `False` means the crop box is already precise (landmark-derived) or `None` (no crop at all — capture full window, matching existing fail-open behavior). The `window.PaneControl(...)`/render-host anchoring, landmark search, and width-rejection logic are all unchanged from the already-shipped code — only the final branch (what happens when no usable landmark exists) changes.

### `main()` — updated wiring

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

Replaces the single `ocr_text = run_ocr(bmp_bytes)...` line. Everything else in `main()` (dedup, sending, state save) is unchanged.

---

## Tests (`scripts/test_screenshot_logic.py`)

Mirror `find_gap_threshold` and `filter_and_sort_lines` (both pure) into the inlined-copy pattern, with the same constants:

- `find_gap_threshold`:
  - real-data fixture (a representative subset of the actual captured Quora line lefts, including both the sidebar/content gap and the wider header-button gaps) → returns the sidebar boundary's midpoint, **not** the largest gap — this is the regression test for the largest-gap bug caught during design.
  - fewer than `MIN_LINES_FOR_SPLIT` lines → `None`
  - enough lines but no gap reaches `MIN_GAP_FOR_SPLIT_PX` → `None`
  - a gap exactly equal to `MIN_GAP_FOR_SPLIT_PX` → counts (inclusive `>=`)
  - a small-but-qualifying early gap alongside a larger later gap → returns the early one's midpoint
- `filter_and_sort_lines`:
  - `threshold=None` → all lines kept, sorted by `(top, left)`
  - threshold set → lines left of it dropped, survivors sorted
  - out-of-order input (simulating the OCR engine's non-monotonic block order) → correctly sorted output

`_ocr_lines_async`/`run_ocr_lines`/`run_ocr_filtered`/`get_main_content_rect` are UIA/OCR-touching and not unit-tested, consistent with this file's existing convention. Manual verification required: confirm on the same real Quora window that filtered OCR text no longer truncates sentences and no longer contains sidebar space names; confirm Facebook (landmark path) is unaffected; confirm LinkedIn's fallback case (when its landmark is rejected) also benefits from the same filtering, without site-specific logic.

---

## Dependencies

None — no new packages. Uses the same `winsdk.windows.media.ocr` API already in use, just reading `result.lines`/`.words` instead of `result.text`.

---

## Out of Scope

- Right-side sidebar/column filtering (e.g. Quora topic pages' "Related Topics" column). The gap-scan only looks for a left-side boundary; a right-side column's lines have `left` values inside the "kept" range and pass through unfiltered. This matches the already-accepted "leaves opposite sidebar visible" tradeoff from the crop feature's final review — not a regression, and arguably an improvement (no more content truncation) even where it doesn't fully solve the noise problem.
- Guaranteeing the *first* qualifying gap is always the correct one. A page whose sidebar itself has an internal gap wider than `MIN_GAP_FOR_SPLIT_PX` (e.g. an icon-only row) could in principle trigger a split partway through the sidebar rather than at its true edge. Not observed in the real data gathered for this design; accepted as a heuristic limitation, not solved by scanning further gaps or clustering, to keep the algorithm simple.
- Any change to the landmark-found path (Facebook) or to `find_main_landmark`/`landmark_too_wide`/the render-host anchoring — all unchanged.
- Non-Latin-script sidebar detection or language-specific tuning — the gap heuristic is purely geometric and language-agnostic.
