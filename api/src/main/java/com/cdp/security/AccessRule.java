package com.cdp.security;

import java.util.Collection;
import java.util.function.Supplier;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * 角色矩陣的一列（auth.md §2）：需要某角色（含 RoleHierarchy 可達的），可再限定憑證種類（僅 Bearer／僅 session）。
 * 未認證一律不通過（交給 entry point 回 401）。
 */
final class AccessRule implements AuthorizationManager<RequestAuthorizationContext> {

    private final RoleHierarchy hierarchy;
    private final Role role;
    private final Credential credential;

    private AccessRule(RoleHierarchy hierarchy, Role role, Credential credential) {
        this.hierarchy = hierarchy;
        this.role = role;
        this.credential = credential;
    }

    /** 任何憑證，只看角色。 */
    static AccessRule role(RoleHierarchy hierarchy, Role role) {
        return new AccessRule(hierarchy, role, null);
    }

    /** 角色且限定憑證種類。 */
    static AccessRule role(RoleHierarchy hierarchy, Role role, Credential credential) {
        return new AccessRule(hierarchy, role, credential);
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> supplier, RequestAuthorizationContext context) {
        Authentication auth = supplier.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        Collection<? extends GrantedAuthority> reachable =
                hierarchy.getReachableGrantedAuthorities(auth.getAuthorities());
        boolean hasRole = reachable.stream().anyMatch(a -> role.authority().equals(a.getAuthority()));
        boolean hasCredential = credential == null
                || auth.getAuthorities().stream().anyMatch(a -> credential.authority().equals(a.getAuthority()));
        return new AuthorizationDecision(hasRole && hasCredential);
    }
}
