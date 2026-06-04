package com.miniwatson.service;

import com.miniwatson.dto.EmbeddingRequest;
import com.miniwatson.dto.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
@Service
public class EmbeddingService {
//    private final String OllAMA_EMBED_URL = "http://localhost:11434/api/embed";
    @Value("${ollama.url}")
    private String ollamaUrl;

//    private final String EMBED_MODEL = "nomic-embed-text";
    @Value("${ollama.embed-model}")
    private String embedModel;
    private final RestTemplate restTemplate = new RestTemplate();

    public List<Float> embed(String text) {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(embedModel);
        request.setInput(text);

        EmbeddingResponse response = restTemplate.postForObject(
                ollamaUrl + "/api/embed",
                request,
                EmbeddingResponse.class
        );

        if (response == null || response.getEmbeddings() == null || response.getEmbeddings().isEmpty()){
            throw new RuntimeException("No embedding returned for text: " + text);
        }
        return response.getEmbeddings().get(0);
    }

}
