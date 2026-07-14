# Screenshot Main-Content Crop — Design Spec
Date: 2026-07-14

## Problem

`scripts/screenshot-capture.py` currently OCRs the entire browser window. For Quora, LinkedIn, and Facebook, the window contains a lot of text that isn't useful for search indexing: the browser's own vertical tab pane (user-resizable, so its width varies between captures) and, within the page itself, a left navigation column and a right column (suggestions/ads/empty) that flank the actual feed/article content in the middle. Indexing that noise dilutes OCR text quality and wastes tokens.

## Solution Overview

Crop the screenshot to the page's main-content column before OCR, in two layers:

1. **Exclude browser chrome dynamically.** Ask Windows UI Automation for the window's `Document`-type control — Chrome/Edge expose the actual rendered page as a `Document` control, distinct from the tab strip and toolbars. Its bounding rectangle tracks the real content area regardless of how wide the (user-resizable) vertical tab pane currently is, so no fixed pixel offset is needed. As a side effect this also excludes the toolbar/address-bar height.

2. **Exclude the page's own left/right sidebar columns.** Within the `Document` control, search for a `main` ARIA landmark (Quora/LinkedIn/Facebook all mark their central feed/article with semantic `<main>` markup, which Chromium's accessibility tree exposes as a UIA landmark). If found, crop to its bounding rectangle. If not found, fall back to trimming a fixed percentage off the left and right of the `Document` rectangle. If the `Document` control itself can't be found, skip cropping entirely — capture the full window, matching this script's existing "unknown state → capture, don't skip" fail-open principle.

Scope: Chrome and Edge only, same as the existing sub-page-skip feature (Firefox/Opera/Brave keep today's full-window capture — no UIA lookup is attempted for them).

---

## Changes to `scripts/screenshot-capture.py`

### Rename `SUBPAGE_CAPABLE_BROWSERS` → `UIA_CAPABLE_BROWSERS`

The existing constant name is specific to the sub-page-skip feature, but this new feature relies on the same underlying capability (Chrome/Edge exposing UIA controls: the address bar `EditControl`, and now the `Document` control and its landmarks). Rename in place; update both existing usages (the sub-page gating check) and the new usage added below. Value unchanged: `{"chrome", "edge"}`.

### `CROP_CONTENT_SITES` (new constant)

```python
CROP_CONTENT_SITES = {"quora", "linkedin", "facebook"}
```

### Fallback-crop and validation constants (new)

```python
CONTENT_CROP_LEFT_PCT = 0.20
CONTENT_CROP_RIGHT_PCT = 0.20
MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100
```

`CONTENT_CROP_LEFT_PCT`/`_RIGHT_PCT` are the fraction of the `Document` control's width trimmed from each side when no `main` landmark is found (default: keep the middle 60%). `MIN_CROP_WIDTH`/`MIN_CROP_HEIGHT` guard against a degenerate crop box (e.g. a landmark rect that's nearly empty) — below these thresholds, cropping is abandoned and the full window is captured instead.

### `percentage_fallback_rect(doc_rect)` (new, pure)

```python
def percentage_fallback_rect(doc_rect: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    left, top, right, bottom = doc_rect
    width = right - left
    new_left = left + int(width * CONTENT_CROP_LEFT_PCT)
    new_right = right - int(width * CONTENT_CROP_RIGHT_PCT)
    return (new_left, top, new_right, bottom)
```

All rects in this file are `(left, top, right, bottom)` tuples in absolute screen pixel coordinates, matching `win32gui.GetWindowRect()`'s convention.

### `content_rect_to_crop_box(content_rect, window_rect)` (new, pure)

```python
def content_rect_to_crop_box(
    content_rect: tuple[int, int, int, int],
    window_rect: tuple[int, int, int, int],
) -> tuple[int, int, int, int] | None:
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

Converts a screen-coordinate content rectangle into a window-relative crop box (the coordinate space `PIL.Image.crop()` needs, since the captured image's origin is the window's top-left corner), clamping it to the window's own bounds. Returns `None` — meaning "don't crop" — if the result is degenerate.

- Normal case: landmark or fallback rect entirely inside the window → box matches it, offset to window-relative coordinates.
- Landmark rect partially outside the window bounds (rare UIA rounding) → clamped to the window edge.
- Landmark rect too narrow/short (below `MIN_CROP_WIDTH`/`MIN_CROP_HEIGHT`) → `None`, fail open.

### `find_main_landmark(doc_control)` (new, impure — walks the UIA tree)

```python
UIA_LANDMARK_TYPE_PROPERTY_ID = 30157  # UIA_LandmarkTypePropertyId
UIA_MAIN_LANDMARK_TYPE_ID = 80002      # UIA_MainLandmarkTypeId
MAX_LANDMARK_SEARCH_NODES = 500
MAX_LANDMARK_SEARCH_DEPTH = 20


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
            rect = control.BoundingRectangle
            return (rect.left, rect.top, rect.right, rect.bottom)
        if depth < MAX_LANDMARK_SEARCH_DEPTH:
            try:
                children = control.GetChildren()
            except Exception:
                children = []
            for child in children:
                queue.append((child, depth + 1))
    return None
```

`UIA_LandmarkTypePropertyId` (30157) and `UIA_MainLandmarkTypeId` (80002) are standard, stable Win32 UI Automation constants (defined in `UIAutomationClient.h`), used here via the `uiautomation` package's generic `GetPropertyValue()` rather than a convenience wrapper. Confirmed against the installed `uiautomation` package (`auto.PropertyId.LandmarkTypeProperty == 30157`) and Microsoft's own "Landmark Type Identifiers" documentation.

Breadth-first, capped at 500 visited nodes and depth 20, to bound worst-case latency against a large page DOM. Returns the first `main`-landmark's bounding rectangle found, or `None` if none is found (or the cap is hit) — triggering the percentage fallback.

### `get_main_content_rect(hwnd)` (new, impure — glue)

```python
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

