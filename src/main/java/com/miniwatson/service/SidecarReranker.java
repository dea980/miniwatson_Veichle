package com.miniwatson.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniwatson.data.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 크로스인코더 리랭커 — Python 사이드카(reranker_sidecar.py) 경유.
 *
 * 왜 사이드카: 자바 DJL PyTorch 크로스인코더(CrossEncoderReranker)가 M2 맥에서 네이티브 폴백이라
 *   실측이 안 됨. sentence-transformers는 맥 CPU에서 확실히 동작 → 사이드카로 분리해 실측 가능케.
 *   서빙(Java)·ML(Python) 분리 원칙과 whisper/tts 사이드카 패턴을 그대로 따른다.
 *
 * 사이드카 미기동/오류면 1차 검색 순서 top-K로 graceful fallback(검색은 항상 살린다).
 */
@Component("cross-sidecar")
public class SidecarReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(SidecarReranker.class);
    private static final int MAX_PASSAGE = 512;   // 사이드카 max_length와 정합

    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public SidecarReranker(@Value("${rerank.sidecar.url:http://127.0.0.1:8002}") String url) {
        this.url = url;
    }

    @Override
    public List<Article> rerank(String question, List<Article> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        try {
            List<String> passages = candidates.stream()
                    .map(a -> {
                        String s = a.getSummary() == null ? "" : a.getSummary();
                        return s.length() > MAX_PASSAGE ? s.substring(0, MAX_PASSAGE) : s;
                    })
                    .collect(Collectors.toList());
            String body = mapper.writeValueAsString(Map.of("query", question, "passages", passages));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/rerank"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[cross-sidecar] HTTP {} — fallback", resp.statusCode());
                return candidates.subList(0, topK);
            }
            Map<?, ?> parsed = mapper.readValue(resp.body(), Map.class);
            List<?> scores = (List<?>) parsed.get("scores");
            if (scores == null || scores.size() != candidates.size()) {
                log.warn("[cross-sidecar] 점수 개수 불일치 — fallback");
                return candidates.subList(0, topK);
            }
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                scored.add(new Scored(candidates.get(i), ((Number) scores.get(i)).doubleValue()));
            }
            return scored.stream()
                    .sorted(Comparator.comparingDouble((Scored s) -> -s.score))
                    .limit(topK)
                    .map(s -> s.article)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[cross-sidecar] 호출 실패 — fallback: {}", e.getMessage());
            return candidates.subList(0, topK);
        }
    }

    private record Scored(Article article, double score) {}
}
