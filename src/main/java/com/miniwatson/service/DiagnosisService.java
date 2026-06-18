package com.miniwatson.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이미지 기반 진단 — 계기판 경고등/파손 부품 사진을 Vision+OCR로 식별하고,
 * 매뉴얼 RAG로 의미·조치를 붙여 한국어 진단을 만든다. 진단 결과는 견적의 입력으로 이어진다.
 *
 * 기존 멀티모달(OllamaService.askWithImages + OcrService) 재사용.
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    private final OllamaService ollama;
    private final OcrService ocrService;
    private final RagService ragService;

    @Value("${ollama.vision-model:llava:latest}")
    private String visionModel;

    public DiagnosisService(OllamaService ollama, OcrService ocrService, RagService ragService) {
        this.ollama = ollama;
        this.ocrService = ocrService;
        this.ragService = ragService;
    }

    public Map<String, Object> diagnoseImage(MultipartFile image, String namespace, String model) throws Exception {
        String ns = (namespace == null || namespace.isBlank()) ? "vehicle" : namespace;
        byte[] bytes = image.getBytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);

        // 1) Vision 캡션 + OCR (정확한 텍스트/코드)
        String caption = "";
        try {
            caption = ollama.askWithImages(
                "이 자동차 관련 이미지를 한국어로 설명하라. 계기판 경고등이면 어떤 경고등인지, "
                + "부품/손상 사진이면 어떤 부품과 어떤 문제인지 한두 문장으로.", visionModel, List.of(base64));
        } catch (Exception e) { log.warn("[diagnose] vision 실패: {}", e.getMessage()); }
        String ocr = "";
        try { ocr = ocrService.extract(bytes); } catch (Exception e) { log.warn("[diagnose] ocr 실패: {}", e.getMessage()); }

        // 2) 매뉴얼 RAG로 의미·조치 근거
        String manual = "";
        List<String> sources = new ArrayList<>();
        String query = (caption + " " + ocr).trim();
        if (query.isBlank()) query = "경고등 의미와 조치";
        try {
            var rag = ragService.ask(query.length() > 200 ? query.substring(0, 200) : query, ns, model);
            manual = rag.answer();
            rag.sources().forEach(a -> sources.add(a.getTitle()));
        } catch (Exception e) { log.warn("[diagnose] RAG 실패: {}", e.getMessage()); }

        // 3) 한국어 진단 종합
        String prompt = "당신은 자동차 정비 진단 어시스턴트입니다. 아래 정보로 한국어 진단을 작성하세요.\n"
                + "형식: (1) 식별된 문제 (2) 가능한 원인 (3) 권장 조치. 없는 내용은 지어내지 마세요.\n\n"
                + "[이미지 설명] " + caption + "\n[OCR 텍스트] " + ocr + "\n[매뉴얼 근거] " + manual + "\n\n진단:";
        String diagnosis;
        try { diagnosis = ollama.ask(prompt, model, "image-diagnosis"); }
        catch (Exception e) { diagnosis = caption + (manual.isBlank() ? "" : ("\n매뉴얼: " + manual)); }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", caption);
        out.put("ocr", ocr);
        out.put("diagnosis", diagnosis);
        out.put("problem", caption.isBlank() ? ocr : caption);   // 견적 입력으로 이어짐
        out.put("sources", sources);
        return out;
    }
}
