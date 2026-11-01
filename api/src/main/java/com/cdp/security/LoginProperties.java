package com.cdp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cdp.login.*：登入限速（D-045，auth.md §3）。CDP_LOGIN_MAX_FAILURES、CDP_LOGIN_LOCKOUT；驗證見 {@link LoginRateLimiter}。 */
@ConfigurationProperties("cdp.login")
public record LoginProperties(int maxFailures, Duration lockout) {}
