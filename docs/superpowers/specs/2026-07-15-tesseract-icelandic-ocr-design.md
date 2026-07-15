# Tesseract OCR for Icelandic Support — Design

## Problem

`scripts/screenshot-capture.py` uses `Windows.Media.Ocr` (`OcrEngine.try_create_from_user_profile_languages()`) to OCR browser screenshots. Windows OCR does not support Icelandic (`is-IS`) at all — confirmed via `Get-WindowsCapability -Online | Where-Object {$_.Name -like "Language.OCR*"}`, which lists 34 supported languages, none of them Icelandic. Icelandic-specific characters (þ, ð, æ, ö, accented vowels) come through misrecognized as similar Latin characters on Facebook/Quora/LinkedIn captures (confirmed against a real captured file where "Ólafur", "Guðmundsdóttir", "blóðþrýstingi" were garbled), while ASCII text and overall structure stay accurate.

## Goal

Replace Windows OCR with Tesseract OCR (via `pytesseract`), configured for a combined Icelandic+English language model, so mixed-language pages recognize correctly. This is a full replacement, not a fallback — one OCR engine for every capture.

## Non-goals

- No per-site or per-language OCR engine switching logic.
- No change to the crop/line-filtering pipeline's public shape (`find_gap_threshold`, `filter_and_sort_lines`, `content_rect_to_crop_box`, etc. are untouched).
- No change to `main()`'s control flow, state/dedup logic, or the `/addContent` POST.

## Installation

Tesseract's Windows binary is not bundled with `pytesseract` (a pure wrapper) and is not currently installed on this machine. Install via winget:

```
winget install --id UB-Mannheim.TesseractOCR -e
```

This is the standard community-maintained Windows build. After installing, confirm `eng.traineddata` and `isl.traineddata` exist in its `tessdata` folder (typically `C:\Program Files\Tesseract-OCR\tessdata\`). If Icelandic isn't bundled by the installer, download `isl.traineddata` from the official `tesseract-ocr/tessdata` GitHub repo into that folder.

## Dependency changes

`scripts/requirements.txt`:
- Add `pytesseract`
- Remove `winsdk` (only consumer was the Windows OCR code path being removed)

## Code changes — `scripts/screenshot-capture.py`

### Removed
- `import asyncio`
- `winsdk` imports: `BitmapAlphaMode`, `BitmapDecoder`, `BitmapPixelFormat`, `OcrEngine`, `DataWriter`, `InMemoryRandomAccessStream`
- `_decode_to_bitmap()`, `_ocr_async()`, `_ocr_lines_async()`

The entire async layer existed only to bridge to the WinRT `Windows.Media.Ocr` API. `pytesseract` is a synchronous call, so this layer disappears rather than being replaced — a net simplification.

### Added
- `import pytesseract` (plus `from pytesseract import Output`)
- `TESSERACT_LANG = "isl+eng"` constant
- `pytesseract.pytesseract.tesseract_cmd` set to the known install path (`C:\Program Files\Tesseract-OCR\tesseract.exe`), following this project's existing convention of hardcoding known local install paths (see the IntelliJ-bundled Maven path in `docs/tooling.md`)
- A small preprocessing helper: convert the cropped `PIL.Image` to grayscale and upscale 2x before OCR. Tesseract is tuned for ~300 DPI document scans; typical UI text at screen resolution is small enough that upscaling measurably improves recognition. Both `run_ocr` and `run_ocr_lines` apply this before calling into `pytesseract`.

### Changed (same public shape, new implementation)
- `run_ocr(bmp_bytes: bytes) -> str`: preprocess, then `pytesseract.image_to_string(image, lang=TESSERACT_LANG)`.
- `run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]`: preprocess, then `pytesseract.image_to_data(image, lang=TESSERACT_LANG, output_type=Output.DICT)`. Group the returned words by `(block_num, par_num, line_num)` in their original order, join each group's non-empty words with a single space, and emit one `(left, top, text)` tuple per group using that group's minimum `left`/`top`. This is the exact shape `find_gap_threshold()` and `filter_and_sort_lines()` already consume, so neither of those functions — nor `run_ocr_filtered()`, which calls `run_ocr_lines()` — needs to change.

### Unchanged
- `main()`, `load_state`/`save_state`, `send_to_digital_me`, `take_screenshot_bmp` (PrintWindow capture), all crop-math functions, `detect_site`/`SITE_KEYWORDS`.

## Testing

- Existing 42 pure-function tests in `scripts/test_screenshot_logic.py` don't touch OCR and must keep passing unmodified.
- Per this project's existing convention (see `DeepseekSummarizeClientTest`, which only unit-tests pure static helpers and verifies subprocess behavior manually), Tesseract itself is not unit-tested in CI — it requires a real installed binary and real rendered text. Verification is manual:
  1. Capture a real Icelandic Facebook/Quora/LinkedIn page and confirm previously-garbled words (e.g. "Ólafur", "Guðmundsdóttir", "blóðþrýstingi") now come through correctly.
  2. Capture a real English page and confirm no regression from the Windows-OCR baseline.

## Docs

Update `docs/architecture.md`'s notes on the screenshot/OCR pipeline (currently describes gap-filtered line composition without naming an OCR engine) to reflect the Tesseract swap, per CLAUDE.md's requirement to update docs when committing a feature branch.
