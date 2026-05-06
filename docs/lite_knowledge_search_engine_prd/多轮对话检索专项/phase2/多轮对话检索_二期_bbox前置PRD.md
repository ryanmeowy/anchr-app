# 多轮对话检索专项（二期）bbox 前置 PRD

更新时间：2026-05-06  
状态：Confirmed

## 1. 文档目标

本 PRD 定义 Phase2 启动前必须补齐的图片 OCR bbox 入库与查询能力。

Phase2 的图片预览目标不是仅打开原图，而是支持用户从 Top3 卡片跳转后，在原图中看到命中的 OCR 区域。当前项目已有 `kb_segment.bbox` 字段，但图片入库链路尚未稳定写入可用 bbox，因此需要把 bbox 能力作为 Phase2 前置能力单独交付。

## 2. 背景与问题

1. Phase2 主 PRD 要求卡片点击后可预览并定位命中片段。
2. 文本类资源可通过 `pageNo/chunkOrder/snippet` 做页级或 chunk 级定位。
3. 图片类资源需要依赖 OCR bbox 才能在原图上绘制命中框。
4. 当前图片 OCR segment 可以进入 `kb_segment`，但 bbox 未形成稳定协议、入库、查询和前端映射闭环。
5. 如果不先补齐 bbox，Phase2 图片链路只能长期降级为"原图 + 命中文本"，无法达到图片定位预期。

## 3. 目标

1. 建立统一 bbox 协议。
2. 图片 OCR 入库时写入可用 bbox。
3. `kb_segment` 能按 `segmentId` 查询 bbox 与原图尺寸。
4. 搜索结果、`resultCards`、preview API 能透传图片命中 anchor。
5. 前端图片预览可按原图尺寸映射 bbox 到渲染尺寸。
6. bbox 缺失或无效时安全降级，不绘制错误框，并记录数据质量问题。

## 4. 范围

### 4.1 In Scope

1. bbox 坐标协议定稿。
2. OCR 混合模式实现：
   - 传统 OCR API：获取 paragraph/block 级 bbox 坐标与文本内容。直接使用 API 原生的段落层级（如阿里云 `RecognizeGeneral` 返回的 `paragraphs → words` 结构），不做自行合并或拆分。
   - LLM OCR：对传统 OCR 的 block 文本进行语义增强（纠正误识别、补全上下文）。
   - 文本对齐约束：LLM 增强后的文本与传统 OCR 原文本相似度低于阈值时，block 保留传统 OCR 原文，避免 bbox 位置与展示文本不一致。
3. 图片 OCR 入库拆分为"一图多 segment"：使用传统 OCR API 原生返回的 paragraph/block 层级结构作为 segment 粒度，无需自行实现合并或拆分逻辑。每个 OCR paragraph/block 对应一个 `IMAGE_OCR_BLOCK` segment，该 segment 的 bbox 取该段落内所有文字块的外接矩形。
4. `KbSegmentDocument.bbox` 从 `List<Integer>` 重构为结构化对象（`x/y/width/height/unit`）。
5. 补充 `imageWidth/imageHeight` 字段，数据来源：优先 OCR API 返回；若 API 不返回，通过读取图片文件 header 获取（`javax.imageio.ImageIO` 或 metadata-extractor）。
6. 搜索结果 DTO、`ResultHitDTO.anchor`、`PreviewAnchorDTO` 透传结构化 bbox。
7. `GET /api/v1/preview/segments/{segmentId}` 返回 bbox anchor。
8. 前端图片预览基于 bbox 绘制命中框。
9. bbox 缺失、越界、宽高异常、尺寸缺失时安全降级。
10. 样例图片定位验收记录。

### 4.2 Out of Scope

1. 人工标注、标注编辑、协同批注。
2. word 级精准高亮。
3. 多 bbox 合并策略的复杂排序。
4. 图片裁剪图生成。
5. bbox 反向修正 OCR 结果。
6. per-block 向量嵌入（OCR block 不含 embedding，仅靠文本检索命中；caption segment 保持唯一，携带图片向量用于向量检索）。

