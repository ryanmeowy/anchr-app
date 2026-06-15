package com.anchr.core.search.interfaces.rest.dto;

import com.anchr.core.common.model.BboxInfo;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Preview anchor for locating a segment inside an asset.
 */
@Data
@Builder
public class PreviewAnchorDTO implements Serializable {

    private Integer pageNo;
    private Integer chunkOrder;
    private List<BboxInfo> bbox;
    private Integer imageWidth;
    private Integer imageHeight;

}
