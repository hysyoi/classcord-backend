package com.hys.classcord.auth.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.security.SecureRandom;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    /**
     * 允許的 CORS 來源白名單，透過 app.cors.allowed-origins 設定。 開發環境可設為 localhost，生產環境務必限縮為正式網域。 yml
     * 中使用逗號分隔字串，這裡用 SpEL split 轉換為 List。
     */
    @Value("#{\"${app.cors.allowed-origins}\".split(\"\\s*,\\s*\")}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // 加鹽雜湊工具
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom(); // SecureRandom (安全隨機數產生器)
    }

    // 停用 JwtAuthenticationFilter 的自動雙重註冊
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // 設為 false 停用全域註冊
        return registration;
    }

    // 停用 RateLimitFilter 的自動雙重註冊
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // 設為 false 停用全域註冊
        return registration;
    }

    // Swagger
    @Bean
    public OpenAPI customizeOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                // 1. 定義安全驗證的規格：使用的是 HTTP Bearer JWT 格式
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")))
                // 2. 全域套用這個驗證規格，讓每個 API 右上角都出現小鎖頭
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // swagger
                                        // todo 上線時記得在 yml 關掉，也可考慮限制 IP
                                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                                        .permitAll()
                                        .requestMatchers("/v1/auth/**")
                                        .permitAll()
                                        // todo 前端用Polyfill
                                        .requestMatchers("/v1/materials/questions/tasks/*/stream")
                                        .permitAll()
                                        .dispatcherTypeMatchers(
                                                jakarta.servlet.DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers("/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());

        // 1. 將 rateLimitFilter 放在 UsernamePasswordAuthenticationFilter 之前 (權重較低，先執行)
        http.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        // 2. 將 jwtAuthenticationFilter 放在 UsernamePasswordAuthenticationFilter 的位置 (權重較高，後執行)
        http.addFilterAt(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 使用明確的來源白名單，避免 allowCredentials=true + 萬用字元導致的安全漏洞
        // 來源由 app.cors.allowed-origins 設定，開發/生產環境透過 yml profile 切換
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
