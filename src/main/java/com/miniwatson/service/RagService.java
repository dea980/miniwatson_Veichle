package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.security.TenantAccessChecker;
import com.miniwatson.service.HybridRetriever;
import com.miniwatson.service.llm.EmbeddingClient;
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

    private final EmbeddingClient embeddingService;
    // private final VectorIndex vectorIndex;
    private final HybridRetriever hybridRetriever;
    private final OllamaService ollamaService;
    private final TenantAccessChecker accessChecker;   // 테넌트 격리 강제

    private static final int TOP_K = 2; // LLM 에 최종 전달
    private static final int FETCH_N = 20;   // rerank 후보군 (1차 검색)
    private static final String DEFAULT_NS = "default";
    private final Map<String, Reranker> rerankers; //요청키로  고르기 위해 보관

    private final Reranker reranker; //

    @Value("${eval.overrides.enabled:false}")
    private boolean evalOverrides;

    /** 운영 오설정 방지: 오버라이드가 켜져 있으면 기동 시 크게 경고(외부가 검색 전략 변경 가능, T2). */
    @jakarta.annotation.PostConstruct
    void warnIfEvalOverridesOn() {
        if (evalOverrides)
            log.warn("[SECURITY] eval.overrides.enabled=true — 외부 요청이 rerank/hybrid 전략을 바꿀 수 있음. 평가 전용, 운영에선 false.");
    }
    public RagService(EmbeddingClient embeddingService,
                      HybridRetriever hybridRetriever,
                      OllamaService ollamaService,
                      Map<String, Reranker> rerankers,                        // 추가
                      TenantAccessChecker accessChecker,
                      @Value("${rerank.strategy:mmr}") String strategy) {
        this.embeddingService = embeddingService;
        this.hybridRetriever = hybridRetriever;
        this.ollamaService = ollamaService;
        this.rerankers = rerankers;
        this.accessChecker = accessChecker;
        this.reranker = rerankers.getOrDefault(strategy, rerankers.get("mmr")); // 알 수 없는 키면 mmr 폴백 (eval 최선)
    }

    /** Backward-compatible entry point: default namespace, default chat model. */
    public RagResult ask(String question) throws IOException {
        return ask(question, DEFAULT_NS, null, null, null);
    }

    public RagResult ask(String question, String namespace, String model) throws IOException{
        return ask(question, namespace, model, null,null);
    }

    /**
     * RAG over a single tenant namespace, optionally with a caller-chosen chat model.
     *
     * @param question  user question
     * @param namespace tenant / collection to retrieve from (null/blank → "default")
     * @param model     chat model override (null/blank → configured default)
     */
    public RagResult ask(String question, String namespace, String model, String rerankOverride, Boolean hybridOverride) throws IOException {
        String ns = (namespace == null || namespace.isBlank()) ? DEFAULT_NS : namespace;
        accessChecker.check(ns);   // 격리 강제: 이 호출자가 ns 접근 가능한지 (보안 off면 통과)
        // 평가를 위한 게이트
        Reranker rr = reranker;
        Boolean hy = null;
        if (evalOverrides){
            if (rerankOverride != null && rerankers.containsKey(rerankOverride)){
                rr = rerankers.get(rerankOverride);
            }
            hy = hybridOverride;
        }

        log.info("RAG question (ns={}, model={}): {}", ns, model == null ? "default" : model, question);

        List<Float> questionEmbedding = embeddingService.embedQuery(question);
        List<Article> candidates = hybridRetriever.search(ns, questionEmbedding, question, FETCH_N, hy);
        if (candidates.isEmpty()) throw new RuntimeException("No articles in knowledge base for namespace '" + ns + "'.");
        // Sub-linear retrieval via the in-memory vector index (LSH + exact fallback).
        //List<Article> topArticles = vectorIndex.search(ns, questionEmbedding, TOP_K);
        // rerank 실패(예: llm rerank의 Ollama 타임아웃)해도 검색은 살린다 — 벡터/하이브리드 후보 top-K로 fallback.
        List<Article> topArticles;
        try {
            topArticles = rr.rerank(question, candidates, TOP_K);   // 재정렬
        } catch (Exception e) {
            log.warn("[rerank] 실패 — 후보 top-{}로 fallback: {}", TOP_K, e.getMessage());
            topArticles = candidates.size() > TOP_K ? candidates.subList(0, TOP_K) : candidates;
        }

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

        String prompt = "아래 컨텍스트(자동차 매뉴얼 발췌)를 근거로 사용자 질문에 답하라.\n"
                + "반드시 한국어로 간결하게 답하라. 컨텍스트가 영어여도 한국어로 번역해서 답한다.\n"
                + "컨텍스트에 없는 내용은 지어내지 말 것. 정확한 숫자는 [OCR] 섹션을 신뢰한다.\n\n"
                + "[컨텍스트]\n" + context + "\n[질문] " + question + "\n[답변(한국어)]";

        log.info("Augmented prompt length: {} chars", prompt.length());

        String answer = ollamaService.ask(prompt, model, question, sources);
        Long logId = ollamaService.lastQueryLogId();

        log.info("Ollama answer length: {} chars", answer != null ? answer.length() : 0);

        return new RagResult(answer, topArticles,logId);
    }

    public record RagResult(String answer, List<Article> sources, Long logId) {}
}
