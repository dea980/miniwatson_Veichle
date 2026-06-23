package com.miniwatson.data;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 콜드 스토어 — 문서(PDF)별 파티션 Parquet.
 *
 * 변경 이유(왜): 기존엔 모든 청크를 단일 ./data/articles.parquet 에 넣어,
 *   한 청크만 추가돼도 Parquet 전체를 삭제·재작성했다(O(N²) 디스크 IO, 대량 매뉴얼 적재에 치명적).
 * 개선: ./data/articles/ 디렉터리에 **문서 단위 파티션 파일**(namespace + 문서제목)로 분할.
 *   - saveAll(): 들어온 청크를 파티션별로 그룹핑 → **시그니처(정렬된 id)가 바뀐 파티션만** 재작성.
 *     새 매뉴얼 1권을 넣어도 그 문서의 파티션 파일 하나만 생성/갱신. 나머지는 손대지 않음.
 *   - 입력에 없는 파티션 파일은 삭제(전체 상태 동기화 — compact/delete가 전체 집합을 넘기는 기존 계약 유지).
 *   - 단일 레거시 파일(./data/articles.parquet)이 있으면 최초 로드 시 함께 읽어 다음 saveAll에서 자동 분할.
 * 공개 메서드 시그니처는 그대로라 TieredArticleStore 등 호출부는 변경 불필요.
 */
@Component
public class ArticleParquetStore {

    private static final String DEFAULT_STORAGE_DIR = "./data/articles";
    private static final String DEFAULT_LEGACY_PATH = "./data/articles.parquet";
    private static final String EXT = ".parquet";

    private final String STORAGE_DIR;
    private final String LEGACY_PATH;
    private final Schema schema;
    private volatile List<Article> cache;
    // 파티션 키 -> 마지막 기록된 시그니처(정렬 id 문자열). 동일하면 재작성 생략.
    private final Map<String, String> partitionSig = new LinkedHashMap<>();

    public ArticleParquetStore() throws IOException {
        this(DEFAULT_STORAGE_DIR, DEFAULT_LEGACY_PATH);
    }

