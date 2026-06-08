package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.VectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final OllamaService ollamaService;

    private static final int TOP_K = 2;
    private static final String DEFAULT_NS = "default";

    public RagService(EmbeddingService embeddingService,
                      VectorIndex vectorIndex,
                      OllamaService ollamaService) {
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.ollamaService = ollamaService;
    }

    /** Backward-compatible entry point: default namespace, default chat model. */
    public RagResult ask(String question) throws IOException {
        return ask(question, DEFAULT_NS, null);
    }

    /**
     * RAG over a single tenant namespace, optionally with a caller-chosen chat model.
     *
     * @param question  user question
     * @param namespace tenant / collection to retrieve from (null/blank → "default")
     * @param model     chat model override (null/blank → configured default)
     */
    public RagResult ask(String question, String namespace, String model) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        log.info("RAG question (ns={}, model={}): {}", ns, model == null ? "default" : model, question);

        List<Float> questionEmbedding = embeddingService.embed(question);

        // Sub-linear retrieval via the in-memory vector index (LSH + exact fallback).
        List<Article> topArticles = vectorIndex.search(ns, questionEmbedding, TOP_K);

        if (topArticles.isEmpty()) {
            throw new RuntimeException("No articles in knowledge base for namespace '" + ns + "'.");
        }

        log.info("Retrieved {} articles: {}", topArticles.size(),
                topArticles.stream().map(Article::getTitle).collect(Collectors.toList()));

        StringBuilder context = new StringBuilder();
        for (Article a : topArticles) {
            context.append("- ").append(a.getTitle()).append(": ").append(a.getSummary()).append("\n");
        }

        String prompt = "Use the context below. For exact numbers, trust [OCR] sections over [Vision] descriptions.\n"
                + context + "\nAnswer concisely: " + question;

        log.info("Augmented prompt length: {} chars", prompt.length());

        String answer = ollamaService.ask(prompt, model);

        log.info("Ollama answer length: {} chars", answer != null ? answer.length() : 0);

        return new RagResult(answer, topArticles);
    }

    public record RagResult(String answer, List<Article> sources) {}
}
