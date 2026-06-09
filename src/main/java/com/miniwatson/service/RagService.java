package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.VectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final OllamaService ollamaService;

    private static final int TOP_K = 2; // LLM 에 최종 전달
    private static final int FETCH_N = 20;   // rerank 후보군 (1차 검색)
    private static final String DEFAULT_NS = "default";
    private final Reranker reranker;
    public RagService(EmbeddingService embeddingService,
                      VectorIndex vectorIndex,
                      OllamaService ollamaService,
                      Map<String, Reranker> rerankers,                        // 추가
                      @Value("${rerank.strategy:llm}") String strategy) {
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.ollamaService = ollamaService;
        this.reranker = rerankers.getOrDefault(strategy, rerankers.get("llm")); // 추가
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

        List<Float> questionEmbedding = embeddingService.embed("search_query: " + question);
        List<Article> candidates = vectorIndex.search(ns, questionEmbedding, FETCH_N);
        if (candidates.isEmpty()) throw new RuntimeException("No articles ...");
        // Sub-linear retrieval via the in-memory vector index (LSH + exact fallback).
        //List<Article> topArticles = vectorIndex.search(ns, questionEmbedding, TOP_K);
        List<Article> topArticles = reranker.rerank(question, candidates, TOP_K);   // 재정렬
        if (topArticles.isEmpty()) {
            throw new RuntimeException("No articles in knowledge base for namespace '" + ns + "'.");
        }

        log.info("Retrieved {} articles: {}", topArticles.size(),
                topArticles.stream().map(Article::getTitle).collect(Collectors.toList()));

        StringBuilder context = new StringBuilder();
        for (Article a : topArticles) {
            String s = a.getSummary();
            if (s.length() > 600) s = s.substring(0, 600);   // 소스당 최대 600자
            context.append("- ").append(a.getTitle()).append(": ").append(s).append("\n");
        }
        String sources = topArticles.stream()
                .map(a -> "#" + a.getId() + " " + a.getTitle())
                .collect(Collectors.joining("; "));

        String prompt = "Use the context below. For exact numbers, trust [OCR] sections over [Vision] descriptions.\n"
                + context + "\nAnswer concisely: " + question;

        log.info("Augmented prompt length: {} chars", prompt.length());

        String answer = ollamaService.ask(prompt, model, question, sources);

        log.info("Ollama answer length: {} chars", answer != null ? answer.length() : 0);

        return new RagResult(answer, topArticles);
    }

    public record RagResult(String answer, List<Article> sources) {}
}
