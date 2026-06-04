package com.miniwatson.controller;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.service.IngestionService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private final IngestionService ingestionService;
    private final ArticleParquetStore articleStore;

    public DataController(IngestionService ingestionService, ArticleParquetStore articleStore) {
        this.ingestionService = ingestionService;
        this.articleStore = articleStore;
    }

    /**
     * Single ingest — query param 방식 (기존)
     * POST /api/data/ingest?title=RAG
     */
    @PostMapping("/ingest")
    public Article ingest(@RequestParam String title) throws IOException {
        return ingestionService.ingest(title);
    }

    /**
     * Batch ingest — JSON body 방식 (신규)
     * POST /api/data/ingest-batch
     * { "topics": ["RAG", "Vector database", "Embedding"] }
     */
    @PostMapping("/ingest-batch")
    public Map<String, Object> ingestBatch(@RequestBody Map<String, List<String>> body) throws IOException {
        List<String> topics = body.get("topics");

        if (topics == null || topics.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Request body must contain non-empty 'topics' array"
            );
        }

        List<Article> ingested = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (String title : topics) {
            try {
                ingested.add(ingestionService.ingest(title));
            } catch (Exception e) {
                failed.add(Map.of(
                        "title", title,
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                ));
            }
        }

        return Map.of(
                "success", true,
                "ingested", ingested.size(),
                "failed", failed.size(),
                "articles", ingested,
                "errors", failed
        );
    }

    /**
     * 전체 article 목록
     * GET /api/data/articles
     */
    @GetMapping("/articles")
    public List<Article> getAllArticles() throws IOException {
        return articleStore.loadAll();
    }

    /**
     * Article 개수만 빠르게 확인 (dashboard용)
     * GET /api/data/count
     */
    @GetMapping("/count")
    public Map<String, Integer> getCount() throws IOException {
        return Map.of("count", articleStore.loadAll().size());
    }
}