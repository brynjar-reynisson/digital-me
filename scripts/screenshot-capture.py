import asyncio
import ctypes
import datetime
import hashlib
import io
import json
import re
from pathlib import Path

import requests
import uiautomation as auto
import win32gui
import win32ui
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
# Order matters: "chrome" must precede "edge" so titles like "...share knowledge -
# Google Chrome" match "chrome" first, since "knowledge" itself contains "edge".
BROWSER_KEYWORDS = ("chrome", "edge", "firefox", "opera", "brave")
UIA_CAPABLE_BROWSERS = {"chrome", "edge"}
SUBPAGE_GATED_SITES = {"quora", "linkedin"}
CROP_CONTENT_SITES = {"quora", "linkedin", "facebook"}
MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100
PW_RENDERFULLCONTENT = 0x00000002  # required to capture GPU-composited windows (e.g. Chromium)
UIA_LANDMARK_TYPE_PROPERTY_ID = 30157  # UIA_LandmarkTypePropertyId
UIA_MAIN_LANDMARK_TYPE_ID = 80002      # UIA_MainLandmarkTypeId
MAX_LANDMARK_SEARCH_NODES = 500
MAX_LANDMARK_SEARCH_DEPTH = 20
MAX_LANDMARK_WIDTH_FRACTION = 0.80


def detect_site(window_title: str) -> tuple[str | None, str | None, str]:
    lower = window_title.lower()
    matched_browser = next((b for b in BROWSER_KEYWORDS if b in lower), None)
    if matched_browser is None:
        return None, None, window_title
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, matched_browser, window_title
    return None, None, window_title


def get_active_window() -> tuple[int, str]:
    hwnd = win32gui.GetForegroundWindow()
    return hwnd, win32gui.GetWindowText(hwnd)


def get_address_bar_url(hwnd: int) -> str | None:
    try:
        window = auto.ControlFromHandle(hwnd)
        edit = window.EditControl(Name="Address and search bar")
        if not edit.Exists(0, 0):
            return None
        return edit.GetValuePattern().Value
    except Exception:
        return None


def take_screenshot_bmp(hwnd: int, crop_box: tuple[int, int, int, int] | None = None) -> bytes:
    # PrintWindow renders directly from the window's own surface rather than copying
    # on-screen pixels, so it's unaffected by other windows overlapping it on the desktop
    # (unlike a screen-region grab, which captures whatever is visually on top).
    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    width, height = right - left, bottom - top

    hwnd_dc = win32gui.GetWindowDC(hwnd)
    mfc_dc = win32ui.CreateDCFromHandle(hwnd_dc)
    save_dc = mfc_dc.CreateCompatibleDC()
    save_bitmap = win32ui.CreateBitmap()
    save_bitmap.CreateCompatibleBitmap(mfc_dc, width, height)
    save_dc.SelectObject(save_bitmap)

    ctypes.windll.user32.PrintWindow(hwnd, save_dc.GetSafeHdc(), PW_RENDERFULLCONTENT)

    bmpinfo = save_bitmap.GetInfo()
    bmpstr = save_bitmap.GetBitmapBits(True)
    pil = Image.frombuffer(
        "RGB", (bmpinfo["bmWidth"], bmpinfo["bmHeight"]), bmpstr, "raw", "BGRX", 0, 1
    )

    win32gui.DeleteObject(save_bitmap.GetHandle())
    save_dc.DeleteDC()
    mfc_dc.DeleteDC()
    win32gui.ReleaseDC(hwnd, hwnd_dc)

    if crop_box is not None:
        pil = pil.crop(crop_box)
    buf = io.BytesIO()
    pil.save(buf, format="BMP")
    return buf.getvalue()


def hash_bytes(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def has_subpath(url: str) -> bool:
    idx = url.rfind(".com/")
    if idx == -1:
        return False
    return len(url[idx + len(".com/"):]) > 0


def content_rect_to_crop_box(content_rect: tuple[int, int, int, int], window_rect: tuple[int, int, int, int]) -> tuple[int, int, int, int] | None:
    c_left, c_top, c_right, c_bottom = content_rect
    w_left, w_top, w_right, w_bottom = window_rect
    box_left = max(c_left - w_left, 0)
    box_top = max(c_top - w_top, 0)
    box_right = min(c_right - w_left, w_right - w_left)
    box_bottom = min(c_bottom - w_top, w_bottom - w_top)
    if box_right - box_left < MIN_CROP_WIDTH or box_bottom - box_top < MIN_CROP_HEIGHT:
        return None
    return (box_left, box_top, box_right, box_bottom)


MIN_LINES_FOR_SPLIT = 4
MIN_GAP_FOR_SPLIT_PX = 80.0


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


def filter_and_sort_lines(lines: list[tuple[float, float, str]], threshold: float | None) -> list[tuple[float, float, str]]:
    kept = [line for line in lines if threshold is None or line[0] >= threshold]
    return sorted(kept, key=lambda line: (line[1], line[0]))


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


def landmark_too_wide(landmark_rect: tuple[int, int, int, int], doc_rect: tuple[int, int, int, int]) -> bool:
    landmark_width = landmark_rect[2] - landmark_rect[0]
    doc_width = doc_rect[2] - doc_rect[0]
    if doc_width <= 0:
        return False
    return (landmark_width / doc_width) > MAX_LANDMARK_WIDTH_FRACTION


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


LINKEDIN_FEED_PATTERN = re.compile(r"linkedin\.com/feed/?(?:[?#]|$)")
QUORA_TOPIC_PATTERN = re.compile(r"quora\.com/topic/")


def is_subpage_exempt(url: str) -> bool:
    return bool(LINKEDIN_FEED_PATTERN.search(url)) or bool(QUORA_TOPIC_PATTERN.search(url))


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"last_hash": None, "last_sent_text": None}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state), encoding="utf-8")


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


def run_ocr(bmp_bytes: bytes) -> str:
    return asyncio.run(_ocr_async(bmp_bytes))


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


def send_to_digital_me(source: str, name: str, content: str) -> None:
    resp = requests.post(
        DIGITAL_ME_URL,
        json={"source": source, "name": name, "content": content},
        timeout=10,
    )
    resp.raise_for_status()


def main() -> None:
    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return
    if pagename in SUBPAGE_GATED_SITES and browser in UIA_CAPABLE_BROWSERS:
        url = get_address_bar_url(hwnd)
        if url is not None and has_subpath(url) and not is_subpage_exempt(url):
            return
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
