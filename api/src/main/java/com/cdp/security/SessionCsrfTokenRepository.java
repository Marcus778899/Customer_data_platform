package com.cdp.security;

import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * auth.md §1.1 的 CSRF 權杖：雙重提交 cookie（XSRF-TOKEN，非 HttpOnly、SameSite=Strict）加上綁定 session。
 * cookie、session 內存的值與標頭 X-XSRF-TOKEN 三者一致才放行，所以登入／登出之後舊值立即失效，
 * 缺 cookie 只帶標頭也不行。權杖只在 {@link #rotate}（登入成功、GET /auth/me）發新值；CsrfFilter 驗證失敗時
 * 產生的暫時權杖不寫回（{@link #saveToken} 不做事），避免被誤觸發而換掉合法值。
 */
public class SessionCsrfTokenRepository implements CsrfTokenRepository {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String PARAMETER_NAME = "_csrf";
    private static final String SESSION_ATTRIBUTE = SessionCsrfTokenRepository.class.getName() + ".TOKEN";

    private final SecureRandom random = new SecureRandom();
    private final boolean secure;

    public SessionCsrfTokenRepository(boolean secure) {
        this.secure = secure;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, newValue());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // 刻意不做事：見類別說明
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object stored = session.getAttribute(SESSION_ATTRIBUTE);
        String cookie = cookieValue(request);
        if (stored instanceof String value && value.equals(cookie)) {
            return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, value);
        }
        return null;
    }

    /** 發新值：寫進目前 session 並下發 cookie。沒有 session 時不做事。 */
    public void rotate(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        String value = newValue();
        session.setAttribute(SESSION_ATTRIBUTE, value);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(value, -1).toString());
    }

    /** 清除 cookie（登出）。 */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(false)
                .secure(secure)
                .sameSite("Strict");
        if (maxAgeSeconds >= 0) {
            b.maxAge(maxAgeSeconds);
        }
        return b.build();
    }

    private String newValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
