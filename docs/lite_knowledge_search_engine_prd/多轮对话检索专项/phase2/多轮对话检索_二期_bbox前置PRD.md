# 多轮对话检索专项（二期）bbox 前置 PRD

更新时间：2026-05-06  
状态：Draft for Alignment

## 1. 文档目标

本 PRD 定义 Phase2 启动前必须补齐的图片 OCR bbox 入库与查询能力。

Phase2 的图片预览目标不是仅打开原图，而是支持用户从 Top3 卡片跳转后，在原图中看到命中的 OCR 区域。当前项目已有 `kb_segment.bbox` 字段，但图片入库链路尚未稳定写入可用 bbox，因此需要把 bbox 能力作为 Phase2 前置能力单独交付。

## 2. 背景与问题

1. Phase2 主 PRD 要求卡片点击后可预览并定位命中片段。
2. 文本类资源可通过 `pageNo/chunkOrder/snippet` 做页级或 chunk 级定位。
3. 图片类资源需要依赖 OCR bbox 才能在原图上绘制命中框。
4. 当前图片 OCR segment 可以进入 `kb_segment`，但 bbox 未形成稳定协议、入库、查询和前端映射闭环。
5. 如果不先补齐 bbox，Phase2 图片链路只能长期降级为“原图 + 命中文本”，无法达到图片定位预期。

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
2. OCR provider 输出到标准 bbox 的归一化适配。
3. 图片 OCR segment 写入 `kb_segment.bbox`。
4. 补充 `imageWidth/imageHeight` 或等价原图尺寸字段。
5. 搜索结果 DTO、`ResultHitDTO.anchor`、`PreviewAnchorDTO` 透传 bbox。
6. `GET /api/v1/preview/segments/{segmentId}` 返回 bbox anchor。
7. 前端图片预览基于 bbox 绘制命中框。
8. bbox 缺失、越界、宽高异常、尺寸缺失时安全降级。
9. 样例图片定位验收记录。

### 4.2 Out of Scope

1. 人工标注、标注编辑、协同批注。
2. word 级精准高亮。
3. 多 bbox 合并策略的复杂排序。
4. 图片裁剪图生成。
5. bbox 反向修正 OCR 结果。
6. 历史已入库图片的批量回填，除非另行安排数据修复任务。

## 5. bbox 协议

### 5.1 坐标格式

统一使用原图像素坐标，推荐结构：

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

前端按 `renderedWidth / imageWidth` 和 `renderedHeight / imageHeight` 计算映射比例。

## 6. 数据链路

### 6.1 入库链路

1. 图片上传后执行 OCR。
2. OCR 返回文本块与坐标。
3. 坐标归一化为标准 bbox。
4. 图片 OCR segment 写入 `kb_segment`：
- `segmentId`
- `assetId`
- `assetType`
- `segmentType`
- `ocrText`
- `sourceRef`
- `bbox`
- `imageWidth`
- `imageHeight`

### 6.2 检索链路

1. 检索命中图片 OCR segment。
2. `KbSearchResultDTO` 或二期 `ResultHitDTO` 携带 `anchor.bbox`。
3. Top3 卡片聚合时保留 `primaryHit.anchor.bbox`。

### 6.3 预览链路

1. 前端点击图片卡片，使用 `primaryHit.segmentId`。
2. 前端请求 `GET /api/v1/preview/segments/{segmentId}`。
3. preview API 按 `segmentId` 查询稳定 segment 元数据。
4. 返回 `previewUrl/previewType/snippet/anchor`。
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
3. 展示“命中区域暂不可定位”提示。
4. 记录数据质量指标或日志，避免静默失败。

## 8. 接口影响

### 8.1 ResultHitDTO.anchor

图片命中建议结构：

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
4. 搜索结果中的图片 OCR 命中可返回 `anchor.bbox`。
5. `resultCards.primaryHit.anchor.bbox` 可透传到前端。
6. preview API 可返回图片命中的 `anchor.bbox/imageWidth/imageHeight`。
7. 前端图片预览可按 bbox 在原图上绘制命中框。
8. bbox 缺失、越界或尺寸异常时不绘制错误框，并展示 OCR 命中文本。
9. 样例图片 bbox 定位成功率达到 90% 以上。
10. 相关单测或接口测试覆盖有 bbox、无 bbox、越界 bbox、尺寸缺失四类场景。

## 10. 任务建议

| 任务ID | 标题 | 依赖 | 交付物 |
|---|---|---|---|
| B2-01 | bbox 协议定稿 | 无 | bbox 字段、单位、坐标系、降级规则 |
| B2-02 | OCR bbox 解析适配 | B2-01 | OCR provider 坐标归一化逻辑 |
| B2-03 | 图片 segment 入库写入 bbox | B2-02 | `kb_segment` 写入 bbox 与原图尺寸 |
| B2-04 | bbox 查询与 DTO 透传 | B2-03 | 搜索结果、ResultHit、PreviewAnchor 字段 |
| B2-05 | 图片预览 bbox 映射 | B2-04 | 前端 bbox 绘制与安全降级 |
| B2-06 | bbox 回归与样例验收 | B2-05 | 测试用例与样例定位记录 |

## 11. 工作量评估

如果 OCR provider 已返回坐标：约 2-4 人天。  
如果当前 OCR 输出缺少坐标，需要调整 OCR 调用或更换输出模式：约 4-7 人天。

## 12. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| OCR provider 坐标格式不稳定 | bbox 无法统一映射 | 先归一化为外接矩形，只支持 block/line 级 |
| 历史图片无 bbox | 老数据无法图片定位 | 二期不默认回填历史；如需回填另开数据修复任务 |
| bbox 超出原图边界 | 前端画错框 | 前后端都做边界校验，异常时降级 |
| 原图尺寸缺失 | 无法比例映射 | 入库时写入 `imageWidth/imageHeight`，缺失时不画框 |
| 多 OCR 块命中同一图片 | 命中框选择不稳定 | Phase2 默认展示 `primaryHit` bbox，多个 bbox 展示作为后续增强 |