    /** 테스트용 — 경로를 임의 디렉터리로 격리한다. */
    ArticleParquetStore(String storageDir, String legacyPath) throws IOException {
        this.STORAGE_DIR = storageDir;
        this.LEGACY_PATH = legacyPath;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("article.avsc")) {
            if (is == null) throw new IOException("article.avsc not found in classpath");
            this.schema = new Schema.Parser().parse(is);
        }
    }

    /** 파티션 키 = namespace + 문서 기본제목(청크 접미사 " #N" 제거). 파일명 충돌은 해시로 회피. */
    private static String partitionKey(Article a) {
        String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? "default" : a.getNamespace();
        String base = a.getTitle() == null ? "untitled" : a.getTitle().replaceAll(" #\\d+$", "");
        return ns + "||" + base;
    }

    private static String keyToFilename(String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe + "__" + Integer.toHexString(key.hashCode()) + EXT;
    }

    private static String signature(List<Article> arts) {
        long[] ids = arts.stream().mapToLong(Article::getId).sorted().toArray();
        StringBuilder sb = new StringBuilder();
        for (long id : ids) sb.append(id).append(',');
        return sb.toString();
    }

    /** 전체 상태 저장 — 파티션별로 분할해 변경분만 기록. */
    public synchronized void saveAll(List<Article> articles) throws IOException {
        File dir = new File(STORAGE_DIR);
        if (!dir.exists() && !dir.mkdirs()) System.err.println("Warning: cannot create " + STORAGE_DIR);

        // 1) 파티션별 그룹핑
        Map<String, List<Article>> byPart = new LinkedHashMap<>();
        for (Article a : articles) byPart.computeIfAbsent(partitionKey(a), k -> new ArrayList<>()).add(a);

        // 2) 변경된 파티션만 기록
        int written = 0;
        for (var e : byPart.entrySet()) {
            String sig = signature(e.getValue());
            if (sig.equals(partitionSig.get(e.getKey()))) continue;   // 동일 → 생략
            writePartition(keyToFilename(e.getKey()), e.getValue());
            partitionSig.put(e.getKey(), sig);
            written++;
        }
        // 3) 입력에 사라진 파티션 파일 삭제(전체 상태 동기화)
        for (String gone : new ArrayList<>(partitionSig.keySet())) {
            if (!byPart.containsKey(gone)) {
                File f = new File(dir, keyToFilename(gone));
                if (f.exists() && !f.delete()) System.err.println("Warning: cannot delete " + f);
                partitionSig.remove(gone);
            }
        }
        // 4) 레거시 단일 파일이 남아 있으면 분할 완료 후 제거
        File legacy = new File(LEGACY_PATH);
        if (legacy.exists() && !legacy.delete()) System.err.println("Warning: cannot delete legacy " + LEGACY_PATH);

        this.cache = new ArrayList<>(articles);
        System.out.println("[Parquet] saveAll: " + articles.size() + " articles, "
                + byPart.size() + " partitions (" + written + " rewritten) → " + STORAGE_DIR);
    }

    private void writePartition(String filename, List<Article> arts) throws IOException {
        File out = new File(STORAGE_DIR, filename);
        if (out.exists() && !out.delete()) throw new IOException("Failed to delete partition: " + out);
        Path path = new Path(out.getPath());
        Configuration conf = new Configuration();
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(path)
                .withSchema(schema).withConf(conf).withDataModel(GenericData.get())
                .withCompressionCodec(CompressionCodecName.SNAPPY).build()) {
            for (Article a : arts) {
                GenericRecord r = new GenericData.Record(schema);
                r.put("id", a.getId());
                String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? "default" : a.getNamespace();
                r.put("namespace", ns);
                r.put("title", a.getTitle());
                r.put("summary", a.getSummary());
                r.put("url", a.getUrl());
                r.put("ingestedAt", a.getIngestedAt().toString());
                r.put("embedding", a.getEmbedding());
                // 매뉴얼 메타(파일명에서 파싱). 비-매뉴얼은 null → Avro union이 처리.
                r.put("carCode", a.getCarCode());
                r.put("carModel", a.getCarModel());
                r.put("powertrain", a.getPowertrain());
                r.put("year", a.getYear());
                r.put("lang", a.getLang());
                r.put("region", a.getRegion());
                writer.write(r);
            }
        }
    }

    public synchronized List<Article> loadAll() throws IOException {
        if (cache == null) cache = readFromDisk();
        return new ArrayList<>(cache);
    }

    private List<Article> readFromDisk() throws IOException {
        List<Article> articles = new ArrayList<>();
        partitionSig.clear();
        File dir = new File(STORAGE_DIR);
        // 1) 파티션 파일들
        File[] parts = dir.exists() ? dir.listFiles((d, n) -> n.endsWith(EXT)) : null;
        if (parts != null) {
            for (File f : parts) {
                if (f.length() < 8) continue;
                List<Article> part = readFile(f.getPath());
                articles.addAll(part);
            }
        }
        // 2) 레거시 단일 파일(있으면 합침 → 다음 saveAll에서 분할)
        File legacy = new File(LEGACY_PATH);
        if (legacy.exists() && legacy.length() >= 8) {
            articles.addAll(readFile(LEGACY_PATH));
        }
        // 파티션 시그니처 시드(현재 디스크 상태) → 첫 saveAll에서 불필요한 재작성 방지
        Map<String, List<Article>> byPart = new LinkedHashMap<>();
        for (Article a : articles) byPart.computeIfAbsent(partitionKey(a), k -> new ArrayList<>()).add(a);
        for (var e : byPart.entrySet()) partitionSig.put(e.getKey(), signature(e.getValue()));

        System.out.println("[Parquet] readFromDisk → " + articles.size() + " articles, "
                + byPart.size() + " partitions");
        return articles;
    }

    private List<Article> readFile(String pathStr) throws IOException {
        Path path = new Path(pathStr);
        Configuration conf = new Configuration();
        List<Article> articles = new ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(path)
                .withConf(conf).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                Article article = new Article();
                article.setId((Long) record.get("id"));
                boolean hasNs = record.getSchema().getField("namespace") != null;
                Object nsObj = hasNs ? record.get("namespace") : null;
                article.setNamespace(nsObj != null ? nsObj.toString() : "default");
                article.setTitle(record.get("title").toString());
                article.setSummary(record.get("summary").toString());
                article.setUrl(record.get("url").toString());
                article.setIngestedAt(LocalDateTime.parse(record.get("ingestedAt").toString()));
                Object embObj = record.get("embedding");
                List<Float> embedding = new ArrayList<>();
                if (embObj instanceof List<?> rawList) {
                    for (Object item : rawList) if (item instanceof Number num) embedding.add(num.floatValue());
                }
                article.setEmbedding(embedding);
                // 매뉴얼 메타 — 신규 필드라 구 parquet 호환을 위해 NPE-free 접근.
                article.setCarCode(strOrNull(record, "carCode"));
                article.setCarModel(strOrNull(record, "carModel"));
                article.setPowertrain(strOrNull(record, "powertrain"));
                Object yObj = fieldOrNull(record, "year");
                article.setYear(yObj instanceof Number n ? n.intValue() : null);
                article.setLang(strOrNull(record, "lang"));
                article.setRegion(strOrNull(record, "region"));
                articles.add(article);
            }
        }
        return articles;
    }

    /** 단건 저장 — 해당 문서 파티션에만 추가(전체 재작성 없음). */
    public synchronized Article save(Article article) throws IOException {
        List<Article> all = loadAll();
        long nextId = all.stream().mapToLong(Article::getId).max().orElse(0) + 1;
        article.setId(nextId);
        all.add(article);
        saveAll(all);   // 시그니처 비교로 해당 파티션만 재작성됨
        return article;
    }

    public synchronized boolean deleteById(long id) throws IOException {
        List<Article> all = loadAll();
        boolean removed = all.removeIf(a -> a.getId() == id);
        if (removed) saveAll(all);   // 영향받은 파티션만 재작성
        return removed;
    }

    /** 구 schema parquet 호환: 필드 자체가 없으면 null. */
    private static Object fieldOrNull(GenericRecord r, String name) {
        return r.getSchema().getField(name) != null ? r.get(name) : null;
    }
    private static String strOrNull(GenericRecord r, String name) {
        Object v = fieldOrNull(r, name);
        return v == null ? null : v.toString();
    }
}
