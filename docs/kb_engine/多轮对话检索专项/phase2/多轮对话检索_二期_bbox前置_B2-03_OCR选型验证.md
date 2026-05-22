# B2-03 传统 OCR API 接入验证

更新时间：2026-05-06

## 选型结论

传统 OCR provider 选择阿里云 OCR `RecognizeAdvanced`。

理由：

1. 当前项目已引入 `com.aliyun:ocr_api20210707` SDK，且已有 `aliyun.ocr.*` 配置和 `Client` Bean。
2. `RecognizeAdvancedRequest` 支持 `Paragraph=true`，可以获取 paragraph 级结构。
3. `RecognizeAdvancedResponseBody.Data` 返回 JSON 字符串，包含原图尺寸和 `prism_wordsInfo` 坐标，可归一化为统一 bbox 协议。
4. 该方案不引入新依赖，和现有阿里云能力配置保持一致。

## 代码交付

结构化模型：

- `OcrStructuredResult`
- `OcrParagraph`
- `OcrWord`
- `OcrBoundingBox`

接入代码：

- `IngestionStructuredOcrPort`
- `AliyunTraditionalOcrManager`
- `AliyunAdvancedOcrResultParser`
- `AliyunOcrService.extractStructuredText`

## 坐标归一化

解析 `prism_wordsInfo[].pos` 四点坐标，转换为外接矩形：

- `x = min(pos[].x)`
- `y = min(pos[].y)`
- `width = max(pos[].x) - x`
- `height = max(pos[].y) - y`
- `unit = PIXEL`

paragraph bbox 取段落内 word bbox 的外接矩形。

## 降级边界

1. `prism_paragraphsInfo` 缺失时，按 word 作为最小 paragraph 返回。
2. word 坐标缺失或异常时，该 word/paragraph 的 bbox 置空，由后续 B2-04/B2-06 进入安全降级。
3. 当前任务只完成传统 OCR 结构化接入，不包含 LLM 增强、入库拆段和指标埋点。
