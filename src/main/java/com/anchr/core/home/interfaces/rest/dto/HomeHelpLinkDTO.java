package com.anchr.core.home.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Help link item for home page.
 */
@Value
@Builder
public class HomeHelpLinkDTO {

    String title;
    String url;
}
