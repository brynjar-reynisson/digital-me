import asyncio
import datetime
import hashlib
import io
import json
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
BROWSER_KEYWORDS = {"chrome", "edge", "firefox", "opera", "brave"}


def detect_site(window_title: str) -> tuple[str | None, str]:
    lower = window_title.lower()
    if not any(b in lower for b in BROWSER_KEYWORDS):
        return None, window_title
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, window_title
    return None, window_title


def get_active_window() -> tuple[int, str]:
    hwnd = win32gui.GetForegroundWindow()
    return hwnd, win32gui.GetWindowText(hwnd)


def take_screenshot_bmp(hwnd: int) -> bytes:
    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    region = {"left": left, "top": top, "width": right - left, "height": bottom - top}
    with mss.mss() as sct:
        img = sct.grab(region)
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
    resp = requests.post(
        DIGITAL_ME_URL,
        json={"source": source, "name": name, "content": content},
        timeout=10,
    )
    resp.raise_for_status()


def main() -> None:
    hwnd, title = get_active_window()
    pagename, window_title = detect_site(title)
    if pagename is None:
        return
    bmp_bytes = take_screenshot_bmp(hwnd)
    current_hash = hash_bytes(bmp_bytes)

    state = load_state()
    if current_hash == state.get("last_hash"):
        return

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
