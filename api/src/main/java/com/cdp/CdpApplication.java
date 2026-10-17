package com.cdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 後端進入點。套件分工見 docs/design/DESIGN-01.md「檔案分工」。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CdpApplication.class, args);
    }
}
