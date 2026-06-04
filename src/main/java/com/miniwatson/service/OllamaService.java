package com.miniwatson.service;

import com.miniwatson.dto.OllamaRequest;
import com.miniwatson.dto.OllamaResponse;

import com.miniwatson.governance.QueryLog;
import com.miniwatson.governance.QueryLogRepository;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;



@Service
public class OllamaService {
    //Ollama API URL
    private final String OLLMA_URL = "http://localhost:11434/api/generate";
    // 사용할 모델
    private final String Model = "gemma4";


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
        // 1. Ollama 로 보낼 request 만들기
        OllamaRequest request = new OllamaRequest();
        request.setModel(Model);
        request.setPrompt(question);
        request.setStream(false);

        //2. Ollama API 호출 (POST)
        OllamaResponse response = restTemplate.postForObject(
                OLLMA_URL,
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
        log.setModel(Model);
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


//        @Service
//
//        Spring한테 "이건 Service layer다" 알림
//        @RestController처럼 자동으로 Spring 컨테이너에 등록
//        다른 클래스가 의존성 주입으로 사용 가능 (AskController가 이걸 받음!)
//
//        private final String OLLAMA_URL = "http://localhost:11434/api/generate";
//
//        private = 외부 접근 X
//        final = 한 번 정해지면 변경 불가
//        상수처럼 사용
//        나중에 application.yml로 옮길 수 있음 (지금은 코드에 박아둠)
//
//        private final RestTemplate restTemplate = new RestTemplate();
//
//        RestTemplate = Spring의 HTTP 클라이언트
//        HTTP 요청 보내는 도구
//        new로 직접 만듦 (간단한 방식)
//
//        public String ask(String question)
//
//        메서드 시그니처
//        받는 것: 질문 텍스트 1개
//        반환: 답변 텍스트
//
//        OllamaRequest request = new OllamaRequest();
//
//        OllamaRequest 객체 생성
//        빈 객체 — 아직 필드 안 채움
//
//        request.setModel(MODEL);
//
//        setModel() = Lombok이 자동 생성한 setter
//        값 채움
//
//        restTemplate.postForObject(URL, request, OllamaResponse.class)
//
//        이게 핵심!
//                HTTP POST 요청
//        첫 번째 인자: URL
//        두 번째 인자: 보낼 객체 (Jackson이 자동으로 JSON 변환)
//        세 번째 인자: 응답을 어떤 클래스로 받을지 (Jackson이 자동으로 JSON → 객체 변환)
//
//        if (response == null) return "Error..."
//
//        안전장치
//        네트워크 에러 등으로 null 올 수 있음
//
//        return response.getResponse();
//
//        getResponse() = Lombok 자동 생성 getter
//        OllamaResponse의 response 필드 추출
//    }
//}
