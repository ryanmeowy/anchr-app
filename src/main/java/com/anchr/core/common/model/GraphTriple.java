package com.anchr.core.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shared-kernel graph triple value object.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphTriple {
    private String s;
    private String p;
    private String o;
}
