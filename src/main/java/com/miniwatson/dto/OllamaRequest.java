package com.miniwatson.dto;

import java.util.Map;
import lombok.Data;
// Json key 형태로 만들어서 함.
@Data
public class OllamaRequest {
    private String model;
    private String prompt;
    private boolean stream;
    private Map<String, Object> options;
}
