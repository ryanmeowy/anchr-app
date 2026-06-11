package com.anchr.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class AnchrApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnchrApplication.class, args);
    }

}
