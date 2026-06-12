# anchr-docling 接入方案

## 一、当前入库链路

见上一版。核心：10 个 Parser → TextParseResult → TextChunkSplitter → chunks → embedding → ES。

## 二、anchr-docling API（已确认）

```
POST /v1/parse
Content-Type: application/json

{
  "requestId": "task_xxx:item_xxx",
  "sourceUrl": "https://...",       // OSS presigned download URL
  "fileName": "prd.pdf",
  "options": {
    "outputFormat": "chunks",        // 用 chunks 模式, 直接拿预分块结果
    "ocr": false,                    // 扫描件才开
    "ocrFallback": false,
    "tableStructure": true,
    "chunkMinTokens": 400,
    "chunkMaxTokens": 800
  },
  "oss": {                           // 可选, 导出 PDF 内嵌图片
    "endpoint": "oss-cn-hangzhou.aliyuncs.com",
    "bucket": "anchr-dev",
    "basePath": "docling-images",
    "encryptedCredentials": {
      "iv": "<base64>",
      "ciphertext": "<base64>"       // AES-256-CBC, 明文为 STS JSON
    }
  }
}
```

### chunks 模式响应

```json
{
  "requestId": "task_xxx:item_xxx",
  "parser": "docling",
  "format": "chunks",
  "chunks": [
    {
      "chunkId": "chunks/0",
      "type": "section",
      "text": "## 架构概述\n\n...",       // markdown, 喂 LLM
      "textPlain": "架构概述 ...",          // 纯文本, 建 embedding
      "pageRange": [1, 2],
      "charCount": 85,
      "source": "native",                  // 或 "custom"
      "bboxes": [...],                     // 仅 native chunker
      "headings": ["架构概述"]              // 仅 native chunker
    }
  ],
  "images": [                              // 仅提供 oss 凭证时
    {
      "url": "https://bucket.oss-cn-hangzhou.aliyuncs.com/docling-images/pictures_0.png",
      "pageNo": 1,
      "alt": "图 1：架构总览"
    }
  ],
  "warnings": null
}
```

## 三、接入方案

### 3.1 替换范围

```
之前:
  OSS presigned URL
    → TextAssetContentLoader (下载)
    → 10个 TextParserPort 实现 (解析)
    → TextParserRouter (路由)
    → TextParseResult (中间模型)
    → TextChunkSplitter (分块, 800字/120重叠)
    → TextChunk + embedding

之后:
  OSS presigned URL + AES 加密的 STS
    → DoclingClient (HTTP 调 /v1/parse)
    → chunks[].textPlain → embedding
    → TextChunk 直接写入 ES
```

### 3.2 新增文件

| 文件 | 说明 |
|---|---|
| `DoclingClient.java` | HTTP 客户端, 调 `POST /v1/parse` |
| `DoclingChunkMapper.java` | docling chunk → `TextChunk` 映射 |

### 3.3 删除文件 (~17)

| 文件 | 原因 |
|---|---|
| `TextParserPort.java` | 接口不再需要 |
| `TextParserRouter.java` | 路由不再需要 |
| `PdfTextParser.java` | 被 docling 替代 |
| `PlainTextParser.java` | 同上 |
| `MarkdownTextParser.java` | 同上 |
| `DocxTextParser.java` | 同上 |
| `PptxTextParser.java` | 同上 |
| `SpreadsheetTextParser.java` | 同上 |
| `UrlHtmlTextParser.java` | 同上 |
| `ZipTextParser.java` | 同上 |
| `NoopTextParser.java` | 同上 |
| `StructuredTextParserSupport.java` | POI 基类 |
| `TextParserSupport.java` | 解析器基类 |
| `TextChunkSplitter.java` | docling 已做分块 |
| `TextAssetContentLoader.java` | docling 直接下载 sourceUrl |
| `TextParseResult.java` | 中间模型 |
| `TextParseUnit.java` | 中间模型 |

### 3.4 修改文件

| 文件 | 变更 |
|---|---|
| `IngestionTaskProcessorImpl.processText()` | 简化：OSS URL → docling → chunks → embedding |
| `IngestionCapabilityService` | 简化：不再需要 fileType 校验（docling 自己判断） |
| `pom.xml` | 移除 PDFBox, POI, Jsoup, Commons CSV |

### 3.5 简化后的 processText()

