
package com.smart.vision.core.integration.multimodal.service.local;

import com.google.common.collect.Lists;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.common.model.GraphTriple;
import com.smart.vision.core.conversation.domain.port.ConversationRewritePort;
import com.smart.vision.core.grpc.VisionProto;
import com.smart.vision.core.grpc.VisionServiceGrpc;
import com.smart.vision.core.ingestion.domain.port.IngestionContentPort;
import com.smart.vision.core.integration.multimodal.domain.model.PromptEnum;
import com.smart.vision.core.search.domain.port.QueryGraphParserPort;
import com.smart.vision.core.search.domain.port.SearchContentPort;
import com.smart.vision.core.search.interfaces.rest.dto.GraphTripleDTO;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.smart.vision.core.common.constant.CommonConstant.DEFAULT_IMAGE_NAME;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "gen", havingValue = "local")
public class LocalGenService implements SearchContentPort, IngestionContentPort, QueryGraphParserPort, ConversationRewritePort {

    @SuppressWarnings("unused")
    @GrpcClient("vision-python-service")
    private VisionServiceGrpc.VisionServiceBlockingStub visionStub;

    @Value("${grpc.deadline.stream-caption-ms:20000}")
    private long streamCaptionDeadlineMs;
    @Value("${grpc.deadline.generate-filename-ms:3000}")
    private long generateFileNameDeadlineMs;
    @Value("${grpc.deadline.generate-tags-ms:8000}")
    private long generateTagsDeadlineMs;
    @Value("${grpc.deadline.generate-graph-ms:10000}")
    private long generateGraphDeadlineMs;
    @Value("${grpc.deadline.parse-graph-ms:8000}")
    private long parseGraphDeadlineMs;

    @Override
    public String generateSummary(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        VisionProto.GenRequest request = VisionProto.GenRequest.newBuilder()
                .setImageUrl(imageUrl)
                .setPrompt(PromptEnum.DEFAULT.getPrompt())
                .build();
        long startNs = System.nanoTime();
        try {
            Iterator<VisionProto.StringResponse> iterator = visionStub
                    .withDeadlineAfter(streamCaptionDeadlineMs, TimeUnit.MILLISECONDS)
                    .generateCaption(request);
            StringBuilder sb = new StringBuilder();
            while (iterator.hasNext()) {
                String chunk = Optional.ofNullable(iterator.next()).map(VisionProto.StringResponse::getContent).orElse("");
                if (!chunk.isBlank()) {
                    sb.append(chunk);
                }
            }
            String result = sb.toString().trim();
            if (result.isEmpty()) {
                throw new BusinessException(ApiError.INTERNAL_ERROR, "Local summary result is empty.");
            }
            return result.lines().collect(Collectors.joining(System.lineSeparator())).trim();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.error("gRPC generateSummary failed: statusCode={}, deadlineMs={}, elapsedMs={}", e.getStatus().getCode(), streamCaptionDeadlineMs, elapsedMs, e);
            throw new RuntimeException("generate summary failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC generateSummary call failed: {}", e.getMessage(), e);
            throw new RuntimeException("generate summary failed, try again later.");
        }
    }

    @Override
    public String generateFileName(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) return DEFAULT_IMAGE_NAME;
        try {
            VisionProto.GenRequest request = VisionProto.GenRequest.newBuilder()
                    .setImageUrl(imageUrl)
                    .build();
            long startNs = System.nanoTime();
            VisionProto.GenFileNameResponse response = visionStub
                    .withDeadlineAfter(generateFileNameDeadlineMs, TimeUnit.MILLISECONDS)
                    .generateFileName(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.debug("gRPC generateFileName success: deadlineMs={}, elapsedMs={}", generateFileNameDeadlineMs, elapsedMs);
            return response.getName();
        } catch (StatusRuntimeException e) {
            log.error("gRPC generateFileName failed: statusCode={}, deadlineMs={}", e.getStatus().getCode(), generateFileNameDeadlineMs, e);
            throw new RuntimeException("generate file name failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC gen name call failed: {}", e.getMessage(), e);
            throw new RuntimeException("generate file name failed, try again later.");
        }
    }


    @Override
    public List<String> generateTags(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        try {
            VisionProto.GenRequest request = VisionProto.GenRequest.newBuilder()
                    .setImageUrl(imageUrl)
                    .build();
            VisionProto.GenTagsResponse response = visionStub
                    .withDeadlineAfter(generateTagsDeadlineMs, TimeUnit.MILLISECONDS)
                    .generateTags(request);
            return response.getTagList();
        } catch (StatusRuntimeException e) {
            log.error("gRPC generateTags failed: statusCode={}, deadlineMs={}", e.getStatus().getCode(), generateTagsDeadlineMs, e);
            throw new RuntimeException("generate tags failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC gen tags call failed: {}", e.getMessage(), e);
            throw new RuntimeException("generate tags failed, try again later.");
        }
    }

    /**
     * Generate graph for the image
     *
     * @param imageUrl Image URL
     * @return List of graph triples
     */
    @Override
    public List<GraphTriple> generateGraph(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        try {
            VisionProto.GenRequest request = VisionProto.GenRequest.newBuilder()
                    .setImageUrl(imageUrl)
                    .build();
            VisionProto.GraphTriplesResponse response = visionStub
                    .withDeadlineAfter(generateGraphDeadlineMs, TimeUnit.MILLISECONDS)
                    .extractGraphTriples(request);
            List<VisionProto.GraphTriple> graphTriples = Optional.ofNullable(response).map(VisionProto.GraphTriplesResponse::getTripleList).orElseGet(Lists::newArrayList);
            return graphTriples.stream().map(x -> new GraphTriple(x.getS(), x.getP(), x.getO())).toList();
        } catch (StatusRuntimeException e) {
            log.error("gRPC generateGraph failed: statusCode={}, deadlineMs={}", e.getStatus().getCode(), generateGraphDeadlineMs, e);
            throw new RuntimeException("generate graph failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC gen graph call failed: {}", e.getMessage(), e);
            throw new RuntimeException("generate graph failed, try again later.");
        }
    }

    @Override
    public List<GraphTripleDTO> parseFromKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "keyword cannot be blank.");
        }
        try {
            VisionProto.TextRequest request = VisionProto.TextRequest.newBuilder().setText(keyword).build();
            VisionProto.GraphTriplesResponse response = visionStub
                    .withDeadlineAfter(parseGraphDeadlineMs, TimeUnit.MILLISECONDS)
                    .parseQueryToGraph(request);
            List<VisionProto.GraphTriple> tripleList = response.getTripleList();
            return tripleList.stream()
                    .map(x -> new GraphTripleDTO(x.getS(), x.getP(), x.getO()))
                    .toList();
        } catch (StatusRuntimeException e) {
            log.error("gRPC parseQueryToGraph failed: statusCode={}, deadlineMs={}", e.getStatus().getCode(), parseGraphDeadlineMs, e);
            throw new RuntimeException("parse triples from keyword failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC parse triples from keyword call failed: {}", e.getMessage(), e);
            throw new RuntimeException("parse triples from keyword failed, try again later.");
        }
    }

    @Override
    public String generateText(String prompt) {
        //TODO TO BE IMPLEMENTED
        throw new RuntimeException("generate text is not supported in local capability provider.");
    }
}
