package com.miniwatson.data;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** 인메모리 BM25 역색인. VectorIndex의 어휘(lexical) 짝. namespace별 분리. */
@Component
public class KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndex.class);
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final ArticleRepository store;
    private final Map<String, NsIndex> namespaces = new HashMap<>();

    public KeywordIndex(ArticleRepository store) {
        this.store = store;
    }

    private static class NsIndex {
        List<Article> docs = new ArrayList<>();
        List<List<String>> docTokens = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        double avgdl = 0;
    }

    @PostConstruct
    public synchronized void hydrate() {
        try {
            rebuild(store.loadAll());
            // 한글 토크나이저 망가짐 같은 다음 회귀를 즉시 감지하려고 어휘 통계도 같이 로그.
            for (var e : namespaces.entrySet()) {
                NsIndex idx = e.getValue();
                log.info("[KeywordIndex] ns='{}' docs={} vocab={} avgTokens={}",
                        e.getKey(), idx.docs.size(), idx.df.size(), String.format("%.1f", idx.avgdl));
            }
            log.info("[KeywordIndex] hydrated {} namespace(s)", namespaces.size());
        } catch (IOException e) {
            log.warn("[KeywordIndex] hydrate failed: {}", e.getMessage());
        }
    }

    public synchronized void rebuild(List<Article> articles) {
        namespaces.clear();
        for (Article a : articles) add(a);
    }

    public synchronized void add(Article a) {
        String ns = nsKey(a.getNamespace());
        NsIndex idx = namespaces.computeIfAbsent(ns, k -> new NsIndex());
        List<String> tokens = tokenize(a.getSummary());
        idx.docs.add(a);
        idx.docTokens.add(tokens);
        for (String t : new HashSet<>(tokens)) idx.df.merge(t, 1, Integer::sum);
        idx.avgdl = idx.docTokens.stream().mapToInt(List::size).average().orElse(0);
    }

    public synchronized List<Article> search(String namespace, String query, int topK) {
        NsIndex idx = namespaces.get(nsKey(namespace));
        if (idx == null || idx.docs.isEmpty()) return List.of();

        List<String> qTerms = tokenize(query);
        int N = idx.docs.size();
        double[] scores = new double[N];

        for (int i = 0; i < N; i++) {
            List<String> doc = idx.docTokens.get(i);
            Map<String, Long> tf = termFreq(doc);
            double s = 0;
            for (String t : qTerms) {
                int dfi = idx.df.getOrDefault(t, 0);
                if (dfi == 0) continue;
                long f = tf.getOrDefault(t, 0L);
                if (f == 0) continue;
                double idf = Math.log(1 + (N - dfi + 0.5) / (dfi + 0.5));
                double denom = f + K1 * (1 - B + B * doc.size() / idx.avgdl);
                s += idf * (f * (K1 + 1)) / denom;
            }
            scores[i] = s;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < N; i++) if (scores[i] > 0) order.add(i);
        order.sort((x, y) -> Double.compare(scores[y], scores[x]));

        List<Article> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, order.size()); i++) out.add(idx.docs.get(order.get(i)));
        return out;
    }

    // 토큰 경계 = 한글/영숫자 외 모든 문자. 이전 [^a-z0-9]+ 는 한글 전부를 split해 BM25가 한국어에서 0점이었음.
    // 한국어는 교착어라 어절 끝 조사·어미("타이어를" vs "타이어")가 매칭을 깨므로,
    // 한글 어절은 어절 자체 + char-2gram을 함께 색인해 부분일치를 회복한다. 색인 크기 ~2배.
    private static final java.util.regex.Pattern SEP =
            java.util.regex.Pattern.compile("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+");

    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : SEP.split(text.toLowerCase())) {
            if (t.isBlank()) continue;
            out.add(t);
            // 한글 포함 어절이면 char-2gram도 추가(조사·어미 무시 효과). 쿼리·문서 모두 동일 함수라 일관.
            if (t.length() >= 2 && containsHangul(t)) {
                for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
            }
        }
        return out;
    }

    private static boolean containsHangul(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return true;
        }
        return false;
    }

    private Map<String, Long> termFreq(List<String> tokens) {
        Map<String, Long> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1L, Long::sum);
        return tf;
    }

    private String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns;
    }
}