package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.KeywordIndex;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.data.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** 벡터(의미) + 키워드(BM25) 후보를 RRF로 융합. */
@Service
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final VectorStore vectorIndex;
    private final KeywordIndex keywordIndex;
    private final boolean hybridEnabled;

    public HybridRetriever(VectorStore vectorIndex,
                           KeywordIndex keywordIndex,
                           @Value("${retrieval.hybrid.enabled:true}") boolean hybridEnabled) {
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.hybridEnabled = hybridEnabled;
    }
    /** 기본 설정 사용. */
    public List<Article> search(String ns, List<Float>queryEmbedding, String queryText, int topN){
        return search(ns, queryEmbedding, queryText, topN, null);
    }
    /** hybridOverride != null 이면 그 값으로 (EVAL-ONLY 경로에서 사용). */
    public List<Article> search(String ns, List<Float> queryEmbedding, String queryText, int topN, Boolean hybridOverride) {

        boolean useHybrid = (hybridOverride != null) ? hybridOverride : hybridEnabled;
        List<Article> vec = vectorIndex.search(ns, queryEmbedding, topN);
        if (!hybridEnabled) return vec;

        List<Article> kw = keywordIndex.search(ns, queryText, topN);

        // RRF: 각 리스트 순위로 점수 누적
        Map<Long, Double> score = new HashMap<>();
        Map<Long, Article> byId = new HashMap<>();
        rrf(vec, score, byId);
        rrf(kw, score, byId);

        return score.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void rrf(List<Article> ranked, Map<Long, Double> score, Map<Long, Article> byId) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            Article a = ranked.get(rank);
            byId.put(a.getId(), a);
            score.merge(a.getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }
}