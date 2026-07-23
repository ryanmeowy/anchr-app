package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactException.Reason;
import com.anchr.core.ingestion.config.IngestionArtifactProperties;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
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
                artifactProperties(1024 * 1024, 4 * 1024 * 1024));
    }

    @Test
    void parseResult_shouldRoundTripWithVersionedIdentityEnvelope() {
        IngestionTaskItem item = item();
        ParseResponse response = parseResponse("request-1", "hello");

        IngestionStoredArtifact stored = store.writeParseArtifact(item, "job-1", response);
        String key = stored.objectKey();
        ParseResponse restored = store.readParseResult(
                registeredParse(item, stored));

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

        String firstKey = store.writeParseArtifact(item, "job-1", response).objectKey();
        String replayKey = store.writeParseArtifact(item, "job-1", response).objectKey();

        assertThat(replayKey).isEqualTo(firstKey);
        assertThatThrownBy(() -> store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "changed")))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.IMMUTABLE_CONFLICT));
    }

    @Test
    void parseArtifact_shouldReturnShaOfStoredGzipAndKeepItStableOnReplay() {
        IngestionTaskItem item = item();
        ParseResponse response = parseResponse("request-1", "hello");

        IngestionStoredArtifact first =
                store.writeParseArtifact(item, "job-1", response);
        IngestionArtifactStore laterStore = new IngestionArtifactStore(
                storage,
                new ObjectMapper().findAndRegisterModules(),
                artifactProperties(1024 * 1024, 4 * 1024 * 1024));
        IngestionStoredArtifact replay =
                laterStore.writeParseArtifact(item, "job-1", response);

        assertThat(first.version()).isEqualTo(IngestionArtifactStore.ARTIFACT_VERSION);
        assertThat(first.sha256()).matches("[0-9a-f]{64}");
        assertThat(first.sha256()).isEqualTo(sha256(storage.content(first.objectKey())));
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void parseResult_shouldRejectArtifactBelongingToAnotherAsset() {
        IngestionTaskItem item = item();
        IngestionStoredArtifact stored = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello"));
        String key = stored.objectKey();
        IngestionTaskItem otherAsset = item.toBuilder()
                .assetId("asset-2")
                .parseResultObjectKey(key)
                .parseResultArtifact(reference(
                        "PARSE_RESULT", stored, "PRODUCED", item.getClaimVersion()))
                .build();

        assertThatThrownBy(() -> store.readParseResult(otherAsset))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.IDENTITY_MISMATCH));
    }

    @Test
    void parseResult_shouldRejectValidReplacementThatDoesNotMatchRegistryDigest() {
        IngestionTaskItem item = item();
        IngestionStoredArtifact stored =
                store.writeParseArtifact(
                        item, "job-1", parseResponse("request-1", "hello"));

        InMemoryObjectStoragePort replacementStorage = new InMemoryObjectStoragePort();
        IngestionArtifactStore replacementStore = new IngestionArtifactStore(
                replacementStorage,
                new ObjectMapper().findAndRegisterModules(),
                artifactProperties(1024 * 1024, 4 * 1024 * 1024));
        replacementStore.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "tampered"));
        storage.replace(
                stored.objectKey(), replacementStorage.content(stored.objectKey()));

        IngestionTaskItem registered = item.toBuilder()
                .parseResultObjectKey(stored.objectKey())
                .parseResultArtifact(reference(
                        "PARSE_RESULT", stored, "PRODUCED", 3L))
                .build();

        assertThatThrownBy(() -> store.readParseResult(registered))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void parseResult_shouldAllowLegacyRegistryReferenceWithoutDigest() {
        IngestionTaskItem item = item();
        String key = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello")).objectKey();
        IngestionTaskItem legacy = item.toBuilder()
                .parseResultObjectKey(key)
                .parseResultArtifact(IngestionArtifactReference.builder()
                        .artifactType("PARSE_RESULT")
                        .artifactVersion(1)
                        .provenance("LEGACY_BACKFILL")
                        .objectKey(key)
                        .build())
                .build();

        assertThat(store.readParseResult(legacy))
                .isEqualTo(parseResponse("request-1", "hello"));
    }

    @Test
    void embeddingResult_shouldRoundTripFullChunksAndAllowLaterClaimAttemptToRead() {
        IngestionTaskItem item = item();
        String parseKey = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello")).objectKey();
        IngestionTaskItem embedClaim = item.toBuilder()
                .parseResultObjectKey(parseKey)
                .executionEpoch(3)
                .claimVersion(7)
                .build();
        List<Chunk> chunks = List.of(chunk());

        IngestionStoredArtifact stored = store.writeEmbeddingArtifact(embedClaim, chunks);
        String embeddingKey = stored.objectKey();
        IngestionTaskItem indexClaim = embedClaim.toBuilder()
                .claimVersion(8)
                .embeddingResultObjectKey(embeddingKey)
                .embeddingResultArtifact(reference(
                        "EMBEDDING_RESULT", stored, "PRODUCED", 7L))
                .build();

        assertThat(embeddingKey).isEqualTo(
                "ingestion/task-1/item-1/execution/3/embed/7/embedding-result.v1.json.gz");
        assertThat(stored.version()).isEqualTo(IngestionArtifactStore.ARTIFACT_VERSION);
        assertThat(stored.sha256()).matches("[0-9a-f]{64}");
        assertThat(stored.sha256()).isEqualTo(sha256(storage.content(embeddingKey)));
        assertThat(store.readEmbeddingResult(indexClaim)).isEqualTo(chunks);
    }

    @Test
    void embeddingArtifactV1_shouldKeepStageAttemptJsonWireName() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        IngestionEmbeddingArtifact artifact = new IngestionEmbeddingArtifact(
                "EMBEDDING_RESULT",
                1,
                "task-1",
                "item-1",
                "kb-1",
                "asset-1",
                2,
                3,
                7,
                "request-1",
                "v1:source",
                "parse/result.json.gz",
                NOW,
                List.of());

        String json = mapper.writeValueAsString(artifact);
        IngestionEmbeddingArtifact restored =
                mapper.readValue(json, IngestionEmbeddingArtifact.class);

        assertThat(json).contains("\"stageAttempt\":7");
        assertThat(json).doesNotContain("\"claimVersion\"");
        assertThat(restored.claimVersion()).isEqualTo(7);
    }

    @Test
    void embeddingResult_shouldAllowLegacyRegistryReferenceWithoutProducerMetadata() {
        IngestionTaskItem item = item();
        String parseKey = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello")).objectKey();
        IngestionTaskItem embedClaim = item.toBuilder()
                .parseResultObjectKey(parseKey)
                .executionEpoch(3)
                .claimVersion(7)
                .build();
        IngestionStoredArtifact stored =
                store.writeEmbeddingArtifact(embedClaim, List.of(chunk()));
        IngestionTaskItem legacy = embedClaim.toBuilder()
                .claimVersion(8)
                .embeddingResultObjectKey(stored.objectKey())
                .embeddingResultArtifact(IngestionArtifactReference.builder()
                        .artifactType("EMBEDDING_RESULT")
                        .artifactVersion(1)
                        .provenance("LEGACY_BACKFILL")
                        .objectKey(stored.objectKey())
                        .build())
                .build();

        assertThat(store.readEmbeddingResult(legacy)).containsExactly(chunk());
    }

    @Test
    void embeddingResult_shouldRejectRegistryProducerThatDiffersFromEnvelope() {
        IngestionTaskItem item = item();
        String parseKey = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello")).objectKey();
        IngestionTaskItem embedClaim = item.toBuilder()
                .parseResultObjectKey(parseKey)
                .executionEpoch(3)
                .claimVersion(7)
                .build();
        IngestionStoredArtifact stored =
                store.writeEmbeddingArtifact(embedClaim, List.of(chunk()));
        IngestionTaskItem inconsistent = embedClaim.toBuilder()
                .claimVersion(8)
                .embeddingResultObjectKey(stored.objectKey())
                .embeddingResultArtifact(reference(
                        "EMBEDDING_RESULT", stored, "PRODUCED", 6L))
                .build();

        assertThatThrownBy(() -> store.readEmbeddingResult(inconsistent))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void embeddingResult_shouldRejectDuplicateSegmentIds() {
        IngestionTaskItem embedClaim = item().toBuilder()
                .parseResultObjectKey("ingestion/parse.json.gz")
                .claimVersion(1)
                .build();

        assertThatThrownBy(() -> store.writeEmbeddingArtifact(
                embedClaim, List.of(chunk(), chunk())))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void embeddingResult_shouldRejectValidReplacementThatDoesNotMatchRegistryDigest() {
        IngestionTaskItem item = item();
        String parseKey = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello")).objectKey();
        IngestionTaskItem embedClaim = item.toBuilder()
                .parseResultObjectKey(parseKey)
                .executionEpoch(3)
                .claimVersion(7)
                .build();
        IngestionStoredArtifact stored =
                store.writeEmbeddingArtifact(embedClaim, List.of(chunk()));

        InMemoryObjectStoragePort replacementStorage = new InMemoryObjectStoragePort();
        IngestionArtifactStore replacementStore = new IngestionArtifactStore(
                replacementStorage,
                new ObjectMapper().findAndRegisterModules(),
                artifactProperties(1024 * 1024, 4 * 1024 * 1024));
        Chunk changed = chunk();
        changed.setChunkText("tampered");
        replacementStore.writeEmbeddingArtifact(embedClaim, List.of(changed));
        storage.replace(
                stored.objectKey(), replacementStorage.content(stored.objectKey()));

        IngestionTaskItem registered = embedClaim.toBuilder()
                .claimVersion(8)
                .embeddingResultObjectKey(stored.objectKey())
                .embeddingResultArtifact(reference(
                        "EMBEDDING_RESULT", stored, "PRODUCED", 7L))
                .build();

        assertThatThrownBy(() -> store.readEmbeddingResult(registered))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void read_shouldRejectCorruptGzip() {
        IngestionTaskItem item = item();
        IngestionStoredArtifact stored = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello"));
        String key = stored.objectKey();
        storage.replace(key, new byte[]{0x01, 0x02, 0x03});

        assertThatThrownBy(() -> store.readParseResult(
                registeredParse(item, stored)))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void read_shouldRejectBareObjectKeyWithoutRegistryReference() {
        IngestionTaskItem item = item();
        IngestionStoredArtifact stored = store.writeParseArtifact(
                item, "job-1", parseResponse("request-1", "hello"));

        assertThatThrownBy(() -> store.readParseResult(
                item.toBuilder().parseResultObjectKey(stored.objectKey()).build()))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.CORRUPT));
    }

    @Test
    void write_shouldEnforceUncompressedSizeLimitBeforeStorage() {
        IngestionArtifactStore tinyStore = new IngestionArtifactStore(
                storage,
                new ObjectMapper().findAndRegisterModules(),
                artifactProperties(1024, 100));
        String largeText = "x".repeat(500);

        assertThatThrownBy(() -> tinyStore.writeParseArtifact(
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
                artifactProperties(32, 1024 * 1024));

        assertThatThrownBy(() -> tinyStore.writeParseArtifact(
                item(), "job-1", parseResponse("request-1", "hello")))
                .isInstanceOfSatisfying(IngestionArtifactException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(Reason.TOO_LARGE));
        assertThat(storage.objects).isEmpty();
    }

    private IngestionArtifactProperties artifactProperties(
            int maxCompressedBytes, int maxUncompressedBytes) {
        IngestionArtifactProperties properties = new IngestionArtifactProperties();
        properties.setMaxCompressedBytes(maxCompressedBytes);
        properties.setMaxUncompressedBytes(maxUncompressedBytes);
        return properties;
    }

    private IngestionTaskItem registeredParse(
            IngestionTaskItem item, IngestionStoredArtifact stored) {
        return item.toBuilder()
                .parseResultObjectKey(stored.objectKey())
                .parseResultArtifact(reference(
                        "PARSE_RESULT", stored, "PRODUCED", item.getClaimVersion()))
                .build();
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
                .claimVersion(1)
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

    private IngestionArtifactReference reference(
            String type,
            IngestionStoredArtifact stored,
            String provenance,
            Long producerClaimVersion) {
        return IngestionArtifactReference.builder()
                .artifactType(type)
                .artifactVersion(stored.version())
                .provenance(provenance)
                .producerClaimVersion(producerClaimVersion)
                .objectKey(stored.objectKey())
                .contentSha256(stored.sha256())
                .build();
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available in the test runtime.", e);
        }
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

        byte[] content(String objectKey) {
            byte[] content = objects.get(objectKey);
            return Arrays.copyOf(content, content.length);
        }

        void replace(String objectKey, byte[] content) {
            objects.put(objectKey, Arrays.copyOf(content, content.length));
        }
    }
}
