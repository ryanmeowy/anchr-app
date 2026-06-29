# Search API

## POST /api/v1/search/kb

知识库统一检索。文本 + 图片混合召回，RRF 融合排序，cross-encoder 重排和 LLM 回答。

### Request

```json
{
  "query": "string",           // 必填，≤200字
  "limit": 10,                 // 可选，1~200，最终返回条数（每路召回数由后端基于 limit 计算）
  "strategy": "KB_RRF_RERANK", // 可选 ≤32字，KB_RRF | KB_RRF_RERANK
  "kbIds": ["kb_1", "kb_2"],   // 可选，≤100个，空=全部知识库
  "assetTypes": ["PDF"],       // 可选，≤20个，资产类型过滤
  "hitTypes": ["TEXT"],        // 可选，≤20个，命中类型过滤
  "dateRange": {               // 可选，时间范围过滤
    "from": 1700000000000,
    "to": 1800000000000
  },
  "cursor": "...",             // 可选，翻页游标
  "sort": "score",             // 可选，≤32字，排序字段
  "withAnswer": true,          // 可选，是否生成 LLM 回答
  "answerMode": "STRICT"       // 可选，≤32字，回答模式，默认 STRICT
}
```

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "segmentId": "1234:chunk:0",
        "kbId": "5678",
        "assetId": "1234",
        "sourceRef": "anchr-dev/doc_xxx.pdf",
        "segmentType": "TEXT_CHUNK",
        "assetType": "PDF",
        "content": "MySQL 的架构分为 Server 层和存储引擎层...",
        "snippet": "MySQL 的<em>架构</em>分为 Server 层...",
        "pageNo": 3,
        "score": 0.92,
        "thumbnail": null,
        "ocrSummary": null,
        "resultType": "TEXT",
        "explain": {
          "strategyEffective": "KB_RRF_RERANK",
          "hitSources": ["CONTENT", "VECTOR"],
          "segments": {
            "keyword": false,
            "ocr": false,
            "tag": false,
            "vector": true
          }
        },
        "anchor": {
          "pageNo": 3,
          "chunkOrder": 0
        },
        "totalHits": 5,
        "topChunks": [
          {
            "segmentId": "1234:chunk:1",
            "snippet": "存储引擎层负责数据的存储和提取...",
            "pageNo": 3,
            "chunkOrder": 1
          }
        ]
      }
    ],
    "total": 42,
    "nextCursor": "...",
    "facets": {
      "assetType": [
        { "value": "PDF", "count": 30 },
        { "value": "IMAGE", "count": 12 }
      ]
    },
    "answer": {
      "answer": "MySQL 架构分为 Server 层和存储引擎层...",
      "citations": [
        {
          "citationIndex": 1,
          "segmentId": "1234:chunk:0",
          "assetId": "1234",
          "kbId": "5678",
          "fileName": "mysql.pdf",
          "pageNo": 3,
          "snippet": "MySQL 的架构...",
          "why": {
            "score": 0.92,
            "hitSources": ["VECTOR", "CONTENT"],
            "matchedBy": { "vector": true, "title": false, "content": true, "ocr": false },
            "matchSummary": "语义匹配 + 内容关键词命中 (score: 0.92)"
          }
        }
      ]
    },
    "suggestedQuestions": [
      "MySQL Server 层包含哪些核心组件？",
      "存储引擎层如何与 Server 层交互？",
      "InnoDB 和 MyISAM 在架构层面有何区别？"
    ],
    "insight": {
      "pipeline": {
        "keywordCandidates": 84,
        "vectorCandidates": 62,
        "fusedRetained": 38,
        "rerankAdopted": 8
      },
      "relevanceDistribution": { "high": 4, "medium": 3, "low": 1 },
      "risk": { "lowRelevanceCount": 1 },
      "hitSourceDistribution": {
        "vectorCount": 5, "contentCount": 3, "ocrCount": 1, "tagCount": 0, "titleCount": 2
      },
      "queryIntent": { "intent": "技术原理解释", "category": "FACTUAL", "fallback": false },
      "latencyMs": 340
    }
  }
}
```

### Fields

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | array | 本页检索结果 |
| `items[].segmentId` | string | 片段 ID |
| `items[].kbId` | string | 所属知识库 ID |
| `items[].assetId` | string | 所属资产 ID |
| `items[].sourceRef` | string | 源文件引用 |
| `items[].segmentType` | string | TEXT_CHUNK \| IMAGE_OCR_BLOCK |
| `items[].assetType` | string | PDF \| IMAGE \| TXT \| MARKDOWN |
| `items[].content` | string | 完整片段文本 |
| `items[].snippet` | string | 高亮摘要 |
| `items[].pageNo` | int \| null | 页码 |
| `items[].score` | double | 相关性分数 |
| `items[].resultType` | string | TEXT \| IMAGE \| MIXED |
| `items[].explain` | object | 命中解释 |
| `items[].explain.strategyEffective` | string | 实际策略 |
| `items[].explain.hitSources` | string[] | 命中来源 |
| `items[].totalHits` | int | 聚合到该资产的总命中数 |
| `items[].topChunks` | array | 同资产其他命中片段 |
| `items[].anchor` | object | 定位锚点 |
| `items[].anchor.pageNo` | int \| null | 页码 |
| `items[].anchor.chunkOrder` | int \| null | chunk 序号 |
| `total` | long | 总结果数 |
| `nextCursor` | string \| null | 下一页游标 |
| `facets` | object \| null | 分面统计 |
| `answer` | object \| null | LLM 回答（仅 withAnswer=true） |
| `answer.answer` | string | 回答文本 |
| `answer.citations` | array | 引用来源 |
| `answer.citations[].why` | object \| null | 引用原因（检索层面，非 LLM） |
| `answer.citations[].why.score` | double \| null | 相关性分数 |
| `answer.citations[].why.hitSources` | string[] | 命中路径：VECTOR \| CONTENT \| OCR \| TAG \| TITLE |
| `answer.citations[].why.matchedBy` | object \| null | 字段级命中明细 |
| `answer.citations[].why.matchedBy.vector` | boolean | 向量匹配 |
| `answer.citations[].why.matchedBy.title` | boolean | 标题命中 |
| `answer.citations[].why.matchedBy.content` | boolean | 内容命中 |
| `answer.citations[].why.matchedBy.ocr` | boolean | OCR 文本命中 |
| `answer.citations[].why.matchSummary` | string \| null | 人类可读摘要，如"语义匹配 + 内容关键词命中 (score: 0.92)" |
| `suggestedQuestions` | string[] \| null | LLM 生成的推荐追问（最多 3 个），失败时为空数组 |
| `insight` | object \| null | 检索洞察诊断数据 |
| `insight.pipeline` | object | 检索链路计数 |
| `insight.pipeline.keywordCandidates` | int | 关键词召回候选数 |
| `insight.pipeline.vectorCandidates` | int | 语义向量召回候选数 |
| `insight.pipeline.fusedRetained` | int | RRF 融合去重后保留数 |
| `insight.pipeline.rerankAdopted` | int | 重排后采纳数 |
| `insight.relevanceDistribution` | object | 证据相关性分布 |
| `insight.relevanceDistribution.high` | int | 高相关（score ≥ 0.8） |
| `insight.relevanceDistribution.medium` | int | 中相关（0.5 ≤ score < 0.8） |
| `insight.relevanceDistribution.low` | int | 低相关（score < 0.5） |
| `insight.risk` | object | 证据风险 |
| `insight.risk.lowRelevanceCount` | int | 低相关证据条数 |
| `insight.hitSourceDistribution` | object | 命中来源分布统计 |
| `insight.hitSourceDistribution.vectorCount` | int | 语义命中次数 |
| `insight.hitSourceDistribution.contentCount` | int | 内容命中次数 |
| `insight.hitSourceDistribution.ocrCount` | int | OCR 命中次数 |
| `insight.hitSourceDistribution.tagCount` | int | 标签命中次数 |
| `insight.hitSourceDistribution.titleCount` | int | 标题命中次数 |
| `insight.queryIntent` | object \| null | 查询意图（LLM 解析） |
| `insight.queryIntent.intent` | string \| null | 意图描述，如"技术原理解释" |
| `insight.queryIntent.category` | string \| null | 意图类别：HOW-TO \| FACTUAL \| DEFINITION \| COMPARISON \| TROUBLESHOOTING \| OTHER |
| `insight.queryIntent.fallback` | boolean | LLM 解析失败时为 true |
| `insight.latencyMs` | long | 检索耗时（毫秒） |
