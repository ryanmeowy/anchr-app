package com.anchr.core.settings.application.provider;

import com.anchr.core.common.model.GraphTriple;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.ingestion.domain.port.IngestionContentPort;
import com.anchr.core.search.domain.port.QueryGraphParserPort;
import com.anchr.core.search.domain.port.SearchContentPort;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import com.anchr.core.common.config.EmbeddingProperties;
import com.anchr.core.settings.application.impl.ProviderRuntimeRegistry;
import com.anchr.core.settings.domain.model.ProviderType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runtime router for generation capability.
 */
@Primary
@Service
public class GenerationProviderRouter extends ProviderRouterSupport
        implements SearchContentPort, IngestionContentPort, QueryGraphParserPort, ConversationRewritePort {

    public GenerationProviderRouter(ProviderRuntimeRegistry providerRuntimeRegistry,
                                    EmbeddingProperties embeddingProperties) {
        super(providerRuntimeRegistry, embeddingProperties);
    }

    @Override
    public String generateSummary(String imageInput) {
        return delegate(ProviderType.GENERATION, SearchContentPort.class).generateSummary(imageInput);
    }

    @Override
    public String generateFileName(String imageInput) {
        return delegate(ProviderType.GENERATION, IngestionContentPort.class).generateFileName(imageInput);
    }

    @Override
    public List<String> generateTags(String imageInput) {
        return delegate(ProviderType.GENERATION, IngestionContentPort.class).generateTags(imageInput);
    }

    @Override
    public List<GraphTriple> generateGraph(String imageInput) {
        return delegate(ProviderType.GENERATION, IngestionContentPort.class).generateGraph(imageInput);
    }

    @Override
    public List<GraphTripleDTO> parseFromKeyword(String keyword) {
        return delegate(ProviderType.GENERATION, QueryGraphParserPort.class).parseFromKeyword(keyword);
    }

    @Override
    public String generateText(String prompt) {
        return delegate(ProviderType.GENERATION, ConversationRewritePort.class).generateText(prompt);
    }
}
