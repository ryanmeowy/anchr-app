
package com.anchr.core.integration.multimodal.domain.model;

import lombok.Getter;

@Getter
public enum AliyunErrorCode {
    API_KEY_MISSING(10001, "API Key is not configured, please check the environment variables"),
    CALL_FAILED(10002, "Aliyun API call failed"),
    UNKNOWN(10003, "An unknown exception occurred while invoking the remote capability."),
    UPLOAD_FAILED(10004, "Aliyun Upload Failed"),
    ILLEGAL_INPUT(10005, "Aliyun required input exception")

    ;
    
    private final int code;
    private final String message;

    AliyunErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}