## 5. bbox 协议

### 5.1 坐标格式

统一使用原图像素坐标，结构化格式：

```json
{
  "x": 120,
  "y": 80,
  "width": 360,
  "height": 48,
  "unit": "PIXEL"
}
```

约束：
1. `x/y` 表示左上角坐标。
2. `width/height` 表示矩形宽高。
3. 坐标基于原图未缩放尺寸。
4. `unit` 固定为 `PIXEL`。
5. OCR provider 若返回四点坐标，先归一化为外接矩形。
6. 不再在新增协议中使用语义不清的 `List<Integer>` 作为对外格式。
7. ES 存储使用结构化 object 类型（`x/y/width/height/unit` 各为独立 integer/keyword 字段），不再使用单值 integer 数组。

### 5.2 原图尺寸

图片 anchor 必须包含原图尺寸：

```json
{
  "bbox": {
    "x": 120,
    "y": 80,
    "width": 360,
    "height": 48,
    "unit": "PIXEL"
  },
  "imageWidth": 1920,
  "imageHeight": 1080
}
```

`imageWidth/imageHeight` 数据来源：
1. 优先从传统 OCR API 返回中获取（如阿里云 `RecognizeGeneral` 通常返回页面宽高）。
2. 若 OCR API 不返回尺寸，通过读取图片文件 header 获取（如 `javax.imageio.ImageIO.read()` → `getWidth()`/`getHeight()`）。
3. 两个来源均不可用时，不写入 `imageWidth/imageHeight`，走降级策略。

前端按 `renderedWidth / imageWidth` 和 `renderedHeight / imageHeight` 计算映射比例。

## 6. 数据链路

### 6.1 入库链路

**OCR 混合模式：**

```
图片上传
  ├─→ 传统 OCR API（如阿里云 RecognizeGeneral）
  │     └─→ 返回: paragraphs[{text: "...", words[{x, y, w, h}...]}]
  │           取 paragraph 级文本和 paragraph 内 words 的外接矩形作为 bbox
  │           + imageWidth/imageHeight（如有）
  │
  ├─→ LLM OCR（现有 MultiModalConversation）
  │     └─→ 全量文本语义增强
  │
  └─→ 文本对齐
        └─→ 将 LLM 增强文本与原 block 文本逐条比对
            相似度 ≥ 阈值 → 使用 LLM 增强文本
            相似度 < 阈值 → 保留传统 OCR 原文
            记录并告警严重漂移的 block
```

**segment 拆分规则：**

- 按传统 OCR API 返回的 paragraph 层级拆分，每个 paragraph → 1 个 `IMAGE_OCR_BLOCK` segment。
- 该 segment 的 bbox 取 paragraph 内所有 words 的外接矩形（`x=min(word.x)`, `y=min(word.y)`, `width=max(word.x+word.w)-x`, `height=max(word.y+word.h)-y`）。
- 每个 segment 携带：
  - `segmentId`: `{assetId}:ocr:{paragraphIndex}`
  - `ocrText`: 该 paragraph 的文本（经 LLM 增强或保留原文）
  - `bbox`: `{x, y, width, height, unit: "PIXEL"}`
  - `imageWidth` / `imageHeight`
- `IMAGE_CAPTION` segment 保持不变，仅 1 个，携带图片向量用于向量检索。

**存储开销说明：**

OCR block segment 不含 embedding 字段（embedding 仅在 caption segment 中），拆分后增加的仅是 block 元数据。以每图 15 个 paragraph 级 segment 计算，额外元数据约 200B × 14 ≈ 2.8KB，远小于一个 1024 维 embedding 向量（~4KB）。不存在"一份图片的向量存储在多个文档中"的问题。

**ES mapping 变更：**

`bbox` 字段从 `"type": "integer"` 重构为：

