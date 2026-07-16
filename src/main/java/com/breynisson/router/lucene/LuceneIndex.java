package com.breynisson.router.lucene;

import com.breynisson.router.RouterException;
import com.breynisson.router.digitalme.SearchResult;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

public class LuceneIndex {

    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_BODY = "body";

    public static void createOrUpdateIndex(String content, String source) {
        createOrUpdateIndex(content, source, source);
    }

    public static void createOrUpdateIndex(String content, String source, String name) {
        Map<String, String> properties = new HashMap<>();
        properties.put(FIELD_SOURCE, source);
        properties.put(FIELD_NAME, name);
        createOrUpdateIndex(content, properties);
    }

    public static void createOrUpdateIndex(String content, Map<String, String> properties) {
        try (Directory indexDir = FSDirectory.open(Paths.get(getIndexPath()));
             Analyzer analyzer = new StandardAnalyzer()
        ) {
            IndexWriterConfig indexWriterConfig = new IndexWriterConfig();
            indexWriterConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try (IndexWriter writer = new IndexWriter(indexDir, indexWriterConfig)) {
                String source = properties.get(FIELD_SOURCE);
                writer.deleteDocuments(new Term(FIELD_SOURCE, source));
                Document doc = new Document();
                properties.forEach((key, value) -> {
                    Field field = new StringField(key, value, Field.Store.YES);
                    doc.add(field);
                });
                TextField textField = new TextField(FIELD_BODY, content, Field.Store.YES);
                doc.add(textField);
                writer.addDocument(doc);
            }
        } catch (IOException e) {
            throw new RouterException(e);
        }
    }

    /**
     * Looks up the stored FIELD_NAME for each given source, in one query. Sources with no indexed
     * document are simply absent from the returned map. Returns an empty map (rather than throwing)
     * when the index has no documents yet, since callers may query it before anything is indexed.
     */
    public static Map<String, String> findNamesBySources(Collection<String> sources) {
        if (sources.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        try (Directory indexDir = FSDirectory.open(Paths.get(getIndexPath()));
             IndexReader reader = DirectoryReader.open(indexDir)
        ) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<BytesRef> terms = sources.stream().map(BytesRef::new).toList();
            TopDocs topDocs = searcher.search(new TermInSetQuery(FIELD_SOURCE, terms), sources.size());
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                names.put(doc.get(FIELD_SOURCE), doc.get(FIELD_NAME));
            }
        } catch (IndexNotFoundException e) {
            return Map.of();
        } catch (IOException e) {
            throw new RouterException(e);
        }
        return names;
    }

    public static List<SearchResult> find(String phrase) {
        try (Directory indexDir = FSDirectory.open(Paths.get(getIndexPath()));
             IndexReader reader = DirectoryReader.open(indexDir);
             Analyzer analyzer = new StandardAnalyzer();
        ) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser queryParser = new QueryParser(FIELD_BODY, analyzer);
            Query query = queryParser.parse(phrase);
            TopDocs topDocs = searcher.search(query, 100); // reduced from 100 for sanity

            org.apache.lucene.search.highlight.Formatter formatter = new SimpleHTMLFormatter("<mark>", "</mark>");
            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);
            highlighter.setTextFragmenter(new SimpleSpanFragmenter(scorer, 100));

            List<SearchResult> results = new ArrayList<>();
            
            // Extract query terms for frequency counting
            Set<String> queryTerms = new HashSet<>();
            query.visit(new QueryVisitor() {
                @Override
                public void consumeTerms(Query query, Term... terms) {
                    for (Term t : terms) {
                        if (FIELD_BODY.equals(t.field())) {
                            queryTerms.add(t.text().toLowerCase());
                        }
                    }
                }
            });

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                String source = doc.get(FIELD_SOURCE);
                String name = doc.get(FIELD_NAME);
                String text = doc.get(FIELD_BODY);

                TokenStream tokenStream = TokenSources.getAnyTokenStream(reader, scoreDoc.doc, FIELD_BODY, analyzer);
                String snippet = highlighter.getBestFragment(tokenStream, text);
                
                if (snippet == null || snippet.isBlank()) {
                    snippet = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                }

                // Count term frequencies
                Map<String, Integer> freqMap = new TreeMap<>();
                Terms vector = reader.getTermVector(scoreDoc.doc, FIELD_BODY);
                if (vector != null) {
                    TermsEnum termsEnum = vector.iterator();
                    BytesRef term;
                    while ((term = termsEnum.next()) != null) {
                        String termText = term.utf8ToString().toLowerCase();
                        if (queryTerms.contains(termText)) {
                            freqMap.put(termText, (int) termsEnum.totalTermFreq());
                        }
                    }
                } else {
                    // Fallback if no term vectors: simple string scan (less accurate but better than nothing)
                    String lowerText = text.toLowerCase();
                    for (String qTerm : queryTerms) {
                        int count = 0;
                        int lastIdx = 0;
                        while ((lastIdx = lowerText.indexOf(qTerm, lastIdx)) != -1) {
                            count++;
                            lastIdx += qTerm.length();
                        }
                        if (count > 0) freqMap.put(qTerm, count);
                    }
                }

                results.add(new SearchResult(source, name, snippet, (double) scoreDoc.score, freqMap));
            }
            return results;
        } catch (Exception e) {
            throw new RouterException(e);
        }
    }

    private static String indexPath = "lucene-index";

    public static void setIndexPath(String path) {
        indexPath = path;
    }

    static String getIndexPath() {
        return indexPath;
    }

    public static void deleteIndex() {
        for (File file : Objects.requireNonNull(new File(getIndexPath()).listFiles())) {
            if (file.isFile()) {
                file.delete();
            }
        }
    }
}