```java
private void processText(String kbId, String taskId, IngestionTaskItem item,
                         DocumentAsset document, String userId) {
    LocalDateTime now = LocalDateTime.now();
    updateRunning(kbId, taskId, item.getId(), IngestionStage.PARSE, 30, userId);
    documentAssetRepository.updateStatuses(kbId, document.getId(),
            DocumentParseStatus.RUNNING.name(), DocumentIndexStatus.PENDING.name(), userId, now);

    // 1. 构建 OSS 图片上传凭证 (AES 加密 STS)
    DoclingClient.OssCredentials oss = buildOssCredentials(document.getObjectKey());
    
    // 2. 调 docling: 一步完成下载 + 解析 + 分块 + 图片上传
    String sourceUrl = objectStoragePort.buildDownloadUrl(document.getObjectKey());
    DoclingClient.ParseResult result = doclingClient.parse(sourceUrl, document.getFileName(), oss);

    // 3. docling chunk → TextChunk (textPlain → embedding)
    List<TextChunk> chunks = DoclingChunkMapper.toTextChunks(document, result.chunks());

    // 4. embedding
    updateRunning(kbId, taskId, item.getId(), IngestionStage.EMBED, 65, userId);
    enrichTextEmbeddings(chunks);

    // 5. 写入 ES
    updateRunning(kbId, taskId, item.getId(), IngestionStage.INDEX, 85, userId);
    documentAssetRepository.updateStatuses(kbId, document.getId(),
            DocumentParseStatus.SUCCESS.name(), DocumentIndexStatus.RUNNING.name(), userId, now);
    textSegmentRepository.save(document.getId(), chunks);

    // 6. docling 返回的图片 URL 写入 IMAGE_CAPTION segment (如果开启了 oss 导出)
    if (result.images() != null) {
        indexDoclingImages(document, result.images(), userId);
    }

    completeItem(kbId, taskId, item.getId(), document.getId(), chunks.size(), userId);
}
```

### 3.6 Stage 变化

```
之前: UPLOAD → PARSE → CHUNK → EMBED → INDEX → ASKABLE (6 阶段)
之后: UPLOAD → PARSE → EMBED → INDEX → ASKABLE          (5 阶段, CHUNK 合并到 PARSE)
```

### 3.7 DoclingClient 设计

```java
public class DoclingClient {
    
    private final String baseUrl;         // http://127.0.0.1:8091
    private final HttpClient httpClient;

    public ParseResult parse(String sourceUrl, String fileName, OssCredentials oss) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceUrl", sourceUrl);
        body.put("fileName", fileName);
        body.put("options", Map.of(
            "outputFormat", "chunks",
            "chunkMinTokens", 400,
            "chunkMaxTokens", 800
        ));
        if (oss != null) body.put("oss", oss.toMap());

        JsonNode response = httpClient.post("/v1/parse", body);
        
        List<DoclingChunk> chunks = new ArrayList<>();
        for (JsonNode node : response.path("chunks")) {
            chunks.add(new DoclingChunk(
                node.path("text").asText(),
                node.path("textPlain").asText(),
                node.path("pageRange")
            ));
        }
        
        // images (optional)
        List<DoclingImage> images = null;
        if (response.has("images") && !response.get("images").isNull()) {
            images = parseImages(response.get("images"));
        }
        return new ParseResult(chunks, images);
    }
    
    public record OssCredentials(String endpoint, String bucket, String basePath,
                                  String iv, String ciphertext) {}
    public record DoclingChunk(String text, String textPlain, List<Integer> pageRange) {}
    public record DoclingImage(String url, int pageNo, String alt) {}
    public record ParseResult(List<DoclingChunk> chunks, List<DoclingImage> images) {}
}
```

### 3.8 chunk 映射

| docling chunk 字段 | TextChunk 字段 |
|---|---|
| `chunkId` | `segmentId` |
| `text` (markdown) | `chunkText` (用于 LLM 回答) |
| `textPlain` | 用于 embedding (去格式纯文本) |
| `pageRange[0]` | `pageNo` |

## 四、图片处理

两个路径并行：

1. **docling 提取 PDF 内嵌图片** → 开启 OSS 凭证后, docling 直接上传到 OSS, 返回 URL。Java 侧写入 IMAGE_CAPTION segment
2. **用户直接上传图片** (jpg/png) → 现有 `processImage()` 逻辑不变（embedding + AI 标签 + ES）

## 五、预估影响

| 类别 | 数量 |
|---|---|
| 新增 | 2 文件 (DoclingClient + DoclingChunkMapper) |
| 删除 | ~17 文件 (10 Parser + Router + Splitter + 2 Support + 2 Model) |
| 修改 | 2 文件 (IngestionTaskProcessorImpl + pom.xml) |
| 净减少 | ~15 文件 |
| pom 依赖 | 移除 PDFBox, POI, Jsoup, Commons CSV |

## 六、加密密钥

双方配置同一把 AES-256 密钥：

```yaml
# Spring Boot — application.yaml
app:
  docling:
    base-url: http://127.0.0.1:8091
    encrypt-key: ${ANCHR_DOCLING_OSS_ENCRYPT_KEY}
```

```bash
# docling sidecar
export ANCHR_DOCLING_OSS_ENCRYPT_KEY="<32 字节密钥>"
```
