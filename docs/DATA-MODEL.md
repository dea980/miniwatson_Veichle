# MiniWatson — Data Model

The lifecycle of an `Article` from Wikipedia API to Parquet on disk.

---

## 1. Java Model — `Article`

```java
@Data
public class Article {
    private Long id;
    private String title;
    private String summary;
    private String url;
    private LocalDateTime ingestedAt;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Float> embedding;   // 768-dim, nomic-embed-text
}
```

| Field        | Type            | Source                                           | Notes                             |
|--------------|-----------------|--------------------------------------------------|-----------------------------------|
| `id`         | `Long`          | Generated (`store.size() + 1`)                   | Monotonic, not durable PK         |
| `title`      | `String`        | Wikipedia `title`                                | UTF-8                             |
| `summary`    | `String`        | Wikipedia `/page/summary` → `extract` field      | Plain text, no markup             |
| `url`        | `String`        | Wikipedia `content_urls.desktop.page`            | Canonical                         |
| `ingestedAt` | `LocalDateTime` | Server clock at ingest time                      | ISO-8601 when serialized          |
| `embedding`  | `List<Float>`   | `nomic-embed-text` over `summary`                | 768 floats, hidden from API       |

---

## 2. Anti-Corruption Layer — Wikipedia DTO

Wikipedia's JSON has dozens of fields you don't need. The internal DTO ignores them all:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaSummary {
    private String title;
    private String extract;
    private ContentUrls content_urls;
    // many other Wikipedia fields are silently dropped
}
```

**Why**: protects internal domain model from upstream schema churn. Same principle as DDD anti-corruption layer.

---

## 3. Avro Schema — `article.avsc`

```json
{
  "type": "record",
  "name": "Article",
  "namespace": "miniwatson.schema",
  "fields": [
    { "name": "id",         "type": "long" },
    { "name": "title",      "type": "string" },
    { "name": "summary",    "type": "string" },
    { "name": "url",        "type": "string" },
    { "name": "ingestedAt", "type": "string" },
    { "name": "embedding",  "type": { "type": "array", "items": "float" } }
  ]
}
```

### Why namespace ≠ Java package

`namespace: "miniwatson.schema"` is **intentionally non-Java**.

If you use `com.miniwatson.data`, Avro reflection tries to locate the class `com.miniwatson.data.Article` at read-time and cast `GenericRecord` to it, producing:

```
ClassCastException: com.miniwatson.data.Article
                   cannot be cast to org.apache.avro.generic.IndexedRecord
```

`miniwatson.schema.Article` doesn't exist as a class, so Avro safely returns `GenericRecord` and the application maps it to POJO explicitly.

---

## 4. Parquet Layout

| Property            | Value                              |
|---------------------|------------------------------------|
| Format              | Parquet (Apache 1.14.0)            |
| Schema source       | Avro (`article.avsc`)              |
| Compression         | SNAPPY                             |
| Row group size      | Default (128 MB)                   |
| Page size           | Default (1 MB)                     |
| Data model          | `GenericData.get()` (writer only)  |
| File path           | `./data/articles.parquet`          |
| Side-car            | `.articles.parquet.crc` (checksum) |

### Observed compression

| Storage         | Size (6 articles) | Ratio |
|-----------------|------------------:|------:|
| JSON (raw)      | 54.0 KB           | 1.0×  |
| Parquet+SNAPPY  |  7.8 KB           | 6.9×  |

The win comes mostly from columnar dictionary encoding on `embedding` (768 floats per row).

---

## 5. Read / Write APIs (`ArticleParquetStore`)

```java
public void saveAll(List<Article> articles) throws IOException {
    // 1. ensure ./data/ exists
    // 2. delete existing parquet (Parquet has no overwrite)
    // 3. AvroParquetWriter with GenericData.get() + SNAPPY
    // 4. iterate → GenericRecord.put(...) → writer.write(record)
}

public List<Article> loadAll() throws IOException {
    // 1. early-return [] if file missing
    // 2. AvroParquetReader (no withDataModel — Reader builder doesn't have it)
    // 3. iterate → map GenericRecord → POJO
    // 4. embedding: Object → List<?> → for each Number → float
}
```

### Why no `withDataModel` on the Reader

`ParquetReader.Builder` (parquet-avro 1.14.0) doesn't expose `withDataModel`. The namespace trick in §3 is sufficient — Avro falls back to `GenericRecord` when no Java class matches the schema name.

`AvroParquetWriter.Builder` **does** expose `withDataModel`, and we pass `GenericData.get()` to be explicit on the write side.

---

## 6. Data Lifecycle

```
Wikipedia (REST)
   │  GET /api/rest_v1/page/summary/{title}
   │  + User-Agent: MiniWatson/1.0 (mailto:kdea989@gmail.com)
   ▼
WikipediaSummary (DTO, ignoreUnknown)
   │
   ▼
Article (POJO)  ◄── embedding via Ollama nomic-embed-text
   │
   ▼
GenericRecord (Avro)
   │
   ▼
articles.parquet (Parquet+SNAPPY, columnar)
   │
   ▼ (loadAll)
GenericRecord
   │
   ▼
Article (POJO)
   │
   ▼
RagService cosine similarity
```

---

## 7. Operational Notes

| Topic                  | Detail                                                                   |
|------------------------|--------------------------------------------------------------------------|
| Hadoop on Java 21      | Spring Boot plugin sets `-Djava.security.manager=allow`                  |
| Parquet overwrite      | Not supported by spec — `saveAll` deletes file first                     |
| CRC sidecar            | `.articles.parquet.crc` written by Hadoop FileSystem; safe to delete     |
| Backup convention      | `.backup` suffix is convention only — not part of the read path          |
| Embedding hidden       | `@JsonProperty(WRITE_ONLY)` keeps 768 floats out of API responses        |
| Anti-corruption        | `@JsonIgnoreProperties(ignoreUnknown=true)` on all Wikipedia DTOs        |

---

## 8. Production Evolution Path

| Today                          | Production                                       |
|--------------------------------|--------------------------------------------------|
| Single Parquet file rewrite    | Iceberg or Delta Lake — append-only, ACID        |
| 768-dim cosine in JVM          | pgvector / FAISS / Milvus                        |
| `ingestedAt` as string         | `timestamp-millis` logical type in Avro          |
| `id` from `size()+1`           | Snowflake ID or DB sequence                      |
| Single tenant                  | `tenantId` field + partitioned by tenant         |
| No schema evolution            | Avro forward/backward compat via schema registry |

---

## 9. Sample Article (Parquet → JSON projection)

```json
{
  "id": 1,
  "title": "Retrieval-augmented generation",
  "summary": "Retrieval-augmented generation (RAG) is a technique...",
  "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
  "ingestedAt": "2026-06-05T00:48:40.411367",
  "embedding": "/* 768 floats — hidden from API */"
}
```
