package com.github.gis.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gis.api.ApiError;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService users(EditingAccess access) {
    var users = new InMemoryUserDetailsManager();
    if (access.enabled()) {
      users.createUser(
          User.withUsername(access.username())
              .password(access.passwordHash())
              .roles("EDITOR")
              .build());
    }
    return users;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, EditingAccess access, ObjectMapper mapper)
      throws Exception {
    AuthorizationManager<RequestAuthorizationContext> editor =
        AuthorizationManagers.allOf(
            (authentication, context) -> new AuthorizationDecision(access.enabled()),
            AuthorityAuthorizationManager.hasRole("EDITOR"));
    return http.cors(Customizer.withDefaults())
        // Distances is a public SELECT-only calculation; all other unsafe requests retain CSRF.
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/v1/osm/distances", "POST")))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/*.js",
                        "/*.css",
                        "/favicon.ico",
                        "/api/v1/session",
                        "/api/v1/osm/points",
                        "/api/v1/osm/roads",
                        "/api/v1/osm/polygons")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.HEAD,
                        "/",
                        "/*.js",
                        "/*.css",
                        "/api/v1/osm/points",
                        "/api/v1/osm/roads",
                        "/api/v1/osm/polygons")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/osm/distances")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/osm/points/add")
                    .access(editor)
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/osm/points/*")
                    .access(editor)
                    // Unpublished files still return 404 through Spring's resource handler.
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .formLogin(form -> form.defaultSuccessUrl("/", true).permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/").deleteCookies("JSESSIONID"))
        .exceptionHandling(
            errors ->
                errors
                    // A global custom entry point disables Spring's generated login page.
                    .defaultAuthenticationEntryPointFor(
                        (request, response, exception) -> {
                          ApiError.forStatus(401).write(mapper, response, 401);
                        },
                        new AntPathRequestMatcher("/api/**"))
                    .accessDeniedHandler(
                        (request, response, exception) -> {
                          var error =
                              exception instanceof CsrfException
                                  ? new ApiError(
                                      "invalid_csrf",
                                      "Запрос отклонён: обновите сеанс и повторите операцию.")
                                  : ApiError.forStatus(403);
                          error.write(mapper, response, 403);
                        }))
        .build();
  }

  @Bean
  CorsFilter corsFilter(HandlerMappingIntrospector introspector, ObjectMapper mapper) {
    var filter = new CorsFilter(introspector);
    filter.setCorsProcessor(
        new DefaultCorsProcessor() {
          @Override
          protected void rejectRequest(ServerHttpResponse response) throws IOException {
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            response
                .getHeaders()
                .setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            response.getHeaders().setCacheControl("no-store");
            response.getBody().write(mapper.writeValueAsBytes(ApiError.forStatus(403)));
          }
        });
    return filter;
  }

  @Bean
  FilterRegistrationBean<CorsFilter> corsServletRegistration(CorsFilter corsFilter) {
    // Only the security chain owns this filter; do not register a second servlet filter.
    var registration = new FilterRegistrationBean<>(corsFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  WebSecurityCustomizer rejectedRequests(ObjectMapper mapper) {
    return web ->
        web.requestRejectedHandler(
            (request, response, exception) -> ApiError.forStatus(400).write(mapper, response, 400));
  }
}
