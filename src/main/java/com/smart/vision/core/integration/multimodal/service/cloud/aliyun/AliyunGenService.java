package com.smart.vision.core.integration.multimodal.service.cloud.aliyun;

import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.smart.vision.core.conversation.domain.port.ConversationRewritePort;
import com.smart.vision.core.common.model.GraphTriple;
import com.smart.vision.core.ingestion.domain.port.IngestionContentPort;
import com.smart.vision.core.integration.multimodal.manager.aliyun.AliyunGenManager;
import com.smart.vision.core.integration.multimodal.domain.model.AliyunErrorCode;
import com.smart.vision.core.search.domain.port.QueryGraphParserPort;
import com.smart.vision.core.search.domain.port.SearchContentPort;
import com.smart.vision.core.search.interfaces.rest.dto.GraphTripleDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "gen", havingValue = "aliyun")
public class AliyunGenService implements SearchContentPort, IngestionContentPort, QueryGraphParserPort, ConversationRewritePort {

    private final AliyunGenManager genManager;

    @Override
    public String generateSummary(String imageUrl) {
        try {
            return genManager.generateSummary(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (UploadFileException e) {
            log.error(AliyunErrorCode.UPLOAD_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("generate summary failed, try again later.");
    }

    @Override
    public String generateFileName(String imageUrl) {
        try {
            return genManager.genFileName(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (UploadFileException e) {
            log.error(AliyunErrorCode.UPLOAD_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("generate file name failed, try again later.");
    }

    @Override
    public List<String> generateTags(String imageUrl) {
        try {
            return genManager.generateTags(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (UploadFileException e) {
            log.error(AliyunErrorCode.UPLOAD_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("generate tags failed, try again later.");
    }

    /**
     * Generate graph for the image
     *
     * @param imageUrl Image URL
     * @return List of graph triples
     */
    @Override
    public List<GraphTriple> generateGraph(String imageUrl) {
        try {
            return genManager.generateGraph(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (UploadFileException e) {
            log.error(AliyunErrorCode.UPLOAD_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("generate graph failed, try again later.");
    }

    @Override
    public List<GraphTripleDTO> parseFromKeyword(String keyword) {
        try {
            List<GraphTriple> triples = genManager.praseTriplesFromKeyword(keyword);
            if (triples == null || triples.isEmpty()) {
                return List.of();
            }
            return triples.stream()
                    .filter(Objects::nonNull)
                    .map(t -> new GraphTripleDTO(t.getS(), t.getP(), t.getO()))
                    .toList();
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (InputRequiredException e) {
            log.error(AliyunErrorCode.ILLEGAL_INPUT.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("parse triples from keyword failed, try again later.");
    }

    @Override
    public String generateText(String prompt) {
        try {
            return genManager.generateText(prompt);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (InputRequiredException e) {
            log.error(AliyunErrorCode.ILLEGAL_INPUT.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("generate text failed, try again later.");
    }
}
