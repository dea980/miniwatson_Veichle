package com.miniwatson.service;

import com.miniwatson.dto.EmbeddingRequest;
import com.miniwatson.dto.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
@Service
public class EmbeddingService {
    private final String OllAMA_EMBED_URL = "http://localhost:11434/api/embed";
    private final String EMBED_MODEL = "nomic-embed-text";
    private final RestTemplate restTemplate = new RestTemplate();

    public List<Float> embed(String text){
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(EMBED_MODEL);
        request.setInput(text);

        EmbeddingResponse response = restTemplate.postForObject(
                OllAMA_EMBED_URL,
                request,
                EmbeddingResponse.class
        );

        if (response == null || response.getEmbeddings() == null || response.getEmbeddings().isEmpty()){
            throw new RuntimeException("No embedding returned for text: " + text);
        }
        return response.getEmbeddings().get(0);
    }

}
