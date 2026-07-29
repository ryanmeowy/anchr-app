package com.anchr.core.conversation.interfaces.rest.dto;

import com.anchr.core.common.model.BboxInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** Conversation-owned response anchor with the existing JSON shape. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultAnchorDTO implements Serializable {

    private Integer pageNo;
    private Integer chunkOrder;
    private List<BboxInfo> bbox;
    private Integer imageWidth;
    private Integer imageHeight;
}
