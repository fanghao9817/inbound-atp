package com.haoyu.inbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InboundAtpApplication {

    public static void main(String[] args) {
        SpringApplication.run(InboundAtpApplication.class, args);
    }
}
