package com.smart.vision.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Asset-level Top3 result card.
 */
@Data
public class ResultCardDTO implements Serializable {

    private String assetId;
    private String assetType;
    private String fileName;
    private String title;
    private Double score;
    private Integer hitCount;
    private ResultHitDTO primaryHit;
    private List<ResultHitDTO> additionalHits = new ArrayList<>();
}
