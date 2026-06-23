package com.miniwatson.data;


import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
@Component
@Primary
public class TieredArticleStore implements ArticleRepository{
    private final ArticleStore hot;
    private final ArticleParquetStore cold;

    @Value("${storage.tier.threshold:100}")
    private int threshold;

    // nextId를 매 저장마다 loadAll()로 계산하면 청크당 O(N) → 누적 O(N²)(대량 매뉴얼 적재에 치명적).
    // 최초 1회만 디스크에서 최대 id를 시드한 뒤, 메모리 카운터로 증가시킨다.
    private long maxId = -1;

    public TieredArticleStore(ArticleStore hot, ArticleParquetStore cold){
        this.hot = hot;
        this.cold = cold;
    }

    public List<Article> loadAll() throws IOException{
        List<Article> all = new ArrayList<>(cold.loadAll());
        all.addAll(hot.loadAll());
        return all;
    }

    public synchronized Article save(Article a) throws IOException {
        if (maxId < 0) maxId = loadAll().stream().mapToLong(Article::getId).max().orElse(0);
        a.setId(++maxId);
        hot.save(a);   // JSONL append (O(1))
        if (hot.size() >= threshold) compact();   // size()는 라인 카운트만 — 전체 파싱 안 함
        return a;
    }
    public boolean deleteById(long id) throws IOException{
        boolean isHot = hot.deleteByID(id);
        boolean isCold = cold.deleteById(id);
        return isHot || isCold;
    }
    private void compact() throws IOException{
        List<Article> merged = loadAll(); // cold + hot
        cold.saveAll(merged); // Parquet 으로 압축
        hot.saveAll(new ArrayList<>()); // hot 비우기
    }

    public void saveAll(List<Article> articles) throws IOException {
        cold.saveAll(articles);
    }

}
