# 观测指标速查表（Actuator + MeterRegistry）

## 1. 适用范围

本文用于 `anchr` 项目本地/测试环境快速查看 `MeterRegistry` 指标。

## 2. 开启方式

在当前运行 profile 配置中暴露 `metrics` 端点（示例：`src/main/resources/application-local.yaml`）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

## 3. HTTP 查看地址

默认服务地址 `http://localhost:8080`：

1. 指标列表：`GET /actuator/metrics`
2. 单指标详情：`GET /actuator/metrics/{metric.name}`

示例：

```bash
curl http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/metrics/smartvision.strategy.selection
curl http://localhost:8080/actuator/metrics/smartvision.search.rerank.calls
curl http://localhost:8080/actuator/metrics/smartvision.search.rerank.latency
```

## 4. 搜索链路重点指标

## 4.1 策略选择

1. `smartvision.strategy.selection`
2. `smartvision.strategy.fallback`

说明：用于观察请求策略、有效策略、是否发生降级。

## 4.2 Rerank 质量与成本

1. `smartvision.search.rerank.calls`
2. `smartvision.search.rerank.latency`
3. `smartvision.search.rerank.window.size`
4. `smartvision.search.rerank.window.ratio`
5. `smartvision.search.rerank.window.hit`
6. `smartvision.search.rerank.fallback`

说明：用于观察 rerank 调用频次、耗时、窗口大小与降级情况。

## 5. 常见问题

1. 访问 `/actuator/metrics` 返回 404  
原因：`management.endpoints.web.exposure.include` 未包含 `metrics`。

2. 只能看到 `health` 看不到 `metrics`  
原因：端点未暴露或运行环境覆盖了配置。

3. 想接入 Prometheus  
当前仓库默认未启用。需额外引入 `micrometer-registry-prometheus` 并暴露 `/actuator/prometheus`。
