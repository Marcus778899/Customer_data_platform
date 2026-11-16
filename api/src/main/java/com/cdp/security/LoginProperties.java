package com.cdp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cdp.login.*：登入限速（D-045、D-084，auth.md §3）。CDP_LOGIN_MAX_FAILURES（email＋來源 IP）、CDP_LOGIN_LOCKOUT、
 * CDP_LOGIN_IP_MAX_FAILURES（來源 IP 跨 email 總量）；驗證見 {@link LoginRateLimiter}。
 */
@ConfigurationProperties("cdp.login")
public record LoginProperties(int maxFailures, Duration lockout, int ipMaxFailures) {}
