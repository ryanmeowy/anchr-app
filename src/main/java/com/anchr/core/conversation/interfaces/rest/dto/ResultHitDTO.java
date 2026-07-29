package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Segment-level hit in a result card.
 */
@Data
public class ResultHitDTO implements Serializable {

    private String segmentId;
    private String snippet;
    private Double score;
    private Integer pageNo;
    private ResultAnchorDTO anchor;
    private String hitType;
}
