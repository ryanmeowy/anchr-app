# Phase 2 E1：协议兼容与 Anchor/BBox 规范

## 1. 目标

在不影响现有调用方的前提下，将 `/api/v1/search/kb` 输出升级为 Phase 2 E1 统一协议。

## 2. 接口兼容策略（P2-03）

1. 查询协议扩展为：
   - `query`（必填）
   - `topK`（可选）
   - `limit`（可选）
   - `strategy`（可选，当前支持 `KB_RRF`、`KB_RRF_RERANK`）
2. 结果协议扩展为：
   - `segmentId/assetId/assetType/segmentType/content/score/explain/sourceRef/anchor/thumbnail/ocrSummary`
3. 兼容字段保留：
   - `resultType`（兼容旧字段，等价于 `segmentType`）
   - `snippet`（兼容旧字段，作为 `content` 的短摘要）
   - `pageNo`（兼容旧字段，等价于 `anchor.pageNo`）
4. 旧接口不受影响：
   - `/api/v1/vision/*` 保持原有行为。

## 3. Anchor/BBox 规范（P2-03A）

`anchor` 结构定义：

- `pageNo: Integer`
- `chunkOrder: Integer`
- `bbox: Integer[] | null`

约束：

1. 文本片段（`TEXT_CHUNK`）：
   - 必须可回传 `pageNo + chunkOrder`（允许 `pageNo` 为空）。
   - `bbox` 固定为空。
2. 图片 OCR 片段（`IMAGE_OCR_BLOCK`）：
   - 支持 `bbox` 字段。
   - 当前无坐标来源时，`bbox` 允许为空，不做猜测填充。

## 4. kb_segment 映射变更

新增字段：

- `bbox`：`integer`（数组）
- `thumbnail`：`keyword`
- `ocrSummary`：`text`（与 `contentText/ocrText` 同 analyzer）

说明：

- `thumbnail` 当前优先使用 `sourceRef` 回填，保证字段可用。
- `ocrSummary` 当前由 OCR 文本截断生成，后续可替换为专用摘要链路。
