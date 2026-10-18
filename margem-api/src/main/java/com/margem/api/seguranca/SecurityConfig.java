package com.margem.api.seguranca;

import com.margem.api.config.MargemProperties;
import com.margem.api.config.SegurancaProperties;
import com.margem.api.web.AuthController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Política: tudo em /api exige token válido, exceto cadastro/login/refresh/logout. O resto é negado.
 * Sem sessão no servidor e sem cookie de autenticação nas rotas de negócio, então CSRF não se aplica a elas;
 * o único cookie (refresh) vale só em /api/auth e essas rotas exigem o cabeçalho X-Margem-Csrf.
 */
@Configuration
@EnableWebSecurity
@EnableScheduling
public class SecurityConfig {

    @Bean
    SecurityFilterChain filtros(HttpSecurity http, JwtService jwt, RespostasSeguranca respostas,
                                LimitadorRequisicoes limitador, SegurancaProperties props,
                                @Value("${springdoc.swagger-ui.enabled:false}") boolean swagger) throws Exception {
        RequestMatcher docs = new OrRequestMatcher(
                request -> request.getRequestURI().startsWith("/swagger-ui"),
                request -> request.getRequestURI().startsWith("/v3/api-docs"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(respostas).accessDeniedHandler(respostas))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas)
                        .jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(conversor())))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.POST, "/api/auth/cadastro", "/api/auth/login",
                            "/api/auth/refresh", "/api/auth/logout").permitAll();
                    a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    a.requestMatchers("/error").permitAll();
                    if (swagger) {
                        a.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    a.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    a.requestMatchers("/api/**").authenticated();
                    a.anyRequest().denyAll();
                })
                .headers(h -> {
                    h.frameOptions(f -> f.deny());
                    h.httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000));
                    h.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER));
                    h.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
                    // A API só devolve JSON. O Swagger UI precisa de scripts, então fica fora desta regra.
                    h.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(docs),
                            new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")));
                })
                .addFilterBefore(new FiltroLimiteRequisicoes(FiltroLimiteRequisicoes.Modo.POR_IP, limitador, props.limites(), respostas),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new FiltroLimiteRequisicoes(FiltroLimiteRequisicoes.Modo.POR_USUARIO, limitador, props.limites(), respostas),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static JwtAuthenticationConverter conversor() {
        JwtGrantedAuthoritiesConverter autoridades = new JwtGrantedAuthoritiesConverter();
        autoridades.setAuthoritiesClaimName("papel");
        autoridades.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(autoridades);
        return c;
    }

    /** Só as origens listadas em MARGEM_CORS. Com cookie de refresh (credentials) o "*" não é permitido. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(MargemProperties margem) {
        List<String> origens = margem.corsOrigens();
        if (origens == null || origens.isEmpty() || origens.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("MARGEM_CORS precisa listar origens exatas (sem *).");
        }
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origens.stream().map(String::trim).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", AuthController.CABECALHO_CSRF));
        c.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        c.setAllowCredentials(true);
        c.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/api/**", c);
        return fonte;
    }
}
