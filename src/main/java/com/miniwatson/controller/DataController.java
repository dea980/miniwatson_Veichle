package com.miniwatson.controller;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.service.IngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final String DEFAULT_NS = "default";

    private final IngestionService ingestionService;
    private final ArticleParquetStore articleStore;
    private final VectorIndex vectorIndex;

    public DataController(IngestionService ingestionService,
                          ArticleParquetStore articleStore,
                          VectorIndex vectorIndex) {
        this.ingestionService = ingestionService;
        this.articleStore = articleStore;
        this.vectorIndex = vectorIndex;
    }

    /**
     * Single ingest.
     * POST /api/data/ingest?title=RAG&namespace=acme
     */
    @PostMapping("/ingest")
    public Article ingest(@RequestParam String title,
                          @RequestParam(required = false, defaultValue = DEFAULT_NS) String namespace)
            throws IOException {
        return ingestionService.ingest(title, namespace);
    }

    /**
     * Batch ingest.
     * POST /api/data/ingest-batch?namespace=acme
     * { "topics": ["RAG", "Vector database", "Embedding"] }
     */
    @PostMapping("/ingest-batch")
    public Map<String, Object> ingestBatch(
            @RequestBody Map<String, List<String>> body,
            @RequestParam(required = false, defaultValue = DEFAULT_NS) String namespace) throws IOException {
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
                ingested.add(ingestionService.ingest(title, namespace));
            } catch (Exception e) {
                failed.add(Map.of(
                        "title", title,
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                ));
            }
        }

        return Map.of(
                "success", true,
                "namespace", namespace,
                "ingested", ingested.size(),
                "failed", failed.size(),
                "articles", ingested,
                "errors", failed
        );
    }

    /**
     * Article 목록. namespace 미지정 시 전체 반환.
     * GET /api/data/articles            → all
     * GET /api/data/articles?namespace=acme → tenant only
     */
    @GetMapping("/articles")
    public List<Article> getAllArticles(@RequestParam(required = false) String namespace) throws IOException {
        List<Article> all = articleStore.loadAll();
        if (namespace == null || namespace.isBlank()) {
            return all;
        }
        List<Article> filtered = new ArrayList<>();
        for (Article a : all) {
            String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
            if (ns.equals(namespace)) {
                filtered.add(a);
            }
        }
        return filtered;
    }

    /**
     * Article 개수 (dash board 용). namespace 옵션.
     * GET /api/data/count[?namespace=acme]
     */
    @GetMapping("/count")
    public Map<String, Integer> getCount(@RequestParam(required = false) String namespace) throws IOException {
        return Map.of("count", getAllArticles(namespace).size());
    }

    /**
     * 벡터 인덱스 상태 (mode, hyperplanes, per-namespace vectors/buckets).
     * GET /api/data/index/stats
     */
    @GetMapping("/index/stats")
    public Map<String, Object> indexStats() {
        return vectorIndex.stats();
    }

    @PostMapping("/ingest-file")
    public Article ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "namespace", required = false, defaultValue = "default") String namespace)
            throws IOException {
                return ingestionService.ingestText(file, namespace);
    }
    @DeleteMapping("/articles/{id}")
    public Map<String, Object> deleteArticle(@PathVariable long id) throws IOException {
        boolean removed = articleStore.deleteById(id);
        if (removed) {
            vectorIndex.rebuild(articleStore.loadAll());  // 인메모리 인덱스 동기화 (중요!)
        }
        return Map.of("deleted", removed, "id", id);
    }

}
