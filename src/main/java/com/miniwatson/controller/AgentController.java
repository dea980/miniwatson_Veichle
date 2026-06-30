package com.miniwatson.controller;

import com.miniwatson.service.AgentService;
import com.miniwatson.service.DiagnosisService;
import com.miniwatson.service.EstimateService;
import com.miniwatson.service.ReportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Agentic Search — 질문 → 도구 선택(RAG/리콜SQL/둘다) → 실행 → 한국어 종합.
 * POST /api/agent/ask     body: {"question","namespace","model"}
 * POST /api/agent/report  body: {"car":"PALISADE","namespace":"vehicle","model":<llm>}  — 차종 종합 진단서
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agent;
    private final ReportService report;
    private final EstimateService estimate;
    private final DiagnosisService diagnosis;

    public AgentController(AgentService agent, ReportService report,
                           EstimateService estimate, DiagnosisService diagnosis) {
        this.agent = agent;
        this.report = report;
        this.estimate = estimate;
        this.diagnosis = diagnosis;
    }

    @PostMapping("/ask")
    public AgentService.AgentResult ask(@RequestBody Map<String, String> body) throws Exception {
        return agent.run(
                body.get("question"),
                body.getOrDefault("namespace", "vehicle"),
                body.get("model"));
    }

    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody Map<String, String> body) throws Exception {
        boolean force = "true".equalsIgnoreCase(body.getOrDefault("force", "false"));
        return report.generate(
                body.get("car"),
                body.getOrDefault("namespace", "vehicle"),
                body.get("model"),
                force);
    }

    /** 적재된(생성·저장된) 리포트 목록 — 차종·생성일·모델. */
    @GetMapping("/reports")
    public Map<String, Object> reports() {
        return Map.of("reports", report.savedReports());
    }

    /** 접수번호별 리포트 — AI 진단+견적+점검 스냅샷(적재 캐시). force=true면 재생성(메모 보존). */
    @GetMapping("/case-report")
    public Map<String, Object> caseReport(@RequestParam String id,
                                          @RequestParam(defaultValue = "vehicle") String namespace,
                                          @RequestParam(required = false) String model,
                                          @RequestParam(defaultValue = "false") boolean force) {
        try { return report.caseReport(id, namespace, model, force); }
        catch (Throwable t) { return Map.of("caseNumber", id, "error", t.toString()); }
    }

    /** 불만 접수 내용 한국어 핵심 요약 — 처음 1회만 LLM, 이후 캐시. */
    @GetMapping("/case-summary")
    public Map<String, Object> caseSummary(@RequestParam String id,
                                           @RequestParam(required = false) String model,
                                           @RequestParam(defaultValue = "false") boolean force) {
        try { return report.caseSummary(id, model, force); }
        catch (Throwable t) { return Map.of("caseNumber", id, "error", t.toString()); }
    }

    /** 정비사 메모 저장(문서화·적재). body: {id, note, model?} */
    @PostMapping("/case-report/note")
    public Map<String, Object> saveCaseNote(@RequestBody Map<String, String> body) {
        try { return report.saveCaseNote(body.get("id"), body.get("note"),
                body.getOrDefault("namespace", "vehicle"), body.get("model")); }
        catch (Throwable t) { return Map.of("id", body.getOrDefault("id", ""), "error", t.toString()); }
    }

    /** 증상/진단 → 필요 부품 명세(+ 참고 견적). body: {"problem","car","model"} */
    @PostMapping("/estimate")
    public Map<String, Object> estimate(@RequestBody Map<String, String> body) throws Exception {
        return estimate.estimate(body.get("problem"), body.get("car"), body.get("model"));
    }

    /** 이미지 진단 — 경고등/부품 사진 → Vision+OCR+매뉴얼 RAG → 한국어 진단. */
    @PostMapping("/diagnose-image")
    public Map<String, Object> diagnoseImage(@RequestParam("image") MultipartFile image,
                                             @RequestParam(value = "namespace", required = false, defaultValue = "vehicle") String namespace,
                                             @RequestParam(value = "model", required = false) String model) throws Exception {
        return diagnosis.diagnoseImage(image, namespace, model);
    }
}
