package com.miniwatson.controller;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleStore;
import com.miniwatson.service.IngestionService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/data")
public class DataController {
    private final IngestionService ingestionService;
    private final ArticleStore articleStore;

    public DataController(IngestionService ingestionService, ArticleStore articleStore) {
        this.ingestionService = ingestionService;
        this.articleStore = articleStore;
    }

    @PostMapping("/ingest")
    public Article ingest(@RequestParam String title) throws IOException {
        return ingestionService.ingest(title);
    }

    @GetMapping("/articles")
    public List<Article> getAllArticles() throws IOException {
        return articleStore.loadAll();
    }
}
