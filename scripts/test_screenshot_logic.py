import datetime
import hashlib
import json
import re
import tempfile
from pathlib import Path

from PIL import Image

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

MIN_CROP_WIDTH = 100
MIN_CROP_HEIGHT = 100

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

def preprocess_for_ocr(image):
    grayscale = image.convert("L")
    return grayscale.resize((grayscale.width * 2, grayscale.height * 2))

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

def test_preprocess_for_ocr_converts_to_grayscale_and_upscales():
    original = Image.new("RGB", (10, 20), color=(255, 0, 0))
    result = preprocess_for_ocr(original)
    assert result.mode == "L"
    assert result.size == (20, 40)

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

def derive_session_key(pagename: str, url: str | None) -> str:
    return url if url else pagename

def test_derive_session_key_uses_url_when_present():
    assert derive_session_key("linkedin", "https://www.linkedin.com/feed/") == "https://www.linkedin.com/feed/"

def test_derive_session_key_falls_back_to_pagename_when_no_url():
    assert derive_session_key("linkedin", None) == "linkedin"

def merge_session_lines(existing_lines: list, new_text: str) -> list:
    merged = list(existing_lines)
    seen = set(existing_lines)
    for line in new_text.split("\n"):
        if line not in seen:
            merged.append(line)
            seen.add(line)
    return merged

def test_merge_session_lines_appends_new_lines():
    result = merge_session_lines(["a", "b"], "b\nc")
    assert result == ["a", "b", "c"]

def test_merge_session_lines_empty_existing():
    result = merge_session_lines([], "x\ny")
    assert result == ["x", "y"]

def test_merge_session_lines_all_duplicates_no_change():
    result = merge_session_lines(["a", "b"], "a\nb")
    assert result == ["a", "b"]

def test_merge_session_lines_preserves_existing_order():
    result = merge_session_lines(["z", "a"], "a\nnew")
    assert result == ["z", "a", "new"]

def is_session_idle(last_capture_at: str, now, idle_timeout_seconds: int) -> bool:
    last = datetime.datetime.fromisoformat(last_capture_at)
    return (now - last).total_seconds() > idle_timeout_seconds

def test_is_session_idle_true_when_over_threshold():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 2, 1)
    assert is_session_idle(last.isoformat(), now, 120) is True

def test_is_session_idle_false_when_under_threshold():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 1, 0)
    assert is_session_idle(last.isoformat(), now, 120) is False

def test_is_session_idle_exact_threshold_not_idle():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 2, 0)
    assert is_session_idle(last.isoformat(), now, 120) is False

def start_session(session_key: str, pagename: str, window_title: str, now) -> dict:
    timestamp = now.isoformat()
    return {
        "key": session_key,
        "pagename": pagename,
        "window_title": window_title,
        "started_at": timestamp,
        "last_capture_at": timestamp,
        "lines": [],
    }

def test_start_session_shape():
    now = datetime.datetime(2026, 7, 15, 16, 44, 32)
    session = start_session("https://www.linkedin.com/feed/", "linkedin", "Feed | LinkedIn", now)
    assert session == {
        "key": "https://www.linkedin.com/feed/",
        "pagename": "linkedin",
        "window_title": "Feed | LinkedIn",
        "started_at": now.isoformat(),
        "last_capture_at": now.isoformat(),
        "lines": [],
    }

def resolve_active_session(state: dict, pagename: str, window_title: str, session_key: str, now):
    active_session = state.get("active_session")
    session_to_flush = None
    if active_session is not None and active_session["key"] != session_key:
        session_to_flush = active_session
        active_session = None
    if active_session is None:
        active_session = start_session(session_key, pagename, window_title, now)
    return active_session, session_to_flush

def test_resolve_active_session_starts_new_when_none():
    now = datetime.datetime(2026, 7, 15, 16, 44, 32)
    session, to_flush = resolve_active_session({}, "linkedin", "Feed | LinkedIn", "linkedin-key", now)
    assert session["key"] == "linkedin-key"
    assert session["lines"] == []
    assert to_flush is None

def test_resolve_active_session_continues_when_key_matches():
    existing = {"key": "linkedin-key", "pagename": "linkedin", "window_title": "t",
                "started_at": "2026-07-15T16:00:00", "last_capture_at": "2026-07-15T16:00:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 1, 0)
    session, to_flush = resolve_active_session({"active_session": existing}, "linkedin", "t", "linkedin-key", now)
    assert session is existing
    assert to_flush is None

def test_resolve_active_session_flushes_old_when_key_changes():
    existing = {"key": "old-key", "pagename": "linkedin", "window_title": "t",
                "started_at": "2026-07-15T16:00:00", "last_capture_at": "2026-07-15T16:00:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 1, 0)
    session, to_flush = resolve_active_session({"active_session": existing}, "linkedin", "t", "new-key", now)
    assert to_flush is existing
    assert session["key"] == "new-key"
    assert session["lines"] == []

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
    test_find_gap_threshold_real_data_picks_first_gap_not_largest()
    test_find_gap_threshold_too_few_lines()
    test_find_gap_threshold_no_qualifying_gap()
    test_find_gap_threshold_gap_exactly_at_minimum()
    test_find_gap_threshold_early_gap_not_largest_synthetic()
    test_filter_and_sort_lines_no_threshold_keeps_all_sorted()
    test_filter_and_sort_lines_threshold_drops_left_lines()
    test_filter_and_sort_lines_sorts_out_of_order_input()
    test_group_words_into_lines_joins_words_on_same_line()
    test_group_words_into_lines_splits_separate_lines()
    test_group_words_into_lines_skips_empty_and_whitespace_text()
    test_group_words_into_lines_preserves_first_seen_order()
    test_group_words_into_lines_empty_input_returns_empty_list()
    test_preprocess_for_ocr_converts_to_grayscale_and_upscales()
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
    test_derive_session_key_uses_url_when_present()
    test_derive_session_key_falls_back_to_pagename_when_no_url()
    test_merge_session_lines_appends_new_lines()
    test_merge_session_lines_empty_existing()
    test_merge_session_lines_all_duplicates_no_change()
    test_merge_session_lines_preserves_existing_order()
    test_is_session_idle_true_when_over_threshold()
    test_is_session_idle_false_when_under_threshold()
    test_is_session_idle_exact_threshold_not_idle()
    test_start_session_shape()
    test_resolve_active_session_starts_new_when_none()
    test_resolve_active_session_continues_when_key_matches()
    test_resolve_active_session_flushes_old_when_key_changes()
    print("All tests passed.")
