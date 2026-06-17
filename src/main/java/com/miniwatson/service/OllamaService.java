package com.miniwatson.service;

import com.miniwatson.governance.ModelRegistry;
import com.miniwatson.governance.PiiRedactionService;
import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;
import com.miniwatson.service.llm.LlmClient;
import com.miniwatson.service.llm.OllamaLlmClient;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 거버넌스 래퍼 — 추론 자체는 OllamaLlmClient(순수 제공자)에 위임하고,
 * 이 클래스는 PII 마스킹 + 감사 로그(query_log)만 책임진다.
 * 호출처는 이 클래스를 그대로 주입받으므로(변경 없음), 제공자 교체는 delegate만 바꾸면 된다.
 * (이름을 GovernanceLlmClient 등으로 바꾸는 정리는 Step 3에서 호출처 타입 교체와 함께.)
 */
@Service
public class OllamaService implements LlmClient {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OllamaService.class);

    private final OllamaLlmClient delegate;
    private final QueryLogRepository queryLogRepository;
    private final PiiRedactionService piiRedactionService;
    private final MeterRegistry meterRegistry;
    private final ModelRegistry modelRegistry;

    // 주의: 인스턴스 필드라 동시 요청에서 경합 가능(원본 동작 유지). 정확성은 향후 ThreadLocal/반환값으로 개선.
    private Long lastQueryLogId;

    public OllamaService(OllamaLlmClient delegate,
                         QueryLogRepository queryLogRepository,
                         PiiRedactionService piiRedactionService,
                         MeterRegistry meterRegistry,
                         ModelRegistry modelRegistry) {
        this.delegate = delegate;
        this.queryLogRepository = queryLogRepository;
        this.piiRedactionService = piiRedactionService;
        this.meterRegistry = meterRegistry;
        this.modelRegistry = modelRegistry;
    }

    public Long lastQueryLogId() { return lastQueryLogId; }

    @Override
    public List<String> availableModels() { return delegate.availableModels(); }

    @Override
    public String defaultModel() { return delegate.defaultModel(); }

    /** Text chat with the default model. */
    public String ask(String question) {
        return ask(question, null);
    }

    public String ask(String prompt, String model) {
        return ask(prompt, model, prompt);
    }

    public String ask(String prompt, String model, String userQuestion) {
        return ask(prompt, model, userQuestion, null);
    }

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        long start = System.currentTimeMillis();
        String answer = delegate.ask(prompt, model, userQuestion, sources);
        long latency = System.currentTimeMillis() - start;
        audit(prompt, effectiveModel(model), userQuestion, sources, answer, latency, "chat");
        return answer;
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        long start = System.currentTimeMillis();
        String answer = delegate.askWithImages(prompt, visionModel, base64Images);
        long latency = System.currentTimeMillis() - start;
        audit(prompt, effectiveModel(visionModel), prompt, null, answer, latency, "vision");
        return answer;
    }

    /** null/blank 모델은 기본 모델로 본다(로그에 실제 사용 모델을 남기기 위함). */
    private String effectiveModel(String model) {
        return (model == null || model.isBlank()) ? delegate.defaultModel() : model;
    }

    /**
     * 거버넌스: 감사 로그 남기기 전에 PII 마스킹.
     * fail-open: 저장 실패가 사용자 답변을 막으면 안 된다(거버넌스가 가용성을 죽이면 안 됨).
     */
    private void audit(String prompt, String model, String userQuestion, String sources, String answer, long latencyMs, String type) {
        PiiRedactionService.Redaction rq = piiRedactionService.redact(userQuestion);
        PiiRedactionService.Redaction ra = piiRedactionService.redact(answer);
        int pii = rq.count() + ra.count();

        // 운영 메트릭 (Prometheus): 모델/유형별 지연 히스토그램 + 호출수 + PII 마스킹 건수.
        meterRegistry.timer("miniwatson.llm.latency", "model", model, "type", type).record(latencyMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("miniwatson.llm.calls", "model", model, "type", type).increment();
        if (pii > 0) meterRegistry.counter("miniwatson.pii.redacted", "type", type).increment(pii);

        QueryLog log = new QueryLog();
        log.setAugmentedPrompt(prompt);
        log.setQuestion(rq.text());
        log.setAnswer(ra.text());
        log.setModel(model);
        log.setLatencyMs(latencyMs);
        log.setPiiCount(rq.count() + ra.count());
        log.setSources(sources);
        log.setModelConfig(modelRegistry.fingerprint());   // 모델/설정 지문(재현성·드리프트 귀인)
        try {
            QueryLog saved = queryLogRepository.save(log);
            this.lastQueryLogId = saved.getId();
        } catch (Exception e) {
            this.lastQueryLogId = null;
            LOG.warn("[audit] query_log 저장 실패 — 답변은 반환(fail-open): {}", e.getMessage());
        }
    }
}