```json
"bbox": {
  "type": "object",
  "properties": {
    "x": { "type": "integer" },
    "y": { "type": "integer" },
    "width": { "type": "integer" },
    "height": { "type": "integer" },
    "unit": { "type": "keyword" }
  }
},
"imageWidth": { "type": "integer" },
"imageHeight": { "type": "integer" }
```

入库字段清单（`IMAGE_OCR_BLOCK` segment）：

- `segmentId`
- `assetId`
- `assetType`
- `segmentType`
- `ocrText`
- `sourceRef`
- `bbox`
- `imageWidth`
- `imageHeight`
- `title`（文件名，作为冗余）
- `thumbnail`
- `ocrSummary`（全图 OCR 摘要，clip 至 180 字）
- `tags`
- `createdAt`

### 6.2 检索链路

1. 检索命中图片 OCR segment（通过 `ocrText` 文本匹配）。
2. `KbSearchResultDTO` 或二期 `ResultHitDTO` 携带 `anchor.bbox`（结构化对象）。
3. Top3 卡片聚合时保留 `primaryHit.anchor.bbox`。

### 6.3 预览链路

1. 前端点击图片卡片，使用 `primaryHit.segmentId`。
2. 前端请求 `GET /api/v1/preview/segments/{segmentId}`。
3. preview API 按 `segmentId` 查询稳定 segment 元数据。
4. 返回 `previewUrl/previewType/snippet/anchor`，其中 anchor 包含结构化 bbox 和 imageWidth/imageHeight。
5. 前端加载原图并按 bbox 绘制命中框。

## 7. 降级策略

以下情况不绘制 bbox：
1. bbox 为空。
2. `imageWidth/imageHeight` 为空或小于等于 0。
3. `x/y/width/height` 任一为空。
4. `width/height` 小于等于 0。
5. bbox 超出原图边界且无法安全裁剪。
6. bbox 与渲染尺寸映射后面积异常。

降级展示：
1. 打开原图。
2. 展示 OCR 命中文本。
3. 展示"命中区域暂不可定位"提示。
4. 记录数据质量指标，避免静默失败。

**bbox 质量指标：**

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `smartvision.ingestion.bbox.write_success` | Counter | bbox 成功写入的 OCR block 数 |
| `smartvision.ingestion.bbox.missing` | Counter | bbox 为空的 OCR block 数 |
| `smartvision.ingestion.bbox.out_of_bounds` | Counter | bbox 越界的 OCR block 数 |
| `smartvision.ingestion.bbox.image_size_missing` | Counter | imageWidth/Height 缺失的图片数 |
| `smartvision.ingestion.ocr.text_drift` | Counter | LLM 增强文本与原 OCR 文本相似度低于阈值的 block 数 |

## 8. 接口影响

### 8.1 ResultHitDTO.anchor

图片命中结构（与 5.2 协议一致）：

```json
{
  "segmentId": "asset_001:ocr:3",
  "snippet": "设备故障代码 E102",
  "hitType": "IMAGE_OCR_BLOCK",
  "anchor": {
    "bbox": {
      "x": 120,
      "y": 80,
      "width": 360,
      "height": 48,
      "unit": "PIXEL"
    },
    "imageWidth": 1920,
    "imageHeight": 1080
  }
}
```

### 8.2 PreviewSegmentDTO.anchor

preview API 返回同一套 anchor 结构，避免前端分别适配搜索结果和预览结果。

## 9. 验收标准

1. bbox 协议文档定稿，字段语义无歧义。
2. 至少 10 张图片样例完成 OCR bbox 入库。
3. 有 bbox 的图片 OCR segment 可通过 `segmentId` 查询到 bbox 与原图尺寸。
4. 搜索结果中的图片 OCR 命中可返回结构化 `anchor.bbox`。
5. `resultCards.primaryHit.anchor.bbox` 可透传到前端。
6. preview API 可返回图片命中的 `anchor.bbox/imageWidth/imageHeight`。
7. 前端图片预览可按 bbox 在原图上绘制命中框。
8. bbox 缺失、越界或尺寸异常时不绘制错误框，并展示 OCR 命中文本。
9. 样例图片 bbox 定位成功率达到 90% 以上。
10. 相关单测或接口测试覆盖有 bbox、无 bbox、越界 bbox、尺寸缺失四类场景。

