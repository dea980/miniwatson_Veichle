package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.governance.DocumentCatalogRepository;
import com.miniwatson.reports.GeneratedReport;
import com.miniwatson.reports.GeneratedReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 답변 캐시 — compute-once + 영속(GeneratedReport type="ASK").
 *
 * 왜(설계):
 *  - 같은 (질문·네임스페이스·모델·차종·연식)이면 LLM 재호출 없이 즉시 반환 → /ask p95 57s를 히트 시 수십 ms로.
 *  - **무효화(정합성)**: 캐시 키에 KB 버전(=문서 수 catalogRepo.count())을 섞는다. 적재가 늘면 문서 수↑ →
 *    키가 바뀌어 자동으로 캐시 미스 → 재생성. "캐시를 KB 상태에 종속"시켜 낡은 답을 막는다.
 *  - **관심사 분리**: RagService는 순수(검색+LLM)하게 두고, 캐시는 이 래퍼가 담당. 컨트롤러만 이걸 호출.
 *  - **임베딩 저장 금지**: 경량 DTO(title·summary·url)만 저장. (Article.embedding은 직렬화서 빠지지만 명시적으로.)
 */
@Service
public class RagCacheService {

    private static final Logger log = LoggerFactory.getLogger(RagCacheService.class);

    private final RagService rag;
    private final GeneratedReportRepository reportRepo;
    private final DocumentCatalogRepository catalogRepo;   // KB 버전 = 문서 수(모드 무관 JPA)
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public RagCacheService(RagService rag, GeneratedReportRepository reportRepo,
                           DocumentCatalogRepository catalogRepo) {
        this.rag = rag;
        this.reportRepo = reportRepo;
        this.catalogRepo = catalogRepo;
    }

    /** 경량 캐시 페이로드 — 임베딩은 절대 담지 않는다(용량·불필요). */
    public record Src(String title, String summary, String url) {}
    public record CachedAnswer(String answer, List<Src> sources) {}

    /**
     * 캐시 우선 RAG 질의. 히트면 즉시, 미스면 1회 생성 후 저장.
     * @param force true면 캐시 무시하고 재생성(디버그/갱신용).
     */
    public RagService.RagResult askCached(
            String question, String namespace, String model, String title,
            String car, Integer year, String lang, String powertrain, boolean force) throws IOException {

        // pre-가드: 빈/무의미 질문(예: "", " ", "?")이면 LLM 호출 없이 즉시 안내(비용·UX). 소스 없음.
        if (isBlankQuestion(question)) {
            return new RagService.RagResult("질문을 입력해 주세요.", List.of(), null);
        }

        long kbVersion = catalogRepo.count();
        String key = sha256(String.join("|",
                nz(question), nz(namespace), nz(model), nz(car),
                year == null ? "" : year.toString(), Long.toString(kbVersion)));

        // 1) 조회 — 히트면 LLM 호출 0
        var hit = reportRepo.findFirstByReportTypeAndReportKeyOrderByCreatedAtDesc("ASK", key);
        if (!force && hit.isPresent()) {
            try {
                CachedAnswer dto = mapper.readValue(hit.get().getContentJson(), CachedAnswer.class);
                log.info("[ask-cache] HIT key={}…", key.substring(0, 8));
                return postProcess(new RagService.RagResult(dto.answer(), toArticles(dto.sources()), null));
            } catch (Exception e) {
                log.warn("[ask-cache] 캐시 파싱 실패 — 재생성: {}", e.getMessage());
            }
        }

        // 2) 미스 → 원본 RAG 1회 실행(느린 부분)
        RagService.RagResult r = rag.ask(question, namespace, model, null, null,
                title, car, year, lang, powertrain);

        // 3) 경량 페이로드로 저장(임베딩 제외). 실패해도 응답엔 영향 없음.
        try {
            List<Src> srcs = new ArrayList<>();
            for (Article a : r.sources()) {
                srcs.add(new Src(a.getTitle(), trim(a.getSummary(), 600), a.getUrl()));
            }
            GeneratedReport gr = hit.orElseGet(GeneratedReport::new);
            gr.setReportType("ASK");
            gr.setReportKey(key);
            gr.setModel(model);
            gr.setContentJson(mapper.writeValueAsString(new CachedAnswer(r.answer(), srcs)));
            gr.setCreatedAt(LocalDateTime.now());
            reportRepo.save(gr);
            log.info("[ask-cache] MISS→저장 key={}… (kbVer={})", key.substring(0, 8), kbVersion);
        } catch (Exception e) {
            log.warn("[ask-cache] 캐시 적재 실패: {}", e.getMessage());
        }
        return postProcess(r);   // post-가드: non-answer면 소스 제거
    }

    // ───────── 응답 가드(미들웨어) ─────────
    /** pre-가드: 글자/숫자가 하나도 없으면(예: "", " ", "?") 무의미 질문으로 본다. */
    private static boolean isBlankQuestion(String q) {
        return q == null || q.replaceAll("[^\\p{L}\\p{N}]", "").isBlank();
    }

    /** post-가드: 실질 답이 아니면(빈 답/해명·회피) 소스를 제거 — "헛근거" 표시 방지. */
    private RagService.RagResult postProcess(RagService.RagResult r) {
        if (r == null) return null;
        if (isNonAnswer(r.answer())) {
            return new RagService.RagResult(r.answer(), List.of(), r.logId());
        }
        return r;
    }

    /** 해명/회피(non-answer) 판별 — 보수적으로(실답 소스 안 지우게). */
    private static boolean isNonAnswer(String a) {
        if (a == null || a.isBlank()) return true;
        String s = a.trim();
        if (s.contains("질문을 알려") || s.contains("질문을 입력") || s.contains("질문을 말씀")
                || s.contains("질문이 없") || s.contains("질문을 다시")) return true;
        if (s.contains("죄송") && s.contains("질문")) return true;
        return false;
    }

    /** 경량 Src → 표출용 Article(임베딩 null). RAG 표출은 title/summary/url 만 사용. */
    private static List<Article> toArticles(List<Src> srcs) {
        List<Article> out = new ArrayList<>();
        if (srcs == null) return out;
        for (Src s : srcs) {
            Article a = new Article();
            a.setTitle(s.title());
            a.setSummary(s.summary());
            a.setUrl(s.url());
            out.add(a);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String trim(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());   // 폴백(이론상 도달 안 함)
        }
    }
}
