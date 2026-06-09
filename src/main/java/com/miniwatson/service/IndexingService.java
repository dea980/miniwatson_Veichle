package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.VectorIndex;
import org.springframework.stereotype.Service;
import com.miniwatson.data.KeywordIndex;
import java.util.List;

/** 모든 검색 인덱스(vector, keyword...) 갱신을 한 곳에서 담당. ingest와 분리. */
@Service
public class IndexingService {
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;

    public IndexingService(VectorIndex vectorIndex, KeywordIndex keywordIndex){
        this.vectorIndex = vectorIndex;
        this.keywordIndex =keywordIndex;
    }
    /** 새 article을 모든 인덱스에 추가. */
    public void index(Article a){
        vectorIndex.add(a);
        keywordIndex.add(a);
    }
    /** 전체 재구성 (삭제 후 동기화 등). */

    public void reindex(List<Article>all){
        vectorIndex.rebuild(all);
        keywordIndex.rebuild(all);
    }
}
