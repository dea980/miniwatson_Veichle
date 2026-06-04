package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleStore;
import com.miniwatson.data.ArticleParquetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingService embeddingService;
//    private final ArticleStore articleStore;
    private final ArticleParquetStore articleStore;
    private final OllamaService ollamaService;

    private static final int TOP_K = 2;

//    public RagService(EmbeddingService embeddingService,
//                      ArticleStore articleStore,
//                      OllamaService ollamaService) {
//        this.embeddingService = embeddingService;
//        this.articleStore = articleStore;
//        this.ollamaService = ollamaService;
//    }
    public RagService(EmbeddingService embeddingService,
                      ArticleParquetStore articleStore,
                      OllamaService ollamaService) {
        this.embeddingService = embeddingService;
        this.articleStore = articleStore;
        this.ollamaService = ollamaService;
    }

    public RagResult ask(String question) throws IOException {
        log.info("RAG question: {}", question);

        List<Float> questionEmbedding = embeddingService.embed(question);
        List<Article> articles = articleStore.loadAll();

        if (articles.isEmpty()) {
            throw new RuntimeException("No articles in knowledge base.");
        }

        List<Article> topArticles = articles.stream()
                .filter(a -> a.getEmbedding() != null && !a.getEmbedding().isEmpty())
                .sorted(Comparator.comparingDouble(a ->
                        -cosineSimilarity(questionEmbedding, a.getEmbedding())
                ))
                .limit(TOP_K)
                .collect(Collectors.toList());

        log.info("Retrieved {} articles: {}", topArticles.size(),
                topArticles.stream().map(Article::getTitle).collect(Collectors.toList()));

        // 간단한 prompt (모델이 헷갈리지 않게)
        StringBuilder context = new StringBuilder();
        for (Article a : topArticles) {
            context.append("- ").append(a.getTitle()).append(": ").append(a.getSummary()).append("\n");
        }

        String prompt = "Based on this context:\n" + context +
                "\nAnswer the question concisely: " + question;

        log.info("Augmented prompt length: {} chars", prompt.length());

        String answer = ollamaService.ask(prompt);

        log.info("Ollama answer length: {} chars", answer != null ? answer.length() : 0);

        return new RagResult(answer, topArticles);
    }

    private double cosineSimilarity(List<Float> v1, List<Float> v2) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
            normA += Math.pow(v1.get(i), 2);
            normB += Math.pow(v2.get(i), 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record RagResult(String answer, List<Article> sources) {}
}