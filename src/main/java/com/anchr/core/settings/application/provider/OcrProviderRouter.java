package com.anchr.core.settings.application.provider;

import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import com.anchr.core.ingestion.domain.port.IngestionOcrPort;
import com.anchr.core.search.domain.port.SearchOcrPort;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.application.impl.ProviderSelectionService;
import com.anchr.core.settings.domain.model.ProviderType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Runtime router for OCR capability.
 */
@Primary
@Service
public class OcrProviderRouter extends ProviderRouterSupport implements SearchOcrPort, IngestionOcrPort {

    public OcrProviderRouter(ProviderSelectionService providerSelectionService,
                             ProviderRuntimeRegistry providerRuntimeRegistry) {
        super(providerSelectionService, providerRuntimeRegistry);
    }

    @Override
    public String extractText(String imageInput) {
        return delegate(ProviderType.OCR, SearchOcrPort.class).extractText(imageInput);
    }

    @Override
    public OcrStructuredResult extractStructuredText(String imageInput) {
        return delegate(ProviderType.OCR, IngestionOcrPort.class).extractStructuredText(imageInput);
    }
}
