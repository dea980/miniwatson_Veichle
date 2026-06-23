package com.miniwatson.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiered(hot+cold) 동작 회귀 테스트.
 * Phase 1·2 변경:
 *   - maxId를 부팅 시 1회 시드 후 메모리 카운터로 증가(O(1))
 *   - threshold 도달 시 compact → cold로 머지, hot drain
 *   - loadAll = cold + hot 합본
 */
class TieredArticleStoreTest {

    private static Article mk(String ns, String title, String summary) {
        Article a = new Article();
        a.setNamespace(ns);
        a.setTitle(title);
        a.setSummary(summary);
        a.setUrl("u");
        a.setIngestedAt(LocalDateTime.now());
        a.setEmbedding(List.of(0.1f));
        return a;
    }

    private TieredArticleStore newTiered(Path tmp, int threshold) throws Exception {
        ArticleStore hot = new ArticleStore(
                tmp.resolve("hot").toString(),
                tmp.resolve("legacy.json").toString());
        ArticleParquetStore cold = new ArticleParquetStore(
                tmp.resolve("cold").toString(),
                tmp.resolve("legacy.parquet").toString());
        TieredArticleStore t = new TieredArticleStore(hot, cold);
        Field f = TieredArticleStore.class.getDeclaredField("threshold");
        f.setAccessible(true);
        f.setInt(t, threshold);
        return t;
    }

    @Test
    void saveAssignsMonotonicIds(@TempDir Path tmp) throws Exception {
        TieredArticleStore s = newTiered(tmp, 100);
        Article a = s.save(mk("vehicle", "A.pdf #1", "x"));
        Article b = s.save(mk("vehicle", "A.pdf #2", "y"));
        Article c = s.save(mk("vehicle", "B.pdf #1", "z"));
        assertEquals(1L, a.getId());
        assertEquals(2L, b.getId());
        assertEquals(3L, c.getId());
    }

    @Test
    void compactMergesHotToColdAndDrainsHot(@TempDir Path tmp) throws Exception {
        TieredArticleStore s = newTiered(tmp, 3);   // 임계치 3
        s.save(mk("vehicle", "A.pdf #1", "1"));
        s.save(mk("vehicle", "A.pdf #2", "2"));
        s.save(mk("vehicle", "A.pdf #3", "3"));   // 도달 → compact 발동

        // hot은 비어있고 cold에 3건이 보여야 함
        Field hotF = TieredArticleStore.class.getDeclaredField("hot");
        Field coldF = TieredArticleStore.class.getDeclaredField("cold");
        hotF.setAccessible(true);
        coldF.setAccessible(true);
        ArticleStore hot = (ArticleStore) hotF.get(s);
        ArticleParquetStore cold = (ArticleParquetStore) coldF.get(s);

        assertEquals(0, hot.size(), "compact 후 hot drain");
        assertEquals(3, cold.loadAll().size(), "cold에 3건 머지됨");
        assertEquals(3, s.loadAll().size(), "전체 합본은 3건");
    }

    @Test
    void idsRemainUniqueAcrossCompact(@TempDir Path tmp) throws Exception {
        TieredArticleStore s = newTiered(tmp, 2);
        Article a = s.save(mk("vehicle", "A.pdf #1", "1"));
        Article b = s.save(mk("vehicle", "A.pdf #2", "2"));   // compact 트리거
        Article c = s.save(mk("vehicle", "A.pdf #3", "3"));   // hot에 새로 들어감
        assertEquals(1L, a.getId());
        assertEquals(2L, b.getId());
        assertEquals(3L, c.getId(), "compact 후에도 id 단조 증가");
        assertEquals(3, s.loadAll().size());
    }

    @Test
    void deleteByIdAcrossTiers(@TempDir Path tmp) throws Exception {
        TieredArticleStore s = newTiered(tmp, 2);
        s.save(mk("vehicle", "A.pdf #1", "1"));
        Article b = s.save(mk("vehicle", "A.pdf #2", "2"));   // compact → b는 cold로
        Article c = s.save(mk("vehicle", "A.pdf #3", "3"));   // c는 hot
        assertTrue(s.deleteById(b.getId()), "cold에 있는 id 삭제");
        assertTrue(s.deleteById(c.getId()), "hot에 있는 id 삭제");
        assertEquals(1, s.loadAll().size());
    }
}
