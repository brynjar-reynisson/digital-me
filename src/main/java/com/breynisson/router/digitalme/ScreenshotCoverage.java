package com.breynisson.router.digitalme;

import java.util.regex.Pattern;

public final class ScreenshotCoverage {

    private ScreenshotCoverage() {
    }

    // Mirrors scripts/screenshot-capture.py's LINKEDIN_FEED_PATTERN. Keep in sync if that changes.
    private static final Pattern LINKEDIN_FEED_PATTERN = Pattern.compile("linkedin\\.com/feed/?(?:[?#]|$)");
    // Mirrors scripts/screenshot-capture.py's QUORA_TOPIC_PATTERN. Keep in sync if that changes.
    private static final Pattern QUORA_TOPIC_PATTERN = Pattern.compile("quora\\.com/topic/");

    public static boolean isCovered(String url) {
        if (url.contains("facebook.com")) {
            return true;
        }
        if (url.contains("docs.google.com")) {
            return true;
        }
        if (url.contains("linkedin.com")) {
            return !hasSubpath(url, "linkedin.com/") || LINKEDIN_FEED_PATTERN.matcher(url).find();
        }
        if (url.contains("quora.com")) {
            return !hasSubpath(url, "quora.com/") || QUORA_TOPIC_PATTERN.matcher(url).find();
        }
        return false;
    }

    // Mirrors scripts/screenshot-capture.py's has_subpath(). Keep in sync if that changes.
    private static boolean hasSubpath(String url, String domainSlash) {
        int idx = url.indexOf(domainSlash);
        if (idx == -1) {
            return false;
        }
        return url.length() > idx + domainSlash.length();
    }
}
