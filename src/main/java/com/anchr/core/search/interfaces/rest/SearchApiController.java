package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.model.Result;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SearchService;
import com.anchr.core.search.application.VectorCompareService;
import com.anchr.core.search.application.support.HotSearchManager;
import com.anchr.core.search.domain.port.SearchContentPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.port.SearchOcrPort;
import com.anchr.core.search.domain.strategy.StrategySelectionContext;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import com.anchr.core.search.interfaces.rest.dto.VectorCompareResultDTO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static com.anchr.core.common.constant.SearchConstant.IMAGE_MAX_SIZE;

/**
 * rest api controller for vision search functionality;
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/vision")
@Validated
@RequiredArgsConstructor
public class SearchApiController {

    @Value("${app.debug.strategy-header-enabled:false}")
    private boolean strategyHeaderEnabled;

    private final SearchService searchService;
    private final HotSearchManager hotSearchManager;
    private final SearchContentPort searchContentPort;
    private final SearchOcrPort searchOcrPort;
    private final SearchObjectStoragePort objectStoragePort;
    private final Executor imageGenTaskExecutor;
    private final VectorCompareService vectorCompareService;

    @PostMapping("/search")
    public Result<List<SearchResultDTO>> search(@Valid @RequestBody SearchQueryDTO query, HttpServletResponse response) {
        try {
            return Result.success(searchService.search(query));
        } finally {
            attachStrategyDebugHeaders(response);
            StrategySelectionContext.clear();
        }
    }

    @PostMapping("/search-page")
    public Result<SearchPageDTO> searchPage(@Valid @RequestBody SearchPageQueryDTO query, HttpServletResponse response) {
        try {
            return Result.success(searchService.searchPage(query));
        } finally {
            attachStrategyDebugHeaders(response);
            StrategySelectionContext.clear();
        }
    }

    @GetMapping("/similar")
    public Result<List<SearchResultDTO>> searchSimilar(@RequestParam @NotBlank(message = "id cannot be empty") String id) {
        return Result.success(searchService.searchSimilarById(id));
    }

    @GetMapping("/hot-words")
    public Result<List<String>> getHotWords() {
        return Result.success(hotSearchManager.getTopHotWords());
    }

    @PostMapping(value = "/search-by-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<List<SearchResultDTO>> searchByImage(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(value = "limit", defaultValue = "20") int limit,
                                                       HttpServletResponse response) {
        try {
            if (file.isEmpty()) {
                throw new BusinessException(ApiError.IMAGE_UPLOAD_REQUIRED);
            }
            if (file.getSize() > IMAGE_MAX_SIZE) {
                throw new BusinessException(ApiError.UPLOAD_TOO_LARGE);
            }

            return Result.success(searchService.searchByImage(file, limit));
        } finally {
            attachStrategyDebugHeaders(response);
            StrategySelectionContext.clear();
        }
    }

    @PostMapping(
            value = "/analyze/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/event-stream;charset=UTF-8"
    )
    public SseEmitter analyzeByImageStream(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "mode", defaultValue = "general") String mode,
                                           @RequestParam(value = "enableOcr", defaultValue = "true") boolean enableOcr,
                                           @RequestParam(value = "enableGraph", defaultValue = "true") boolean enableGraph) {
        SseEmitter emitter = new SseEmitter(120_000L);
        imageGenTaskExecutor.execute(() -> runAnalyzeTask(emitter, file, mode, enableOcr, enableGraph));
        return emitter;
    }

    @PostMapping(value = "/vector-compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<VectorCompareResultDTO> vectorCompare(@RequestParam("leftType") String leftType,
                                                        @RequestParam(value = "leftText", required = false) String leftText,
                                                        @RequestParam(value = "leftFile", required = false) MultipartFile leftFile,
                                                        @RequestParam("rightType") String rightType,
                                                        @RequestParam(value = "rightText", required = false) String rightText,
                                                        @RequestParam(value = "rightFile", required = false) MultipartFile rightFile) {
        VectorCompareResultDTO result = vectorCompareService.compare(
                leftType,
                leftText,
                leftFile,
                rightType,
                rightText,
                rightFile
        );
        return Result.success(result);
    }

    private void runAnalyzeTask(SseEmitter emitter,
                                MultipartFile file,
                                String mode,
                                boolean enableOcr,
                                boolean enableGraph) {
        try {
            if (file == null || file.isEmpty()) {
                sendEvent(emitter, "error", Map.of("message", ApiError.IMAGE_UPLOAD_REQUIRED.getMessage()));
                sendDone(emitter, false);
                return;
            }
            if (file.getSize() > IMAGE_MAX_SIZE) {
                sendEvent(emitter, "error", Map.of("message", ApiError.UPLOAD_TOO_LARGE.getMessage()));
                sendDone(emitter, false);
                return;
            }
            String fileName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unknown";
            sendEvent(emitter, "meta", Map.of(
                    "fileName", fileName,
                    "fileSize", file.getSize(),
                    "mode", StringUtils.hasText(mode) ? mode : "general",
                    "enableOcr", enableOcr,
                    "enableGraph", enableGraph
            ));

            String objectKey = objectStoragePort.uploadFile(file);
            if (!StringUtils.hasText(objectKey)) {
                sendEvent(emitter, "error", Map.of("message", "Upload image failed, please try again."));
                sendDone(emitter, false);
                return;
            }
            String imageUrl = objectStoragePort.buildAiImageInput(objectKey, SearchObjectStoragePort.AiInputValidity.MEDIUM);

            String summary = safeTrim(searchContentPort.generateSummary(imageUrl));
            streamTextChunks(emitter, "summary", summary, 48);
            sendEvent(emitter, "summary_end", Map.of("text", summary));

            List<String> tags = searchContentPort.generateTags(imageUrl);
            sendEvent(emitter, "tags", Map.of("items", tags == null ? List.of() : tags));

            String ocrText = "";
            if (enableOcr) {
                ocrText = safeTrim(searchOcrPort.extractText(imageUrl));
                streamTextChunks(emitter, "ocr", ocrText, 80);
                sendEvent(emitter, "ocr_end", Map.of("text", ocrText));
            } else {
                sendEvent(emitter, "ocr_end", Map.of("text", ""));
            }

            List<GraphTripleDTO> graph = List.of();
            if (enableGraph) {
                graph = Optional.ofNullable(searchContentPort.generateGraph(imageUrl))
                        .orElse(List.of())
                        .stream()
                        .map(t -> new GraphTripleDTO(t.getS(), t.getP(), t.getO()))
                        .toList();
            }
            sendEvent(emitter, "graph", Map.of("items", graph == null ? List.of() : graph));

            List<String> suggestions = buildSearchSuggestions(tags, ocrText, graph);
            sendEvent(emitter, "suggestions", Map.of("items", suggestions));
            sendDone(emitter, true);
        } catch (Exception e) {
            log.error("Analyze image stream failed: {}", e.getMessage(), e);
            try {
                sendEvent(emitter, "error", Map.of("message", "Analyze failed, try again later."));
                sendDone(emitter, false);
            } catch (IOException ignored) {
                emitter.completeWithError(e);
            }
        }
    }

    private void sendDone(SseEmitter emitter, boolean success) throws IOException {
        sendEvent(emitter, "done", Map.of("ok", success));
        emitter.complete();
    }

    private void sendEvent(SseEmitter emitter, String event, Object payload) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(payload));
    }

    private void streamTextChunks(SseEmitter emitter, String event, String text, int chunkSize) throws IOException {
        if (!StringUtils.hasText(text)) {
            return;
        }
        int normalizedChunk = Math.max(20, chunkSize);
        for (int start = 0; start < text.length(); start += normalizedChunk) {
            int end = Math.min(text.length(), start + normalizedChunk);
            String delta = text.substring(start, end);
            sendEvent(emitter, event, Map.of("delta", delta));
        }
    }

    private String safeTrim(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private List<String> buildSearchSuggestions(List<String> tags, String ocrText, List<GraphTripleDTO> graph) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (tags != null) {
            tags.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ordered::add);
        }

        if (StringUtils.hasText(ocrText)) {
            Stream.of(ocrText.split("[，。！？、,.;:\\s]+"))
                    .map(String::trim)
                    .filter(token -> token.length() >= 2 && token.length() <= 12)
                    .limit(12)
                    .forEach(ordered::add);
        }

        if (graph != null) {
            graph.stream()
                    .filter(item -> item != null)
                    .forEach(item -> {
                        String s = safeTrim(item.getS());
                        String p = safeTrim(item.getP());
                        String o = safeTrim(item.getO());
                        if (StringUtils.hasText(s)) ordered.add(s);
                        if (StringUtils.hasText(o)) ordered.add(o);
                        if (StringUtils.hasText(s) && StringUtils.hasText(o)) {
                            ordered.add((s + " " + o).trim());
                        }
                        if (StringUtils.hasText(s) && StringUtils.hasText(p) && StringUtils.hasText(o)) {
                            ordered.add((s + " " + p + " " + o).trim());
                        }
                    });
        }

        List<String> suggestions = new ArrayList<>(ordered);
        if (suggestions.size() > 10) {
            return suggestions.subList(0, 10);
        }
        return suggestions;
    }

    private void attachStrategyDebugHeaders(HttpServletResponse response) {
        if (!strategyHeaderEnabled || response == null) {
            return;
        }
        StrategySelectionContext.get().ifPresent(snapshot -> {
            response.setHeader("X-Strategy-Requested", snapshot.getRequested());
            response.setHeader("X-Strategy-Effective", snapshot.getEffective());
            response.setHeader("X-Strategy-Fallback", String.valueOf(snapshot.isFallback()));
            response.setHeader("X-Strategy-Fallback-Reason", snapshot.getReason());
        });
    }
}
