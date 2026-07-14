import hashlib
import json
import re
import tempfile
from pathlib import Path

# Inline the two pure functions so this file has no external imports
SITE_KEYWORDS = {"linkedin": "linkedin", "facebook": "facebook", "quora": "quora"}
# Order matters: "chrome" must precede "edge" so titles like "...share knowledge -
# Google Chrome" match "chrome" first, since "knowledge" itself contains "edge".
BROWSER_KEYWORDS = ("chrome", "edge", "firefox", "opera", "brave")
LINKEDIN_FEED_PATTERN = re.compile(r"linkedin\.com/feed/?(?:[?#]|$)")
QUORA_TOPIC_PATTERN = re.compile(r"quora\.com/topic/")

def detect_site(window_title: str) -> tuple:
    lower = window_title.lower()
    matched_browser = next((b for b in BROWSER_KEYWORDS if b in lower), None)
    if matched_browser is None:
        return None, None, window_title
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, matched_browser, window_title
    return None, None, window_title

def hash_bytes(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()

def has_subpath(url: str) -> bool:
    idx = url.rfind(".com/")
    if idx == -1:
        return False
    return len(url[idx + len(".com/"):]) > 0

def is_subpage_exempt(url: str) -> bool:
    return bool(LINKEDIN_FEED_PATTERN.search(url)) or bool(QUORA_TOPIC_PATTERN.search(url))

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

MAX_LANDMARK_WIDTH_FRACTION = 0.80

def landmark_too_wide(landmark_rect: tuple, doc_rect: tuple) -> bool:
    landmark_width = landmark_rect[2] - landmark_rect[0]
    doc_width = doc_rect[2] - doc_rect[0]
    if doc_width <= 0:
        return False
    return (landmark_width / doc_width) > MAX_LANDMARK_WIDTH_FRACTION

def test_detect_quora():
    pagename, browser, title = detect_site("Quora - A place to share knowledge - Google Chrome")
    assert pagename == "quora", f"expected quora, got {pagename}"
    assert browser == "chrome", f"expected chrome, got {browser}"
    assert "Quora" in title

def test_detect_linkedin():
    pagename, browser, _ = detect_site("Feed | LinkedIn - Google Chrome")
    assert pagename == "linkedin"
    assert browser == "chrome"

def test_detect_facebook():
    pagename, browser, _ = detect_site("Facebook - Google Chrome")
    assert pagename == "facebook"
    assert browser == "chrome"

def test_detect_no_match():
    pagename, browser, _ = detect_site("GitHub - Microsoft Edge")
    assert pagename is None
    assert browser is None

def test_detect_case_insensitive():
    pagename, browser, _ = detect_site("QUORA - SOME TITLE - GOOGLE CHROME")
    assert pagename == "quora"
    assert browser == "chrome"

def test_detect_ignores_notepad():
    pagename, browser, _ = detect_site("screenshot_quora_20260620_014350.txt - Notepad")
    assert pagename is None
    assert browser is None

def test_detect_ignores_explorer():
    pagename, browser, _ = detect_site("LinkedIn - File Explorer")
    assert pagename is None
    assert browser is None

def test_detect_microsoft_edge():
    pagename, browser, _ = detect_site("Feed | LinkedIn - Microsoft Edge")
    assert pagename == "linkedin"
    assert browser == "edge"

def test_hash_deterministic():
    assert hash_bytes(b"hello") == hash_bytes(b"hello")

def test_hash_distinct():
    assert hash_bytes(b"hello") != hash_bytes(b"world")

def test_has_subpath_root_with_slash():
    assert has_subpath("https://www.quora.com/") is False

def test_has_subpath_subpage():
    assert has_subpath("https://www.quora.com/Some-Question-Title") is True

def test_has_subpath_no_dot_com_slash():
    assert has_subpath("https://www.quora.com") is False

def test_has_subpath_query_string_root():
    assert has_subpath("https://www.linkedin.com/?ref=x") is True

def test_has_subpath_linkedin_profile():
    assert has_subpath("https://www.linkedin.com/in/someone/") is True

def test_is_subpage_exempt_linkedin_feed():
    assert is_subpage_exempt("https://www.linkedin.com/feed/") is True

def test_is_subpage_exempt_linkedin_feed_no_trailing_slash():
    assert is_subpage_exempt("https://www.linkedin.com/feed") is True

def test_is_subpage_exempt_linkedin_feed_with_query():
    assert is_subpage_exempt("https://www.linkedin.com/feed/?trk=nav_home") is True

def test_is_subpage_exempt_linkedin_feedback_not_exempt():
    assert is_subpage_exempt("https://www.linkedin.com/feedback/") is False

def test_is_subpage_exempt_linkedin_profile_not_exempt():
    assert is_subpage_exempt("https://www.linkedin.com/in/someone/") is False

def test_is_subpage_exempt_quora_topic():
    assert is_subpage_exempt("https://www.quora.com/topic/Artificial-Intelligence") is True

def test_is_subpage_exempt_quora_topic_nested():
    assert is_subpage_exempt("https://www.quora.com/topic/Artificial-Intelligence/answer/Someone") is True

def test_is_subpage_exempt_quora_question_not_exempt():
    assert is_subpage_exempt("https://www.quora.com/Some-Question-Title") is False

def test_is_subpage_exempt_neither_site():
    assert is_subpage_exempt("https://www.facebook.com/topic/") is False

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

def test_content_rect_to_crop_box_degenerate_height_returns_none():
    content_rect = (100, 50, 1000, 120)
    window_rect = (100, 50, 1000, 800)
    assert content_rect_to_crop_box(content_rect, window_rect) is None

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

def test_state_roundtrip():
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        path = Path(f.name)
    state = {"last_hash": "abc123", "last_sent_text": "some ocr text"}
    path.write_text(json.dumps(state), encoding="utf-8")
    loaded = json.loads(path.read_text(encoding="utf-8"))
    assert loaded == state
    path.unlink()

def test_load_state_missing_file():
    path = Path(tempfile.gettempdir()) / "nonexistent_test_screenshot_state.json"
    path.unlink(missing_ok=True)  # clean up any prior run
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
    test_detect_ignores_notepad()
    test_detect_ignores_explorer()
    test_detect_microsoft_edge()
    test_hash_deterministic()
    test_hash_distinct()
    test_has_subpath_root_with_slash()
    test_has_subpath_subpage()
    test_has_subpath_no_dot_com_slash()
    test_has_subpath_query_string_root()
    test_has_subpath_linkedin_profile()
    test_is_subpage_exempt_linkedin_feed()
    test_is_subpage_exempt_linkedin_feed_no_trailing_slash()
    test_is_subpage_exempt_linkedin_feed_with_query()
    test_is_subpage_exempt_linkedin_feedback_not_exempt()
    test_is_subpage_exempt_linkedin_profile_not_exempt()
    test_is_subpage_exempt_quora_topic()
    test_is_subpage_exempt_quora_topic_nested()
    test_is_subpage_exempt_quora_question_not_exempt()
    test_is_subpage_exempt_neither_site()
    test_percentage_fallback_rect_default()
    test_percentage_fallback_rect_nonzero_origin()
    test_content_rect_to_crop_box_inside_window()
    test_content_rect_to_crop_box_clamped()
    test_content_rect_to_crop_box_degenerate_returns_none()
    test_content_rect_to_crop_box_degenerate_height_returns_none()
    test_landmark_too_wide_true()
    test_landmark_too_wide_false()
    test_landmark_too_wide_exact_threshold_not_too_wide()
    test_landmark_too_wide_zero_doc_width()
    test_state_roundtrip()
    test_load_state_missing_file()
    print("All tests passed.")
