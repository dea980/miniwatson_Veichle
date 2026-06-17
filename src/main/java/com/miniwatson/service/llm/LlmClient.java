package com.miniwatson.service.llm;
import java.util.List;

public interface LlmClient {
    String ask(String prompt, String model, String userQuestion, String sources);
    String askWithImages(String prompt, String visionModel, List<String> base64Images);
    List<String> availableModels();
    String defaultModel();
}
