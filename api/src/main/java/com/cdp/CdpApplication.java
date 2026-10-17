package com.cdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 後端進入點。模組切分見 docs/design/INIT-01.md「目錄結構」。 */
@SpringBootApplication
public class CdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CdpApplication.class, args);
    }
}
