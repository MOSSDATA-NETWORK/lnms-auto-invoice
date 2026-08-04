package com.autoinvoice.api.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        UUID tenantId,
        String tenantCode,
        String username,
        String displayName,
        String password,
        boolean mfaEnabled,
        String mfaSecretCiphertext,
        boolean mfaVerified,
        long securityVersion,
        Set<String> permissions,
        boolean enabled,
        boolean credentialsNonExpired,
        boolean mustChangePassword
) implements UserDetails {
    @Serial
    private static final long serialVersionUID = 1L;

    public AuthenticatedUser(UUID userId, UUID tenantId, String tenantCode, String username,
                             String displayName, String password, boolean mfaEnabled,
                             String mfaSecretCiphertext, boolean mfaVerified, long securityVersion,
                             Set<String> permissions, boolean enabled) {
        this(userId, tenantId, tenantCode, username, displayName, password, mfaEnabled,
                mfaSecretCiphertext, mfaVerified, securityVersion, permissions, enabled, true, false);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public AuthenticatedUser forSession(boolean verified) {
        return new AuthenticatedUser(userId, tenantId, tenantCode, username, displayName, "",
                mfaEnabled, null, mfaEnabled && verified, securityVersion, permissions, enabled,
                credentialsNonExpired, mustChangePassword);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[" +
                "userId=" + userId +
                ", tenantId=" + tenantId +
                ", tenantCode=" + tenantCode +
                ", username=" + username +
                ", displayName=" + displayName +
                ", mfaEnabled=" + mfaEnabled +
                ", mfaVerified=" + mfaVerified +
                ", securityVersion=" + securityVersion +
                ", permissions=" + permissions +
                ", enabled=" + enabled +
                ", credentialsNonExpired=" + credentialsNonExpired +
                ", mustChangePassword=" + mustChangePassword +
                ']';
    }
}
