import ctypes
import datetime
import hashlib
import io
import json
import re
from pathlib import Path

import pytesseract
import requests
import uiautomation as auto
import win32gui
import win32ui
from PIL import Image
from pytesseract import Output

STATE_FILE = Path(__file__).parent / "screenshot-capture-state.json"
DIGITAL_ME_URL = "http://localhost:8080/addContent"
SITE_KEYWORDS = {"linkedin": "linkedin", "facebook": "facebook", "quora": "quora", "google docs": "google-docs"}
# Order matters: "chrome" must precede "edge" so titles like "...share knowledge -
# Google Chrome" match "chrome" first, since "knowledge" itself contains "edge".
BROWSER_KEYWORDS = ("chrome", "edge", "firefox", "opera", "brave")
UIA_CAPABLE_BROWSERS = {"chrome", "edge"}
SUBPAGE_GATED_SITES = {"quora", "linkedin"}
CROP_CONTENT_SITES = {"quora", "linkedin", "facebook", "google-docs"}
MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100
PW_RENDERFULLCONTENT = 0x00000002  # required to capture GPU-composited windows (e.g. Chromium)
TESSERACT_LANG = "isl+eng"
# Required module-level side effect: configures the pytesseract wrapper to find the
# Tesseract executable; not just a constant declaration.
pytesseract.pytesseract.tesseract_cmd = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
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
    # Groups on (block_num, par_num, line_num); page_num is intentionally omitted since
    # it's always 1 for single-image OCR.
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


GOOGLE_DOCS_DOCUMENT_PATTERN = re.compile(r"docs\.google\.com/document/d/")


def is_non_document_google_docs_page(pagename: str, url: str | None) -> bool:
    return pagename == "google-docs" and url is not None and not GOOGLE_DOCS_DOCUMENT_PATTERN.search(url)


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"last_hash": None, "last_processed_text": None}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state), encoding="utf-8")


IDLE_TIMEOUT_SECONDS = 120


def derive_session_key(pagename: str, url: str | None) -> str:
    return url if url else pagename


def merge_session_lines(existing_lines: list[str], new_text: str) -> list[str]:
    merged = list(existing_lines)
    seen = set(existing_lines)
    for line in new_text.split("\n"):
        if line not in seen:
            merged.append(line)
            seen.add(line)
    return merged


def is_session_idle(last_capture_at: str, now: datetime.datetime, idle_timeout_seconds: int) -> bool:
    last = datetime.datetime.fromisoformat(last_capture_at)
    return (now - last).total_seconds() > idle_timeout_seconds


def start_session(session_key: str, pagename: str, window_title: str, now: datetime.datetime) -> dict:
    timestamp = now.isoformat()
    return {
        "key": session_key,
        "pagename": pagename,
        "window_title": window_title,
        "started_at": timestamp,
        "last_capture_at": timestamp,
        "lines": [],
    }


def resolve_active_session(
    state: dict, pagename: str, window_title: str, session_key: str, now: datetime.datetime
) -> tuple[dict, dict | None]:
    active_session = state.get("active_session")
    session_to_flush = None
    if active_session is not None and active_session["key"] != session_key:
        session_to_flush = active_session
        active_session = None
    if active_session is None:
        active_session = start_session(session_key, pagename, window_title, now)
    return active_session, session_to_flush


def check_idle_flush(
    state: dict, now: datetime.datetime, idle_timeout_seconds: int
) -> tuple[dict, dict | None]:
    active_session = state.get("active_session")
    if active_session is None or not is_session_idle(active_session["last_capture_at"], now, idle_timeout_seconds):
        return state, None
    new_state = dict(state)
    new_state["active_session"] = None
    return new_state, active_session


def preprocess_for_ocr(image: Image.Image) -> Image.Image:
    grayscale = image.convert("L")
    return grayscale.resize((grayscale.width * 2, grayscale.height * 2))


def run_ocr(bmp_bytes: bytes) -> str:
    image = preprocess_for_ocr(Image.open(io.BytesIO(bmp_bytes)))
    return pytesseract.image_to_string(image, lang=TESSERACT_LANG)


def run_ocr_lines(bmp_bytes: bytes) -> list[tuple[float, float, str]]:
    image = preprocess_for_ocr(Image.open(io.BytesIO(bmp_bytes)))
    data = pytesseract.image_to_data(image, lang=TESSERACT_LANG, output_type=Output.DICT)
    lines = group_words_into_lines(data)
    # preprocess_for_ocr upscales 2x for OCR accuracy; rescale coordinates back down to
    # the original screenshot resolution so find_gap_threshold's absolute pixel constant
    # (MIN_GAP_FOR_SPLIT_PX, calibrated pre-upscaling) still applies correctly.
    return [(left / 2, top / 2, text) for left, top, text in lines]


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


def flush_session(session: dict) -> None:
    if not session["lines"]:
        return
    merged_text = "\n".join(session["lines"])
    started = datetime.datetime.fromisoformat(session["started_at"])
    timestamp = started.strftime("%Y%m%d_%H%M%S")
    entry_name = f"screenshot_{session['pagename']}_{timestamp}"
    send_to_digital_me(session["window_title"], entry_name, merged_text)


def main() -> None:
    now = datetime.datetime.now()
    state = load_state()

    # Runs regardless of what's currently in the foreground, so a session isn't
    # stranded forever if the user never returns to that tab (e.g. closes the browser).
    state, stale_session = check_idle_flush(state, now, IDLE_TIMEOUT_SECONDS)
    if stale_session is not None:
        flush_session(stale_session)
        save_state(state)

    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return

    url = get_address_bar_url(hwnd) if browser in UIA_CAPABLE_BROWSERS else None
    if pagename in SUBPAGE_GATED_SITES and url is not None and has_subpath(url) and not is_subpage_exempt(url):
        return
    if is_non_document_google_docs_page(pagename, url):
        return

    crop_box = None
    needs_line_filtering = False
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box, needs_line_filtering = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
    current_hash = hash_bytes(bmp_bytes)
    session_key = derive_session_key(pagename, url)

    if current_hash == state.get("last_hash"):
        # Nothing changed on screen, but the user may just be reading without
        # scrolling -- keep the session alive rather than letting it idle out.
        active_session, session_to_flush = resolve_active_session(state, pagename, window_title, session_key, now)
        active_session["last_capture_at"] = now.isoformat()
        state = dict(state)
        state["active_session"] = active_session
        if session_to_flush is not None:
            flush_session(session_to_flush)
        save_state(state)
        return

    if needs_line_filtering:
        ocr_text = run_ocr_filtered(bmp_bytes).strip().replace("\r\n", "\n")
    else:
        ocr_text = run_ocr(bmp_bytes).strip().replace("\r\n", "\n")

    active_session, session_to_flush = resolve_active_session(state, pagename, window_title, session_key, now)
    if ocr_text != state.get("last_processed_text"):
        active_session["lines"] = merge_session_lines(active_session["lines"], ocr_text)
    active_session["last_capture_at"] = now.isoformat()

    state = dict(state)
    state["active_session"] = active_session
    state["last_hash"] = current_hash
    state["last_processed_text"] = ocr_text
    if session_to_flush is not None:
        flush_session(session_to_flush)
    save_state(state)


if __name__ == "__main__":
    main()
