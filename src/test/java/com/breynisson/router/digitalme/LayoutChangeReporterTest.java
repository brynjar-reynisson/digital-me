package com.breynisson.router.digitalme;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutChangeReporterTest {

    @TempDir
    Path dataDir;

    private LayoutChangeReporter reporter;

    @BeforeEach
    void setUp() {
        reporter = new LayoutChangeReporter(dataDir.toString());
    }

    private List<Path> errorFiles() throws IOException {
        Path errorsDir = dataDir.resolve("errors");
        try (Stream<Path> stream = Files.list(errorsDir)) {
            return stream.toList();
        }
    }

    @Test
    void firstReportWritesFileWithExpectedNameAndContent() throws IOException {
        reporter.report("testsite", "some layout-change message");

        List<Path> files = errorFiles();
        assertEquals(1, files.size());
        String fileName = files.get(0).getFileName().toString();
        assertTrue(fileName.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-testsite\\.txt"));
        assertEquals("some layout-change message", Files.readString(files.get(0)));
    }

    @Test
    void secondReportForSameSiteSameMonthIsNoOp() throws IOException {
        reporter.report("testsite", "first message");
        reporter.report("testsite", "second message");

        List<Path> files = errorFiles();
        assertEquals(1, files.size());
        assertEquals("first message", Files.readString(files.get(0)));
    }

    @Test
    void reportsForDifferentSitesBothWriteFiles() throws IOException {
        reporter.report("siteone", "message one");
        reporter.report("sitetwo", "message two");

        List<Path> files = errorFiles();
        assertEquals(2, files.size());
    }
}
