package com.anchr.core.settings.application.provider;

import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.application.impl.ProviderSelectionService;
import com.anchr.core.settings.domain.model.ProviderType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runtime router for rerank capability.
 */
@Primary
@Service
public class RerankProviderRouter extends ProviderRouterSupport implements SearchRerankPort {

    public RerankProviderRouter(ProviderSelectionService providerSelectionService,
                                ProviderRuntimeRegistry providerRuntimeRegistry) {
        super(providerSelectionService, providerRuntimeRegistry);
    }

    @Override
    public List<RerankItem> rerank(String query, List<String> documents, Integer topN) {
        return delegate(ProviderType.RERANK, SearchRerankPort.class).rerank(query, documents, topN);
    }
}
