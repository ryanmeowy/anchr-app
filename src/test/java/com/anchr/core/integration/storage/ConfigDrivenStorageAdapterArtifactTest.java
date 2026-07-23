package com.anchr.core.integration.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigDrivenStorageAdapterArtifactTest {

    @Mock
    private StorageConfigRepository configRepository;
    @Mock
    private AesUtil aesUtil;
    @Mock
    private OSS oss;

    private ConfigDrivenStorageAdapter adapter;
    private MockedConstruction<OSSClientBuilder> clientBuilders;

    @BeforeEach
    void setUp() {
        StorageConfig config = StorageConfig.builder()
                .endpoint("https://oss.example.test")
                .bucket("bucket")
                .accessKeyEnc("encrypted-access")
                .secretKeyEnc("encrypted-secret")
                .enabled(true)
                .build();
        when(configRepository.find()).thenReturn(Optional.of(config));
        when(aesUtil.decrypt("encrypted-access")).thenReturn("access");
        when(aesUtil.decrypt("encrypted-secret")).thenReturn("secret");
        clientBuilders = mockConstruction(
                OSSClientBuilder.class,
                (builder, context) -> when(builder.build(
                        "https://oss.example.test", "access", "secret"))
                        .thenReturn(oss));
        adapter = new ConfigDrivenStorageAdapter(configRepository, aesUtil);
    }

    @AfterEach
    void tearDown() {
        clientBuilders.close();
    }

    @Test
    void putArtifactIfAbsent_shouldUseAtomicForbidOverwriteHeaderAndDigests() throws Exception {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        when(oss.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        boolean created = adapter.putArtifactIfAbsent(
                "ingestion/task/item/result.json.gz", content, "application/json", "gzip");

        assertThat(created).isTrue();
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(oss).putObject(captor.capture());
        PutObjectRequest request = captor.getValue();
        assertThat(request.getBucketName()).isEqualTo("bucket");
        assertThat(request.getKey()).isEqualTo("ingestion/task/item/result.json.gz");
        assertThat(request.getInputStream().readAllBytes()).isEqualTo(content);
        assertThat(request.getMetadata().getRawMetadata())
                .containsEntry("x-oss-forbid-overwrite", "true");
        assertThat(request.getMetadata().getContentType()).isEqualTo("application/json");
        assertThat(request.getMetadata().getContentEncoding()).isEqualTo("gzip");
        assertThat(request.getMetadata().getContentMD5()).isNotBlank();
        assertThat(request.getMetadata().getUserMetadata())
                .containsEntry("artifact-sha256", sha256(content));
        verify(oss).shutdown();
    }

    @Test
    void putArtifactIfAbsent_shouldReturnFalseForProviderConflict() {
        OSSException conflict = new OSSException("already exists") {
            @Override
            public String getErrorCode() {
                return "FileAlreadyExists";
            }
        };
        when(oss.putObject(any(PutObjectRequest.class))).thenThrow(conflict);

        assertThat(adapter.putArtifactIfAbsent(
                "ingestion/task/item/result.json.gz",
                new byte[]{0x1},
                "application/json",
                "gzip")).isFalse();
        verify(oss).shutdown();
    }

    @Test
    void readArtifact_shouldVerifySizeAndStoredDigest() {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        OSSObject object = object(content, sha256(content));
        when(oss.getObject("bucket", "ingestion/task/item/result.json.gz")).thenReturn(object);

        assertThat(adapter.readArtifact(
                "ingestion/task/item/result.json.gz", 100)).isEqualTo(content);
        verify(oss).shutdown();
    }

    @Test
    void readArtifact_shouldRejectDigestMismatch() {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        OSSObject object = object(content, "0".repeat(64));
        when(oss.getObject("bucket", "ingestion/task/item/result.json.gz")).thenReturn(object);

        assertThatThrownBy(() -> adapter.readArtifact(
                "ingestion/task/item/result.json.gz", 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("integrity");
        verify(oss).shutdown();
    }

    @Test
    void readArtifact_shouldRejectMissingDigestMetadata() {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        OSSObject object = object(content, null);
        when(oss.getObject("bucket", "ingestion/task/item/result.json.gz")).thenReturn(object);

        assertThatThrownBy(() -> adapter.readArtifact(
                "ingestion/task/item/result.json.gz", 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("integrity");
        verify(oss).shutdown();
    }

    @Test
    void readArtifact_shouldRejectMalformedDigestMetadata() {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        OSSObject object = object(content, "not-a-sha256");
        when(oss.getObject("bucket", "ingestion/task/item/result.json.gz")).thenReturn(object);

        assertThatThrownBy(() -> adapter.readArtifact(
                "ingestion/task/item/result.json.gz", 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("integrity");
        verify(oss).shutdown();
    }

    private OSSObject object(byte[] content, String sha256) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (sha256 != null) {
            metadata.addUserMetadata("artifact-sha256", sha256);
        }
        OSSObject object = new OSSObject();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new ByteArrayInputStream(content));
        return object;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
