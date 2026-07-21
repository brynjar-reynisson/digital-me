package com.breynisson.router;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.jdbc.TextEntryDao;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

public class FileChangeWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileChangeWatcher.class);

    private final DigitalMeStorage storage;

    public FileChangeWatcher(DigitalMeStorage storage) {
        this.storage = storage;
    }

    public void watchDirectory(String directoryPath) throws IOException {
        watchDirectory(directoryPath, TextEntryDao.findAllNameToTime());
    }

    private void watchDirectory(String directoryPath, Map<String, Instant> knownEntries) throws IOException {

        if (directoryPath.endsWith("/*")) {
            String baseDir = directoryPath.substring(0, directoryPath.length() - 2);
            watchDirectory(baseDir, knownEntries);
            try (Stream<Path> subDirs = Files.list(Paths.get(baseDir))) {
                subDirs.filter(p -> p.toFile().isDirectory())
                       .forEach(p -> {
                           try {
                               watchDirectory(p + "/*", knownEntries);
                           } catch (IOException e) {
                               log.error("Error watching directory {}", p, e);
                           }
                       });
            }
            return;
        }

        //scan files in directory
        try (Stream<Path> pathsStream = Files.list(Paths.get(directoryPath))) {
            pathsStream.forEach((path) -> {
                File file = path.toFile();
                String name = file.getName().toLowerCase();
                if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".pdf")) {
                    try {
                        String source = file.getAbsolutePath();
                        Instant knownTime = knownEntries.get(source);
                        boolean isNew = knownTime == null;
                        boolean isModified = !isNew && knownTime.getEpochSecond() < file.lastModified() / 1000;
                        if (isNew || isModified) {
                            updateFileInfo(file);
                        }
                    } catch (IOException | RouterException e) {
                        log.error("Error processing file {}", file.getAbsolutePath(), e);
                    }
                }
            });
        }
    }

    private void updateFileInfo(File file) throws IOException {
        String source = file.getAbsolutePath();
        log.info("Indexing {}", source);
        AddContentRequest req = new AddContentRequest();
        req.setSource(source);
        req.setName(file.getName());
        req.setContent(extractContent(file));
        storage.addContent(req);
    }

    private String extractContent(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }
        } else {
            // Assume text-based for .txt, .md, etc.
            return Files.readString(file.toPath());
        }
    }
}
