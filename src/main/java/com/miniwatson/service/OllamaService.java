package com.miniwatson.service;

import com.miniwatson.dto.OllamaRequest;
import com.miniwatson.dto.OllamaResponse;
import com.miniwatson.governance.PiiRedactionService;

import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    @Value("${ollama.url}")
    private String ollamaUrl;

    /** Default chat model when the caller doesn't specify one. */
    @Value("${ollama.chat-model}")
    private String defaultModel;

    /** Comma-separated whitelist of selectable chat models (multi-LLM). */
    @Value("${ollama.chat-models:}")
    private String chatModelsCsv;

    @Value("${ollama.num-predict}")
    private int numPredict;

    private final RestTemplate restTemplate = new RestTemplate();
    private final QueryLogRepository queryLogRepository;
    private final PiiRedactionService piiRedactionService;

    public OllamaService(QueryLogRepository queryLogRepository, PiiRedactionService piiRedactionService) {

        this.queryLogRepository = queryLogRepository;
        this.piiRedactionService = piiRedactionService;
    }

    /** Available chat models = configured whitelist, always including the default. */
    public List<String> availableModels() {
        List<String> models = new ArrayList<>();
        models.add(defaultModel);
        if (chatModelsCsv != null && !chatModelsCsv.isBlank()) {
            for (String m : chatModelsCsv.split(",")) {
                String t = m.trim();
                if (!t.isEmpty() && !models.contains(t)) {
                    models.add(t);
                }
            }
        }
        return models;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** Resolve + validate a requested model against the whitelist. */
    private String resolveModel(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultModel;
        }
        List<String> allowed = availableModels();
        if (!allowed.contains(requested)) {
            throw new IllegalArgumentException(
                    "Model '" + requested + "' is not allowed. Available: " + allowed);
        }
        return requested;
    }

    /** Text chat with the default model. */
    public String ask(String question) {
        return ask(question, null);
    }

    public String ask(String prompt, String model){
        return ask(prompt, model, prompt);
    }
    /** Text chat with a caller-chosen model (validated against the whitelist). */

    public String ask(String prompt, String model, String userQuestion){
        return generate(prompt, resolveModel(model), null, userQuestion, null);
    }
    public String ask(String prompt, String model, String userQuestion, String sources){
        return generate(prompt, resolveModel(model), null, userQuestion, sources);
    }
    /**
     * Multimodal chat: send a prompt plus base64-encoded images to a vision model.
     * The model is used as-is (vision models live outside the chat whitelist).
     */
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) {
        String m = (visionModel == null || visionModel.isBlank()) ? defaultModel : visionModel;
        return generate(prompt, m, base64Images, prompt, null);
    }

    /** Core Ollama /api/generate call + governance audit logging. */
    private String generate(String prompt, String model, List<String> images, String userQuestion, String sources) {
        long startTime = System.currentTimeMillis();

        OllamaRequest request = new OllamaRequest();
        request.setModel(model);
        request.setPrompt(prompt);
        request.setStream(false);
        request.setThink(false);
        request.setOptions(Map.of("num_predict", numPredict));
        request.setKeepAlive("10m");                 // ← 여기로 (호출 전, 별도 줄)
        if (images != null && !images.isEmpty()) {
            request.setImages(images);
        }

        OllamaResponse response = restTemplate.postForObject(
                ollamaUrl + "/api/generate",
                request,
                OllamaResponse.class
        );

        long latency = System.currentTimeMillis() - startTime;
        String answer = (response != null) ? response.getResponse() : "Error: no response";

        //거버넌스 : 감사 로그 남기기전에 PPI 마스킹
        PiiRedactionService.Redaction rq = piiRedactionService.redact(userQuestion);
        PiiRedactionService.Redaction ra = piiRedactionService.redact(answer);

        QueryLog log = new QueryLog();
        log.setAugmentedPrompt(prompt);
        log.setQuestion(rq.text());
        log.setAnswer(ra.text());
        log.setModel(model);
        log.setLatencyMs(latency);
        log.setPiiCount(rq.count() + ra.count());
        log.setSources(sources);
        queryLogRepository.save(log);
        return answer;
    }
}
