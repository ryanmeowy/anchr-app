package com.anchr.core.settings.application.provider;

import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.application.impl.ProviderSelectionService;
import com.anchr.core.settings.domain.model.ProviderType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Runtime router for object storage capability.
 */
@Primary
@Service
public class ObjectStorageProviderRouter extends ProviderRouterSupport
        implements SearchObjectStoragePort, IngestionObjectStoragePort {

    public ObjectStorageProviderRouter(ProviderSelectionService providerSelectionService,
                                       ProviderRuntimeRegistry providerRuntimeRegistry) {
        super(providerSelectionService, providerRuntimeRegistry);
    }

    @Override
    public String uploadFile(MultipartFile file) {
        return delegate(ProviderType.OBJECT_STORAGE, SearchObjectStoragePort.class).uploadFile(file);
    }

    @Override
    public String buildAiImageInput(String objectKey, AiInputValidity validity) {
        return delegate(ProviderType.OBJECT_STORAGE, SearchObjectStoragePort.class)
                .buildAiImageInput(objectKey, validity);
    }

    @Override
    public String buildDisplayImageUrl(String objectKey) {
        return delegate(ProviderType.OBJECT_STORAGE, SearchObjectStoragePort.class).buildDisplayImageUrl(objectKey);
    }

    @Override
    public String buildPreviewUrl(String objectKey) {
        return delegate(ProviderType.OBJECT_STORAGE, SearchObjectStoragePort.class).buildPreviewUrl(objectKey);
    }

    @Override
    public String buildDownloadUrl(String objectKey) {
        return delegate(ProviderType.OBJECT_STORAGE, IngestionObjectStoragePort.class).buildDownloadUrl(objectKey);
    }

    @Override
    public String buildAiImageInput(String objectKey) {
        return delegate(ProviderType.OBJECT_STORAGE, IngestionObjectStoragePort.class).buildAiImageInput(objectKey);
    }
}
