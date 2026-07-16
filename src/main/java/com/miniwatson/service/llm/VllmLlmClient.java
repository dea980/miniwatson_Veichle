package com.miniwatson.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * vLLM 추론 제공자 (llm.provider=vllm 일 때만 활성).
 *
 * vLLM은 OpenAI 호환 API(/v1/chat/completions)를 서빙한다. 이 클래스는 그 엔드포인트만 호출한다
 * — 거버넌스(PII 마스킹·감사 로그)는 OllamaService 래퍼가 이 제공자를 감싸 처리(관심사 분리).
 *
 * 왜 이 제공자:
 *  - 맥(Apple Silicon)에서 vLLM-Metal(MLX 백엔드, PagedAttention·prefix cache·연속배칭)로 로컬 서빙.
 *  - OpenAI 호환이라 클라우드 vLLM(CUDA)이나 다른 OpenAI 호환 서버로도 URL만 바꿔 스왑 가능.
 *  - 실행법·로드맵은 docs/SERVING.md (P1 서빙, P2 이 배선, P3 벤치).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "vllm")
public class VllmLlmClient implements RawLlmProvider {

    /** OpenAI 호환 베이스 URL. vLLM-Metal 기본 :8000. (경로 /v1/... 는 코드가 붙임) */
    @Value("${vllm.url:http://localhost:8000}")
    private String baseUrl;

    /** 선택: vLLM을 --api-key 로 띄웠을 때의 토큰(Bearer). 비면 인증 헤더 생략. */
    @Value("${vllm.api-key:}")
    private String apiKey;

    @Value("${vllm.chat-model:}")
    private String defaultModel;

    /** 선택 가능한 chat 모델 화이트리스트(쉼표구분, 멀티-LLM 드롭다운). */
    @Value("${vllm.chat-models:}")
    private String chatModelsCsv;

    @Value("${vllm.num-predict:512}")
    private int numPredict;

    /** 샘플링 온도 — 낮을수록 결정적(집계 수치 인용 충실도↑). .env로 조절(VLLM_TEMPERATURE). */
    @Value("${vllm.temperature:0.2}")
    private double temperature;

    // 출력 품질 제약 — Ollama 제공자와 동일한 한국어 가드(외국 문자 누수·반복 완화).
    // 중요: system 프롬프트에 한자·가나 같은 외국 문자를 절대 넣지 않는다(소형 모델이 그대로 베낌).
    private static final String SYSTEM =
            "답변은 오직 한국어로만 작성한다. 한글과 숫자, 영문 약어만 사용하고 그 외 다른 나라 문자는 하나도 쓰지 않는다. "
          + "같은 문장이나 구절을 반복하지 않는다. 지시문을 그대로 옮겨 적지 말고 간결하게 답한다.";

    private final RestTemplate restTemplate = buildTimeoutRestTemplate();

    /** 타임아웃 없는 RestTemplate은 서버가 멈추면 무한대기 → 가용성 구멍. 연결 5s/읽기 120s. */
    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(java.time.Duration.ofSeconds(5));
        f.setReadTimeout(java.time.Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    @Override
    public List<String> availableModels() {
        List<String> models = new ArrayList<>();
        if (defaultModel != null && !defaultModel.isBlank()) models.add(defaultModel);
        if (chatModelsCsv != null && !chatModelsCsv.isBlank()) {
            for (String m : chatModelsCsv.split(",")) {
                String t = m.trim();
                if (!t.isEmpty() && !models.contains(t)) models.add(t);
            }
        }
        return models;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    /** 요청 모델을 화이트리스트로 검증(비었으면 기본). 화이트리스트가 비어 있으면 요청값을 신뢰. */
    private String resolveModel(String requested) {
        if (requested == null || requested.isBlank()) return defaultModel;
        List<String> allowed = availableModels();
        if (!allowed.isEmpty() && !allowed.contains(requested)) {
            throw new IllegalArgumentException("Model '" + requested + "' is not allowed. Available: " + allowed);
        }
        return requested;
    }

    @Override
    public String ask(String prompt, String model, String userQuestion, String sources) {
        // userQuestion/sources는 감사 로그용 메타라 순수 제공자에선 쓰지 않는다(래퍼가 처리).
        List<Map<String, Object>> messages = List.of(
                Map.<String, Object>of("role", "system", "content", SYSTEM),
                Map.<String, Object>of("role", "user", "content", prompt));
        return chatCompletion(resolveModel(model), messages);
    }

    @Override
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        // OpenAI 비전 포맷: content 배열에 text + image_url(data URI). 서빙 모델이 비전 지원 시 동작.
        // (vLLM-Metal의 멀티모달은 paged 백엔드에서 vision-only로 제한적 — 모델별 지원 확인 필요.)
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.<String, Object>of("type", "text", "text", prompt));
        if (base64Images != null) {
            for (String b64 : base64Images) {
                content.add(Map.<String, Object>of(
                        "type", "image_url",
                        "image_url", Map.of("url", "data:image/jpeg;base64," + b64)));
            }
        }
        String m = (visionModel == null || visionModel.isBlank()) ? defaultModel : visionModel;
        List<Map<String, Object>> messages = List.of(Map.<String, Object>of("role", "user", "content", content));
        return chatCompletion(m, messages);
    }

    /** OpenAI 호환 /v1/chat/completions 호출. choices[0].message.content 반환. */
    @SuppressWarnings("unchecked")
    private String chatCompletion(String model, List<Map<String, Object>> messages) {
        Map<String, Object> body = Map.<String, Object>of(
                "model", model,
                "messages", messages,
                "temperature", temperature,  // 결정성↑(집계 수치 인용 충실도). VLLM_TEMPERATURE로 조절
                "max_tokens", numPredict);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);

        Map<String, Object> resp = restTemplate.postForObject(
                baseUrl + "/v1/chat/completions",
                new HttpEntity<>(body, headers),
                Map.class);

        if (resp == null) return "Error: no response";
        Object choicesObj = resp.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return "Error: no choices";
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) return "Error: bad choice";
        Object msg = ((Map<String, Object>) choice).get("message");
        if (!(msg instanceof Map<?, ?> message)) return "Error: no message";
        Object content = ((Map<String, Object>) message).get("content");
        return content == null ? "" : content.toString();
    }
}
