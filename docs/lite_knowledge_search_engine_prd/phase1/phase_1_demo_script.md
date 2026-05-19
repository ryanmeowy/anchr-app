# Phase 1 演示脚本（E6 / P1-23）

> 演示目标：稳定展示「上传入库 -> 任务可观测 -> 统一检索 -> 文本+图片混排结果」完整路径。

## 0. 演示前准备（2 分钟）

1. 启动后端服务（确保可访问 `http://localhost:8080`）。
2. 打开 Streamlit 页面，Base URL 指向后端。
3. 准备一批文本文件（`PDF/TXT/MD`）和若干图片（包含 OCR 可识别文本/图示内容）。

## 1. 文本入库演示（3 分钟）

1. 调用文本任务接口提交 2~3 个文本文件：
   - `POST /api/v1/ingestion/text-assets/batch-tasks`
2. 展示任务状态查询：
   - `GET /api/v1/ingestion/text-assets/batch-tasks/{taskId}`
3. 如有失败项，演示失败重试：
   - 单项重试：`POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/items/{itemId}/retry`
   - 全量失败重试：`POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/retry-failed`

讲解要点（30 秒）：
- 文本任务复用现有任务体系，状态和重试语义与图片一致。

## 2. 图片入库演示（2 分钟）

1. 在图片批处理页提交 2~3 张图片。
2. 展示任务状态可见，确认图片入库成功。

讲解要点（20 秒）：
- 图片旧链路保持可用，同时已适配统一 segment 检索模型。

## 3. 统一检索演示（4 分钟）

1. 打开 Streamlit「KB Search」页（`POST /api/v1/search/kb`）。
2. 依次输入 3 个 query（建议）：
   - `mysql 架构实现`
   - `系统架构图`
   - `慢查询分析`
3. 每个 query 展示以下信息：
   - `resultType/assetType/snippet/pageNo/score/explain`
   - 同一页里同时看到文本结果与图片结果（若数据覆盖到位）。

讲解要点（40 秒）：
- 检索单位统一为 segment；
- 文本返回页码与片段，图片返回命中摘要；
- explain 可看到命中来源（如 `VECTOR/TITLE/CONTENT/OCR`）。

## 4. 收口总结（1 分钟）

按 Phase 1 验收口径总结：

1. 文本上传、解析、chunk、embedding、索引链路已通；
2. 图片与文本可在统一接口 `POST /api/v1/search/kb` 下联合召回；
3. UI 可展示文本+图片混排结果，满足最小可演示要求。

## 5. 演示失败兜底（可选）

- 若某条 query 无结果，切换到已验证通过的 query（见验收清单）。
- 若单条素材解析失败，直接展示任务重试能力与错误可观测性。
