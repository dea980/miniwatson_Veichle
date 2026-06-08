package com.miniwatson.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.util.StringPair;
import com.miniwatson.data.Article;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component("cross")
public class CrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final String MODEL = "BAAI/bge-reranker-base";

    private ZooModel<StringPair, float[]> model;

    @PostConstruct
    public void load() {
        try {
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(MODEL);
            Translator<StringPair, float[]> translator =
                    CrossEncoderTranslator.builder(tokenizer).optSigmoid(true).build();

            Criteria<StringPair, float[]> criteria = Criteria.builder()
                    .setTypes(StringPair.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + MODEL)
                    .optEngine("PyTorch")
                    .optTranslator(translator)
                    .build();

            model = criteria.loadModel();
            log.info("[CrossEncoderReranker] loaded {}", MODEL);
        } catch (Exception e) {
            log.error("[CrossEncoderReranker] model load failed, will fall back: {}", e.getMessage());
            model = null;
        }
    }

    @Override
    public List<Article> rerank(String question, List<Article> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        if (model == null) return candidates.subList(0, topK);   // 모델 못 받으면 폴백

        try (Predictor<StringPair, float[]> predictor = model.newPredictor()) {
            List<Scored> scored = new ArrayList<>();
            for (Article a : candidates) {
                String passage = a.getSummary();
                if (passage.length() > 512) passage = passage.substring(0, 512);
                float[] out = predictor.predict(new StringPair(question, passage));
                scored.add(new Scored(a, out[0]));   // 관련도 점수
            }
            return scored.stream()
                    .sorted(Comparator.comparingDouble((Scored s) -> -s.score))
                    .limit(topK)
                    .map(s -> s.article)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[CrossEncoderReranker] predict failed, fallback: {}", e.getMessage());
            return candidates.subList(0, topK);
        }
    }

    @PreDestroy
    public void close() {
        if (model != null) model.close();
    }

    private record Scored(Article article, float score) {}
}