package com.miniwatson.service;

import com.miniwatson.dto.OllamaRequest;
import com.miniwatson.dto.OllamaResponse;

import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
@Service
public class OllamaService {
    //Ollama API URL
    //private final String OLLMA_URL = "http://localhost:11434/api/generate";
    @Value("${ollama.url}")
    private String ollamaUrl;
    // 사용할 모델
    //private final String Model = "gemma4";
    @Value("${ollama.chat-model}")
    private String model;

    @Value("${ollama.num-predict}")
    private int numPredict;

    // HTTP 호출용 도구
    private final RestTemplate restTemplate = new RestTemplate();
    // Repository 주입 (governance)
    private final QueryLogRepository queryLogRepository;

    //생성자
    public OllamaService(QueryLogRepository queryLogRepository){

        this.queryLogRepository = queryLogRepository;
    }

    // 메서드 : 질문 받아서 답변 변환

    public String ask(String question) {
        long startTime = System.currentTimeMillis();

        OllamaRequest request = new OllamaRequest();
        request.setModel(model);
        request.setPrompt(question);
        request.setStream(false);
        request.setThink(false);
        request.setOptions(Map.of("num_predict", numPredict));

        OllamaResponse response = restTemplate.postForObject(
                ollamaUrl + "/api/generate",
                request,
                OllamaResponse.class
        );

        // 응답 시간 계산

        long latency = System.currentTimeMillis() - startTime;
        // 응답 추출
        String answer = (response != null) ? response.getResponse() : "Error: no response";

        // DB 에  로그 저장
        QueryLog log = new QueryLog();
        log.setQuestion(question);
        log.setAnswer(answer);
        log.setModel(model);
        log.setLatencyMs(latency);
        queryLogRepository.save(log);
        return answer;

        // 응답 시간 계
//        // 3. 응답에서 답변 텍스트 추출해서 반환
//        if (response == null) {
//            return "Error : no response from Ollama";
//        }
//        return response.getResponse();
    }
}
