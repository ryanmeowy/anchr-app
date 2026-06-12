package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.integration.ai.ParseResponse;
import com.anchr.core.kb.domain.model.Asset;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Maps docling chunks into the ingestion pipeline's TextChunk model.
 */
@Component
@RequiredArgsConstructor
public class DoclingChunkMapper {

    private final IdGen idgen;

    public List<Chunk> toTextChunks(Asset asset, List<ParseResponse.Chunk> chunks) {
        String title = StringUtils.hasText(asset.getTitle()) ? asset.getTitle() : asset.getFileName();
        List<Chunk> res = Lists.newArrayList();
        for (int i = 0; i < chunks.size(); i++) {
            ParseResponse.Chunk currentChunk = chunks.get(i);
            if (null == chunks.get(i)) {
                continue;
            }
            int pageNo = CollectionUtils.isEmpty(currentChunk.pageRange()) ? 0 : currentChunk.pageRange().getFirst();
            Chunk chunk = Chunk.builder()
                    .segmentId(idgen.nextIdStr())
                    .kbId(asset.getKbId())
                    .assetId(asset.getId())
                    .title(title)
                    .pageNo(pageNo)
                    .chunkText(currentChunk.textPlain())
                    .chunkOrder(i)
                    .sourceRef(asset.getObjectKey())
                    .embedding(null)
                    .build();
            res.add(chunk);
        }
        return res;
    }
}
