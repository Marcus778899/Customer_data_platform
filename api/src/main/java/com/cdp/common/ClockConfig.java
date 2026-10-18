package com.cdp.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全系統唯一時間來源（README「時間」）。測試以 @Primary 的可調 Clock bean 覆蓋來快轉。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
