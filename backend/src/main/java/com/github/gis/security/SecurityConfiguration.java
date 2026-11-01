package com.github.gis.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService users(EditingAccess access) {
        var users = new InMemoryUserDetailsManager();
        if (access.enabled()) {
            users.createUser(User.withUsername(access.username()).password(access.passwordHash())
                    .roles("EDITOR").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, EditingAccess access) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> editor = AuthorizationManagers.allOf(
                (authentication, context) -> new AuthorizationDecision(access.enabled()),
                AuthorityAuthorizationManager.hasRole("EDITOR"));
        return http
                .cors(Customizer.withDefaults())
                // Distances is a public SELECT-only calculation; all other unsafe requests retain CSRF.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        new AntPathRequestMatcher("/api/v1/osm/distances", "POST")))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/*.js", "/*.css", "/favicon.ico",
                                "/api/v1/session", "/api/v1/osm/points", "/api/v1/osm/roads",
                                "/api/v1/osm/polygons").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/", "/*.js", "/*.css",
                                "/api/v1/osm/points", "/api/v1/osm/roads", "/api/v1/osm/polygons").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/osm/distances").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/osm/points/add").access(editor)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/osm/points/*").access(editor)
                        // Unpublished files still return 404 through Spring's resource handler.
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .formLogin(form -> form.defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/").deleteCookies("JSESSIONID"))
                .exceptionHandling(errors -> errors
                        // A global custom entry point disables Spring's generated login page.
                        .defaultAuthenticationEntryPointFor((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"authentication_required\"}");
                        }, new AntPathRequestMatcher("/api/**"))
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"access_denied\"}");
                        }))
                .build();
    }
}
