package com.miniwatson.service.llm;

import java.util.List;


public interface EmbeddingClient {
    List<Float> embed(String text);
    List<Float> embedQuery(String text);
    List<Float> embedDocument(String text);

}
