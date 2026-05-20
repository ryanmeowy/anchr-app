package com.anchr.core.auth.application;

/**
 * Auth application service for issuing encrypted upload credentials.
 */
public interface OssService {

    String fetchStsToken();

}
