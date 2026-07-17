package com.breynisson.router.digitalme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotCoverageTest {

    @Test
    void facebookAnySubpageIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.facebook.com/anything"));
    }

    @Test
    void facebookRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.facebook.com/"));
    }

    @Test
    void linkedinRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/"));
    }

    @Test
    void linkedinFeedIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/feed/"));
    }

    @Test
    void linkedinFeedWithQueryIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/feed/?trk=nav_home"));
    }

    @Test
    void linkedinProfileSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.linkedin.com/in/someone/"));
    }

    @Test
    void linkedinArticleSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.linkedin.com/pulse/some-article"));
    }

    @Test
    void quoraRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.quora.com/"));
    }

    @Test
    void quoraTopicIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.quora.com/topic/Artificial-Intelligence"));
    }

    @Test
    void quoraQuestionSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.quora.com/Some-Question-Title"));
    }

    @Test
    void untrackedDomainIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.example.com/"));
    }

    @Test
    void googleDocsDocumentIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://docs.google.com/document/d/abc123/edit"));
    }

    @Test
    void googleDocsHomepageIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://docs.google.com/"));
    }
}
