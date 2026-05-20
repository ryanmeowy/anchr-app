package com.anchr.core.search.escli.application;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.cat.indices.IndicesRecord;
import co.elastic.clients.elasticsearch.cluster.ClusterStatsResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import co.elastic.clients.elasticsearch.indices.stats.IndexStats;
import co.elastic.clients.json.JsonpSerializable;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.escli.domain.EsCliAccessControl;
import com.anchr.core.search.escli.domain.EsCliIndexValidator;
import com.anchr.core.search.escli.interfaces.rest.dto.EsClusterHealthDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsClusterStatsDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsDocGetDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsDocHitDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsDocSearchDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsDocSearchRequestDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsIndexListDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsIndexMappingDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsIndexStatsDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsIndexSummaryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.json.spi.JsonProvider;

/**
 * ES read-only query service used by the internal ES CLI APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsCliQueryService {
    private static final JacksonJsonpMapper JSONP_MAPPER = new JacksonJsonpMapper();

    private static final Set<String> ALLOWED_QUERY_FIELDS = Set.of(
            "id", "imagePath", "ocrContent", "createTime", "rawFilename", "fileName", "tags", "fileHash"
    );
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("_score", "id", "createTime", "fileName");
    private static final Pattern SAFE_QUERY_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_:\\-\\s\"'().*+]+$");
    private static final List<String> DEFAULT_SOURCE_EXCLUDES = List.of("imageEmbedding");

    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;
    private final EsCliAccessControl accessControl;
    private final MeterRegistry meterRegistry;

    public EsClusterHealthDTO getClusterHealth() {
        return monitor("cluster.health", () -> {
            var response = esClient.cluster().health();
            EsClusterHealthDTO result = EsClusterHealthDTO.builder()
                    .status(response.status() == null ? "unknown" : response.status().jsonValue())
                    .clusterName(response.clusterName())
                    .numberOfNodes(response.numberOfNodes())
                    .activePrimaryShards(response.activePrimaryShards())
                    .activeShards(response.activeShards())
                    .unassignedShards(response.unassignedShards())
                    .timedOut(response.timedOut())
                    .timestamp(Instant.now().toString())
                    .build();
            auditSuccess("cluster.health", "cluster=" + result.clusterName() + ",status=" + result.status());
            return result;
        });
    }

    public EsClusterStatsDTO getClusterStats() {
        return monitor("cluster.stats", () -> {
            ClusterStatsResponse response = esClient.cluster().stats();
            EsClusterStatsDTO result = EsClusterStatsDTO.builder()
                    .clusterName(response.clusterName())
                    .status(response.status() == null ? "unknown" : response.status().jsonValue())
                    .nodeTotal(response.nodes() == null || response.nodes().count() == null ? null : response.nodes().count().total())
                    .dataNodeCount(response.nodes() == null || response.nodes().count() == null ? null : response.nodes().count().data())
                    .indexCount(response.indices() == null ? null : response.indices().count())
                    .docCount(response.indices() == null || response.indices().docs() == null ? null : response.indices().docs().count())
                    .storeSizeBytes(response.indices() == null || response.indices().store() == null ? null : response.indices().store().sizeInBytes())
                    .shardsTotal(response.indices() == null || response.indices().shards() == null || response.indices().shards().total() == null
                            ? null : response.indices().shards().total().intValue())
                    .build();
            auditSuccess("cluster.stats", "cluster=" + result.clusterName() + ",indices=" + result.indexCount());
            return result;
        });
    }

    public EsIndexListDTO listIndices(String pattern, String status, int page, int size) {
        EsCliIndexValidator.validatePattern(pattern);
        EsCliIndexValidator.validatePageAndSize(page, size);

        return monitor("indices.list", () -> {
            var request = esClient.cat().indices(i -> {
                if (StringUtils.hasText(pattern)) {
                    i.index(pattern);
                }
                return i;
            });

            List<EsIndexSummaryDTO> allItems = request.valueBody().stream()
                    .filter(record -> accessControl.isAllowed(record.index()))
                    .filter(record -> filterByStatus(record, status))
                    .map(this::toIndexSummary)
                    .sorted(Comparator.comparing(EsIndexSummaryDTO::name))
                    .toList();

            int from = Math.min((page - 1) * size, allItems.size());
            int to = Math.min(from + size, allItems.size());

            EsIndexListDTO result = EsIndexListDTO.builder()
                    .items(allItems.subList(from, to))
                    .page(page)
                    .size(size)
                    .total(allItems.size())
                    .build();
            auditSuccess("indices.list", "pattern=" + safe(pattern) + ",status=" + safe(status) + ",total=" + result.total());
            return result;
        });
    }

    public EsIndexStatsDTO getIndexStats(String index) {
        EsCliIndexValidator.validateIndex(index);
        accessControl.assertIndexAllowed(index);

        return monitor("indices.stats", () -> {
            var response = esClient.indices().stats(s -> s.index(index));
            Map<String, IndicesStats> indexStatsMap = response.indices();
            if (indexStatsMap == null || indexStatsMap.isEmpty()) {
                throw new BusinessException(ApiError.NOT_FOUND, "Index not found");
            }

            Map.Entry<String, IndicesStats> entry = indexStatsMap.entrySet().iterator().next();
            IndexStats total = entry.getValue().total();
            IndexStats primaries = entry.getValue().primaries();
            EsIndexStatsDTO result = EsIndexStatsDTO.builder()
                    .index(entry.getKey())
                    .docsCount(total == null || total.docs() == null ? null : total.docs().count())
                    .docsDeleted(total == null || total.docs() == null ? null : total.docs().deleted())
                    .storeSizeBytes(total == null || total.store() == null ? null : total.store().sizeInBytes())
                    .priStoreSizeBytes(primaries == null || primaries.store() == null ? null : primaries.store().sizeInBytes())
                    .queryTotal(total == null || total.search() == null ? null : total.search().queryTotal())
                    .queryTimeInMillis(total == null || total.search() == null ? null : total.search().queryTimeInMillis())
                    .build();
            auditSuccess("indices.stats", "index=" + safe(result.index()) + ",docs=" + result.docsCount());
            return result;
        });
    }

    public EsIndexMappingDTO getIndexMapping(String index) {
        EsCliIndexValidator.validateIndex(index);
        accessControl.assertIndexAllowed(index);

        return monitor("indices.mapping", () -> {
            GetMappingResponse response = esClient.indices().getMapping(g -> g.index(index));
            if (response.result() == null || response.result().isEmpty()) {
                throw new BusinessException(ApiError.NOT_FOUND, "Index not found");
            }
            Map.Entry<String, IndexMappingRecord> entry = response.result().entrySet().iterator().next();
            Map<String, Object> mapping = resolveMapping(entry.getValue());

            EsIndexMappingDTO result = EsIndexMappingDTO.builder()
                    .index(entry.getKey())
                    .mapping(mapping)
                    .build();
            // Note: mapping payload may be large, log only top-level size.
            auditSuccess("indices.mapping", "index=" + safe(result.index()) + ",keys=" + (result.mapping() == null ? 0 : result.mapping().size()));
            return result;
        });
    }

    private Map<String, Object> resolveMapping(IndexMappingRecord record) {
        if (record == null) {
            return Map.of();
        }
        Map<String, Object> mapping = parseJsonpAsMap(record.mappings());
        if (!mapping.isEmpty()) {
            return mapping;
        }
        return parseJsonpAsMap(record);
    }

    private Map<String, Object> parseJsonpAsMap(JsonpSerializable payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            StringWriter writer = new StringWriter();
            var generator = JsonProvider.provider().createGenerator(writer);
            payload.serialize(generator, JSONP_MAPPER);
            generator.close();
            return objectMapper.readValue(writer.toString(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to parse index mapping");
        }
    }

    public EsDocGetDTO getDocument(String index, String id, boolean sourceEnabled) {
        EsCliIndexValidator.validateIndex(index);
        accessControl.assertIndexAllowed(index);
        if (!StringUtils.hasText(id)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "id cannot be empty");
        }

        return monitor("docs.get", () -> {
            var response = esClient.get(g -> {
                g.index(index).id(id).source(s -> s.fetch(sourceEnabled));
                if (sourceEnabled) {
                    g.sourceExcludes(DEFAULT_SOURCE_EXCLUDES);
                }
                return g;
            }, Map.class);
            EsDocGetDTO result = EsDocGetDTO.builder()
                    .found(response.found())
                    .index(response.index())
                    .id(response.id())
                    .version(response.version())
                    .source(response.source())
                    .build();
            auditSuccess("docs.get", "index=" + safe(index) + ",id=" + safe(id) + ",found=" + result.found());
            return result;
        });
    }

    public EsDocSearchDTO searchDocuments(String index, EsDocSearchRequestDTO request) {
        EsCliIndexValidator.validateIndex(index);
        accessControl.assertIndexAllowed(index);
        validateDocSearchRequest(request);

        return monitor("docs.search", () -> {
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(index)
                    .from(request.getFrom())
                    .size(request.getSize())
                    .query(q -> q.queryString(qs -> qs
                            .query(request.getQuery().trim())
                            .defaultOperator(Operator.And)
                            .fields(ALLOWED_QUERY_FIELDS.stream().toList())));

            if (request.getSort() != null && !request.getSort().isEmpty()) {
                builder.sort(request.getSort().stream()
                        .map(this::parseSortExpression)
                        .toList());
            }
            if (request.getSourceIncludes() != null && !request.getSourceIncludes().isEmpty()) {
                builder.source(src -> src.filter(f -> f.includes(request.getSourceIncludes())));
            } else {
                builder.source(src -> src.filter(f -> f.excludes(DEFAULT_SOURCE_EXCLUDES)));
            }

            var response = esClient.search(builder.build(), Map.class);
            List<EsDocHitDTO> hits = response.hits().hits().stream()
                    .map(this::toDocHit)
                    .toList();

            Long total = response.hits().total() == null ? null : response.hits().total().value();
            EsDocSearchDTO result = EsDocSearchDTO.builder()
                    .took(response.took())
                    .timedOut(response.timedOut())
                    .total(total)
                    .hits(hits)
                    .build();
            auditSuccess("docs.search", "index=" + safe(index) + ",from=" + request.getFrom() + ",size=" + request.getSize() + ",total=" + result.total());
            return result;
        });
    }

    private EsIndexSummaryDTO toIndexSummary(IndicesRecord record) {
        return EsIndexSummaryDTO.builder()
                .name(record.index())
                .health(record.health() == null ? "unknown" : record.health())
                .docsCount(parseLong(record.docsCount()))
                .storeSize(record.storeSize())
                .pri(record.pri())
                .rep(record.rep())
                .build();
    }

    private boolean filterByStatus(IndicesRecord record, String status) {
        if (!StringUtils.hasText(status)) {
            return true;
        }
        String normalized = status.trim().toLowerCase();
        String actual = record.health() == null ? "unknown" : record.health();
        return normalized.equals(actual);
    }

    private co.elastic.clients.elasticsearch._types.SortOptions parseSortExpression(String expression) {
        if (!StringUtils.hasText(expression)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "sort expression cannot be empty");
        }
        String[] parts = expression.split(":");
        if (parts.length != 2) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "sort format must be field:asc|desc");
        }

        String field = parts[0].trim();
        String orderRaw = parts[1].trim().toLowerCase();
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "unsupported sort field: " + field);
        }
        SortOrder order;
        if ("asc".equals(orderRaw)) {
            order = SortOrder.Asc;
        } else if ("desc".equals(orderRaw)) {
            order = SortOrder.Desc;
        } else {
            throw new BusinessException(ApiError.INVALID_REQUEST, "sort order must be asc or desc");
        }

        if ("_score".equals(field)) {
            return new co.elastic.clients.elasticsearch._types.SortOptions.Builder()
                    .score(s -> s.order(order))
                    .build();
        }
        return new co.elastic.clients.elasticsearch._types.SortOptions.Builder()
                .field(f -> f.field(field).order(order))
                .build();
    }

    private EsDocHitDTO toDocHit(Hit<Map> hit) {
        return EsDocHitDTO.builder()
                .id(hit.id())
                .score(hit.score())
                .source(hit.source())
                .build();
    }

    private void validateDocSearchRequest(EsDocSearchRequestDTO request) {
        if (request == null) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "request body cannot be empty");
        }
        if (!StringUtils.hasText(request.getQuery())) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query cannot be empty");
        }
        String query = request.getQuery().trim();
        if (query.length() > 500) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query length must be <= 500");
        }
        if (!SAFE_QUERY_PATTERN.matcher(query).matches()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "query contains unsupported characters");
        }
        if (request.getFrom() == null || request.getFrom() < 0) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "from must be >= 0");
        }
        if (request.getSize() == null || request.getSize() < 1 || request.getSize() > 100) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "size must be between 1 and 100");
        }
        if (request.getSourceIncludes() != null) {
            boolean illegal = request.getSourceIncludes().stream().anyMatch(field -> !ALLOWED_QUERY_FIELDS.contains(field));
            if (illegal) {
                throw new BusinessException(ApiError.INVALID_REQUEST, "sourceIncludes contains unsupported fields");
            }
        }
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BusinessException mapException(String scene, Exception e) {
        if (e instanceof IOException) {
            log.error("ES CLI {} failed due to IO error", scene, e);
            return new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE, "Search backend unavailable");
        }
        log.error("ES CLI {} failed", scene, e);
        return new BusinessException(ApiError.INTERNAL_ERROR, "ES query failed");
    }

    private <T> T monitor(String endpoint, CheckedSupplier<T> supplier) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T value = supplier.get();
            meterRegistry.counter("escli.requests", "endpoint", endpoint, "outcome", "success").increment();
            return value;
        } catch (BusinessException e) {
            meterRegistry.counter("escli.requests", "endpoint", endpoint, "outcome", "business_error").increment();
            auditFailure(endpoint, e.getMessage());
            throw e;
        } catch (Exception e) {
            meterRegistry.counter("escli.requests", "endpoint", endpoint, "outcome", "error").increment();
            BusinessException mapped = mapException(endpoint, e);
            auditFailure(endpoint, mapped.getMessage());
            throw mapped;
        } finally {
            sample.stop(Timer.builder("escli.latency")
                    .description("Latency for ES CLI backend APIs")
                    .tag("endpoint", endpoint)
                    .register(meterRegistry));
        }
    }

    private void auditSuccess(String command, String details) {
        log.info("escli_audit outcome=success command={} caller={} details={}",
                command, callerFingerprint(), details);
    }

    private void auditFailure(String command, String reason) {
        log.warn("escli_audit outcome=failure command={} caller={} reason={}",
                command, callerFingerprint(), safe(reason));
    }

    private String callerFingerprint() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return "unknown";
        }
        HttpServletRequest request = servletAttributes.getRequest();
        if (request == null) {
            return "unknown";
        }
        String token = request.getHeader("X-Access-Token");
        if (!StringUtils.hasText(token)) {
            return "anonymous";
        }
        return "tk_" + sha256(token).substring(0, 8);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "hash_error";
        }
    }

    private String safe(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