Broad exception catch is intentional, matching `get_address_bar_url`'s existing pattern: any UIA failure degrades to "no crop," never a crash.

### Addendum: rejecting too-wide landmarks

Live verification against the real target sites (with corrected constants and the render-host
anchoring, see below) found that a `main` landmark, when present, doesn't always mean what this
feature needs it to mean. On LinkedIn's feed, the `main` landmark spans the *entire* three-column
layout (left profile card, center feed, right "Add to your feed" column) — 100% of the document's
width (landmark width equal to `doc_rect`'s own width) — because LinkedIn wraps the whole page body
in one `<main>` element rather than scoping it to just the feed column. Using it verbatim produced a
screenshot barely narrower than the uncropped window, defeating the feature's purpose. Facebook's
`main` landmark, by contrast, correctly scopes to just the center feed column (51% of document width)
and produces an excellent crop.

Fix: `landmark_too_wide(landmark_rect, doc_rect)` (new, pure) rejects a landmark whose width exceeds
`MAX_LANDMARK_WIDTH_FRACTION = 0.80` (80%) of the document's width, treating it as "not useful" and
falling through to `percentage_fallback_rect` instead:

```python
MAX_LANDMARK_WIDTH_FRACTION = 0.80

def landmark_too_wide(landmark_rect: tuple[int, int, int, int], doc_rect: tuple[int, int, int, int]) -> bool:
    landmark_width = landmark_rect[2] - landmark_rect[0]
    doc_width = doc_rect[2] - doc_rect[0]
    if doc_width <= 0:
        return False
    return (landmark_width / doc_width) > MAX_LANDMARK_WIDTH_FRACTION
```

Verified live: LinkedIn's landmark (ratio ~1.0) is now rejected, falling back to the already-verified
20%/20% percentage crop; Facebook's landmark (ratio ~0.51) is well under the threshold and is used
unchanged.

### `take_screenshot_bmp(hwnd, crop_box=None)` — extended signature

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

Still captures the full window region (simplest — cropping the in-memory image is cheap and avoids duplicating window-rect logic in two places), then crops before encoding to BMP. `crop_box=None` (the default) preserves today's full-window behavior exactly, for any caller/site not in `CROP_CONTENT_SITES`.

### `main()` — new step between site detection and screenshot capture

```python
crop_box = None
if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
    crop_box = get_main_content_rect(hwnd)
bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
```

Placed after the existing sub-page gating check (which may already have returned early) and replaces the current unconditional `bmp_bytes = take_screenshot_bmp(hwnd)` line. Downstream logic (hashing, OCR, dedup-by-text, sending) is unchanged — it now simply operates on the cropped image when cropping applied, which as a side benefit makes the dedup hash less sensitive to sidebar content (ads, "who to follow" widgets) shifting independently of the main content.

---

## Tests (`scripts/test_screenshot_logic.py`)

Mirror the pure functions into the file's existing inlined-copy pattern, with the same constants (`CONTENT_CROP_LEFT_PCT`, `CONTENT_CROP_RIGHT_PCT`, `MIN_CROP_WIDTH`, `MIN_CROP_HEIGHT`):

- `percentage_fallback_rect`:
  - symmetric default (20%/20%) on a round-number rect, e.g. `(0, 0, 1000, 800)` → `(200, 0, 800, 800)`
  - non-square rect / non-zero origin, to confirm `top`/`bottom` pass through untouched and `left`/`right` offsets aren't accidentally window-relative
- `content_rect_to_crop_box`:
  - content rect fully inside window → simple offset translation
  - content rect exceeding window bounds on one side → clamped to the window edge
  - degenerate case (width or height below `MIN_CROP_WIDTH`/`MIN_CROP_HEIGHT`) → `None`
- `landmark_too_wide`:
  - landmark width clearly over the 80% threshold → `True`
  - landmark width clearly under the threshold → `False`
  - landmark width exactly at the threshold (80% of doc width) → `False` (strict `>` comparison, not `>=`)
  - zero-width `doc_rect` → `False` (avoid division by zero; safe default is "not too wide")

`find_main_landmark` and `get_main_content_rect` are UIA-touching and not unit-tested — consistent with `get_address_bar_url` (see `docs/testing.md`). Manual verification against live Chrome/Edge windows on Quora, LinkedIn, and Facebook is required before merge: confirm a `main` landmark is found where the site markup has one, confirm the percentage fallback engages sensibly where it doesn't, and confirm OCR output on the cropped image excludes the tab pane and side columns.

---

## Dependencies

None — `uiautomation`, `mss`, and `pywin32` are already required by this script.

---

## Out of Scope

- Vertical cropping beyond what the `Document` control's own bounds already exclude (toolbar/address bar height). The user's request was specifically about left/right columns; no attempt is made to also strip a page's own header/nav bar vertically.
- Firefox, Opera, Brave — unaffected, same as the existing sub-page-skip feature's scoping.
- Multiple or nested `main` landmarks, or unusual DOM structures — first match found by the breadth-first search wins; no scoring or "best" heuristic.
- Landmark search performance tuning beyond the node/depth caps — if manual verification shows the 500-node/depth-20 cap is too slow or too shallow in practice, that's a follow-up, not blocking this feature.
- User-configurable fallback percentages (e.g. via `application.properties` or a config file) — `CONTENT_CROP_LEFT_PCT`/`_RIGHT_PCT` are module constants, matching this script's existing style (e.g. `SUBPAGE_GATED_SITES`).
