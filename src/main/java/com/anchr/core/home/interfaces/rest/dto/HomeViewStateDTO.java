package com.anchr.core.home.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Frontend-safe home page state contract.
 */
@Value
@Builder
public class HomeViewStateDTO {

    boolean loading;
    boolean empty;
    boolean error;
}
