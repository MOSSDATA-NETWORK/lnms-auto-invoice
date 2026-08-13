package com.autoinvoice.api.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.security.Security;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
    }

    @Bean
    AuthenticationManager authenticationManager(DatabaseUserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TenantContextFilter tenantContextFilter,
                                            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
                                            MfaAuthorizationManager mfaAuthorizationManager) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");

        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .hasAnyAuthority("audit.read", "system.admin")
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/sign-in", "/api/v1/auth/mfa/verify")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/system/**",
                                "/api/v1/invoice-previews/*/approve-business",
                                "/api/v1/invoice-previews/*/approve-finance",
                                "/api/v1/invoice-previews/*/reject",
                                "/api/v1/invoice-previews/*/finalize",
                                "/api/v1/invoice-previews/**",
                                "/api/v1/invoice-profiles/**",
                                "/api/v1/invoices/*/void",
                                "/api/v1/invoices/*/create-replacement-preview",
                                "/api/v1/invoices/*/send",
                                "/api/v1/payments",
                                "/api/v1/payments/**",
                                "/api/v1/jobs/*/retry",
                                "/api/v1/webhook-endpoints",
                                "/api/v1/webhook-endpoints/**",
                                "/api/v1/contracts/*/template",
                                "/api/v1/invoice-profiles/*/excel-template",
                                "/api/v1/librenms/instances",
                                "/api/v1/billing-entities",
                                "/api/v1/billing-entities/**",
                                "/api/v1/pricing-rules/**",
                                "/api/v1/pricing-rule-versions/**",
                                "/api/v1/pricing-rule-versions/*/publish",
                                "/api/v1/pricing-rule-versions/*/retire",
                                "/api/v1/template-versions/*/publish",
                                "/api/v1/invoice-templates/*/rollback")
                        .access(mfaAuthorizationManager)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/operations/settings",
                                "/api/v1/librenms/instances/**",
                                "/api/v1/billing-entities/**")
                        .access(mfaAuthorizationManager)
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            boolean mfaMissing = SecurityContextHolder.getContext().getAuthentication()
                                    instanceof Authentication authentication
                                    && authentication.getPrincipal() instanceof AuthenticatedUser user
                                    && user.enabled()
                                    && user.mfaEnabled()
                                    && !user.mfaVerified();
                            response.setStatus(403);
                            response.setContentType("application/problem+json");
                            response.setCharacterEncoding("UTF-8");
                            String code = mfaMissing ? "MFA_REQUIRED" : "FORBIDDEN";
                            String detail = mfaMissing
                                    ? "此操作需要先启用并通过 TOTP MFA 验证：请在「系统管理」页完成 MFA 注册后重新登录"
                                    : "没有执行此操作的权限";
                            response.getWriter().write("""
                                    {"type":"https://auto-invoice.example/problems/%s","title":"Forbidden","status":403,"detail":"%s","instance":"%s","code":"%s"}
                                    """.formatted(code.toLowerCase().replace('_', '-'), detail,
                                    request.getRequestURI(), code));
                        }))
                .logout(logout -> logout.disable())
                .addFilterAfter(tenantContextFilter, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, TenantContextFilter.class)
                .build();
    }
}
