package com.miniwatson.dto;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
// Json key 형태로 만들어서 함.
@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // images 등 null 필드는 직렬화 제외
public class OllamaRequest {
    private String model;
    private String prompt;
    // 시스템 프롬프트(Ollama /api/generate의 system) — 출력 형식 제약(한국어만·한자금지·반복금지)
    private String system;
    private boolean stream;
    private Map<String, Object> options;
    private Boolean think;
    // 멀티모달: base64로 인코딩한 이미지 목록 (Ollama /api/generate의 images 필드)
    private List<String> images;
    @JsonProperty("keep_alive")
    private String keepAlive;
}
