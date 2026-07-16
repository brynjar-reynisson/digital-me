package com.breynisson.router.digitalme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.stream.Stream;

public class LayoutChangeReporter {

    private static final Logger log = LoggerFactory.getLogger(LayoutChangeReporter.class);

    private final Path errorsDir;

    public LayoutChangeReporter(String dataDir) {
        this.errorsDir = Paths.get(dataDir, "errors");
    }

    public void report(String siteName, String message) {
        try {
            Files.createDirectories(errorsDir);
            if (alreadyReportedThisMonth(siteName)) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            String fileName = String.format("%04d-%02d-%02d-%02d-%02d-%02d-%s.txt",
                    now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                    now.getHour(), now.getMinute(), now.getSecond(), siteName);
            Files.writeString(errorsDir.resolve(fileName), message);
        } catch (IOException e) {
            log.error("Error writing layout-change report for {}", siteName, e);
        }
    }

    private boolean alreadyReportedThisMonth(String siteName) throws IOException {
        YearMonth currentMonth = YearMonth.now();
        String prefix = String.format("%04d-%02d-", currentMonth.getYear(), currentMonth.getMonthValue());
        String suffix = "-" + siteName + ".txt";
        try (Stream<Path> files = Files.list(errorsDir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(suffix);
            });
        }
    }
}
