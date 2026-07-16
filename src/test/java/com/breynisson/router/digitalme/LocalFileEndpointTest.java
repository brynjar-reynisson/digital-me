package com.breynisson.router.digitalme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileEndpointTest {

    @Test
    void publicDomainLocalFileUrlIsDetected() {
        assertTrue(LocalFileEndpoint.isLocalFileUrl(
                "https://digitalme.breynisson.org/localFile?filePath=C%3A%2FUsers%2FLenovo%2Fnote.md"));
    }

    @Test
    void localhostLocalFileUrlIsDetected() {
        assertTrue(LocalFileEndpoint.isLocalFileUrl(
                "http://localhost:8080/localFile?filePath=C%3A%2FUsers%2FLenovo%2Fnote.md"));
    }

    @Test
    void unrelatedExternalUrlIsNotDetected() {
        assertFalse(LocalFileEndpoint.isLocalFileUrl("https://www.example.com/article"));
    }

    @Test
    void rawFilePathIsNotDetected() {
        assertFalse(LocalFileEndpoint.isLocalFileUrl("C:\\Users\\Lenovo\\Documents\\note.md"));
    }

    @Test
    void urlContainingLocalFileAsUnrelatedTextIsNotDetected() {
        assertFalse(LocalFileEndpoint.isLocalFileUrl("https://example.com/blog/my-localFile-review"));
    }
}