## 10. 任务建议

| 任务ID | 标题 | 依赖 | 交付物 |
|---|---|---|---|
| B2-01 | bbox 协议定稿 | 无 | bbox 结构化字段、单位、坐标系、降级规则 |
| B2-02 | 传统 OCR API 接入 | B2-01 | 传统 OCR provider 选型验证 + 坐标解析适配 |
| B2-03 | ES mapping 重构 + DTO/Model 变更 | B2-01 | `KbSegmentDocument.bbox` 结构化、`imageWidth`/`imageHeight` 新增、`es-kb-segment-mapping.json` 更新 |
| B2-04 | OCR 混合模式入库 | B2-02, B2-03 | 传统 OCR（bbox + text）+ LLM OCR（增强）+ 文本对齐约束；一图多 segment 写入 `kb_segment` |
| B2-05 | bbox 查询与 DTO 透传 | B2-04 | 搜索结果、ResultHit、PreviewAnchor 字段透传结构化 bbox |
| B2-06 | 图片预览 bbox 映射 | B2-05 | 前端 bbox 绘制、安全降级、质量指标埋点 |
| B2-07 | bbox 回归与样例验收 | B2-06 | 测试用例与样例定位记录 |

## 11. 工作量评估

| 任务 | 工时 |
|------|------|
| B2-01 bbox 协议定稿 + DTO/Model 变更 | 1 天 |
| B2-02 传统 OCR API 选型验证 + PoC | 1 天 |
| B2-03 ES mapping 重构 + `KbSegmentDocument` 字段变更 | 1 天 |
| B2-04 OCR 混合模式实现（传统 OCR + LLM 增强 + 文本对齐 + 一图多 segment） | 2~3 天 |
| B2-05 bbox 查询与 DTO 透传（preview API + ResultHit/PreviewAnchor） | 1 天 |
| B2-06 降级逻辑 + 质量指标 + 前端 bbox 绘制 | 1 天 |
| B2-07 测试（有 bbox / 无 bbox / 越界 / 尺寸缺失） | 1 天 |
| **合计** | **8~10 天** |

说明：传统 OCR API 选型验证（B2-02）和 ES mapping 重构（B2-03）可并行进行。

## 12. 部署依赖

1. ES 索引 mapping 有 breaking change（`bbox` 类型从 `integer` 改为 `object`，新增 `imageWidth`/`imageHeight`），需创建新版本索引 + alias 切换。
2. 传统 OCR API（如阿里云 `RecognizeGeneral`）需要独立的 API 配置（endpoint / access key），与现有 LLM-OCR 的 DashScope API 不同。

## 13. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| OCR provider 坐标格式不稳定 | bbox 无法统一映射 | 先归一化为外接矩形，只支持 block/line 级 |
| LLM 增强文本与传统 OCR block 文本漂移 | bbox 位置与展示文本不一致 | 逐 block 计算文本相似度，低于阈值则保留原文；记录 `text_drift` 指标 |
| 传统 OCR 与 LLM OCR 两套 API 配置复杂度 | 部署配置增加 | 统一放入 `application-cloud-aliyun.yaml`，明确新增的 OCR API 配置项 |
| bbox 超出原图边界 | 前端画错框 | 前后端都做边界校验，异常时降级 |
| 原图尺寸缺失 | 无法比例映射 | OCR API 优先；fallback 到图片 header 读取；都不行时不画框 |
| 多 OCR 块命中同一图片 | 命中框选择不稳定 | Phase2 默认展示 `primaryHit` bbox，多个 bbox 展示作为后续增强 |
| ES mapping breaking change | 旧索引与新 mapping 不兼容 | 新版本索引 + alias 切换，旧索引保留可回滚 |
