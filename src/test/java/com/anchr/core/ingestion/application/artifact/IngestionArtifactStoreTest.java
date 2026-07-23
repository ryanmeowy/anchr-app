package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException.Reason;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionArtifactStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-23T08:00:00Z");

    private InMemoryObjectStoragePort storage;
    private IngestionArtifactStore store;

    @BeforeEach
    void setUp() {
        storage = new InMemoryObjectStoragePort();
        store = new IngestionArtifactStore(
                storage,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1024 * 1024,
                4 * 1024 * 1024);
    }

    @Test
    void parseResult_shouldRoundTripWithVersionedIdentityEnvelope() {
        IngestionTaskItem item = item();
        ParseResponse response = parseResponse("request-1", "hello");

        String key = store.writeParseResult(item, "job-1", response);
        ParseResponse restored = store.readParseResult(
                item.toBuilder().parseResultObjectKey(key).build());

        assertThat(key).isEqualTo(
                "ingestion/task-1/item-1/parse/2/jobs/job-1/parse-result.v1.json.gz");
        assertThat(restored).isEqualTo(response);
        assertThat(storage.contentType(key)).isEqualTo("application/json");
        assertThat(storage.contentEncoding(key)).isEqualTo("gzip");
    }

    @Test
    void parseResult_shouldAcceptIdenticalCreateOnlyReplayButRejectDifferentContent() {
        IngestionTaskItem item = item();
        ParseResponse response = parseResponse("request-1", "hello");

        String firstKey = store.writeParseResult(item, "job-1", response);
        String replayKey = store.writeParseResult(item, "job-1", response);

        assertThat(replayKey).isEqualTo(firstKey);
        assertThatThrownBy(() -> store.writeParseResult(
                item, "job-1", parseResponse("request-1", "changed")))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.IMMUTABLE_CONFLICT));
    }

    @Test
    void parseResult_shouldRejectArtifactBelongingToAnotherAsset() {
        IngestionTaskItem item = item();
        String key = store.writeParseResult(
                item, "job-1", parseResponse("request-1", "hello"));
        IngestionTaskItem otherAsset = item.toBuilder()
                .assetId("asset-2")
                .parseResultObjectKey(key)
                .build();

        assertThatThrownBy(() -> store.readParseResult(otherAsset))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.IDENTITY_MISMATCH));
    }

    @Test
    void embeddingResult_shouldRoundTripFullChunksAndAllowLaterClaimAttemptToRead() {
        IngestionTaskItem item = item();
        String parseKey = store.writeParseResult(
                item, "job-1", parseResponse("request-1", "hello"));
        IngestionTaskItem embedClaim = item.toBuilder()
                .parseResultObjectKey(parseKey)
                .executionEpoch(3)
                .stageAttempt(7)
                .build();
        List<Chunk> chunks = List.of(chunk());

        String embeddingKey = store.writeEmbeddingResult(embedClaim, chunks);
        IngestionTaskItem indexClaim = embedClaim.toBuilder()
                .stageAttempt(8)
                .embeddingResultObjectKey(embeddingKey)
                .build();

        assertThat(embeddingKey).isEqualTo(
                "ingestion/task-1/item-1/execution/3/embed/7/embedding-result.v1.json.gz");
        assertThat(store.readEmbeddingResult(indexClaim)).isEqualTo(chunks);
    }

    @Test
    void embeddingResult_shouldRejectDuplicateSegmentIds() {
        IngestionTaskItem embedClaim = item().toBuilder()
                .parseResultObjectKey("ingestion/parse.json.gz")
                .stageAttempt(1)
                .build();

        assertThatThrownBy(() -> store.writeEmbeddingResult(
                embedClaim, List.of(chunk(), chunk())))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void read_shouldRejectCorruptGzip() {
        IngestionTaskItem item = item();
        String key = store.writeParseResult(
                item, "job-1", parseResponse("request-1", "hello"));
        storage.replace(key, new byte[]{0x01, 0x02, 0x03});

        assertThatThrownBy(() -> store.readParseResult(
                item.toBuilder().parseResultObjectKey(key).build()))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void write_shouldEnforceUncompressedSizeLimitBeforeStorage() {
        IngestionArtifactStore tinyStore = new IngestionArtifactStore(
                storage,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1024,
                100);
        String largeText = "x".repeat(500);

        assertThatThrownBy(() -> tinyStore.writeParseResult(
                item(), "job-1", parseResponse("request-1", largeText)))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.TOO_LARGE));
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void write_shouldEnforceCompressedSizeLimitBeforeStorage() {
        IngestionArtifactStore tinyStore = new IngestionArtifactStore(
                storage,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                32,
                1024 * 1024);

        assertThatThrownBy(() -> tinyStore.writeParseResult(
                item(), "job-1", parseResponse("request-1", "hello")))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.TOO_LARGE));
        assertThat(storage.objects).isEmpty();
    }

    private IngestionTaskItem item() {
        return IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .parseAttempt(2)
                .doclingRequestId("request-1")
                .doclingJobId("job-1")
                .sourceRevision("v1:source")
                .executionEpoch(1)
                .stageAttempt(1)
                .build();
    }

    private ParseResponse parseResponse(String requestId, String text) {
        return new ParseResponse(
                requestId,
                "docling",
                "markdown",
                text,
                "pdf",
                List.of(new ParseResponse.Page(1, text)),
                List.of(new ParseResponse.Chunk(
                        "chunk/1",
                        "text",
                        text,
                        text,
                        List.of(1),
                        text.length(),
                        "body",
                        List.of(new ParseResponse.BboxInfo(
                                1, new ParseResponse.Bbox(0.1, 0.2, 0.3, 0.4, "TOPLEFT"))),
                        List.of("Heading"))),
                List.of(),
                List.of());
    }

    private Chunk chunk() {
        return Chunk.builder()
                .segmentId("segment-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .title("Heading")
                .pageNo(1)
                .chunkText("hello")
                .chunkOrder(1)
                .sourceRef("documents/source.pdf")
                .embedding(List.of(0.1F, 0.2F, 0.3F))
                .bboxInfos(List.of(BboxInfo.builder()
                        .pageNo(1)
                        .bbox(BboxInfo.Bbox.builder()
                                .l(0.1)
                                .t(0.2)
                                .r(0.3)
                                .b(0.4)
                                .coordOrigin("TOPLEFT")
                                .build())
                        .build()))
                .build();
    }

    private static final class InMemoryObjectStoragePort implements IngestionObjectStoragePort {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
        private final Map<String, String> contentEncodings = new ConcurrentHashMap<>();

        @Override
        public String buildDownloadUrl(String objectKey) {
            return "https://example.test/" + objectKey;
        }

        @Override
        public boolean putArtifactIfAbsent(String objectKey, byte[] content,
                                           String contentType, String contentEncoding) {
            byte[] existing = objects.putIfAbsent(objectKey, Arrays.copyOf(content, content.length));
            if (existing == null) {
                contentTypes.put(objectKey, contentType);
                contentEncodings.put(objectKey, contentEncoding);
            }
            return existing == null;
        }

        @Override
        public byte[] readArtifact(String objectKey, int maxBytes) {
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new IllegalStateException("missing object");
            }
            if (content.length > maxBytes) {
                throw new IllegalStateException("too large");
            }
            return Arrays.copyOf(content, content.length);
        }

        String contentType(String objectKey) {
            return contentTypes.get(objectKey);
        }

        String contentEncoding(String objectKey) {
            return contentEncodings.get(objectKey);
        }

        void replace(String objectKey, byte[] content) {
            objects.put(objectKey, Arrays.copyOf(content, content.length));
        }
    }
}
