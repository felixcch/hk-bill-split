package hk.billsplit;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class Security {
  static HttpSecurity shared(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .addFilterAfter(
            new WriteRateLimit(),
            org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                                    + "connect-src 'self' https://*.supabase.co; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/config", "/health")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
                    }));
  }

  @Bean
  @Profile("!demo")
  SecurityFilterChain production(HttpSecurity http, JwtDecoder decoder) throws Exception {
    return shared(http).oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder))).build();
  }

  @Bean
  @Profile("!demo")
  JwtDecoder decoder(
      @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.jwk-set-uri}") String jwks,
      @Value("${app.auth.audience}") String audience) {
    if (!issuer.startsWith("https://") || !jwks.startsWith("https://")) {
      throw new IllegalStateException(
          "Set SUPABASE_URL to the HTTPS project URL, or use the local demo profile.");
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks)
            .jwsAlgorithms(
                a ->
                    a.addAll(
                        List.of(
                            org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256,
                            org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)))
            .build();
    decoder.setJwtValidator(validator(issuer, audience));
    return decoder;
  }

  static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience)),
        new JwtClaimValidator<String>("role", "authenticated"::equals));
  }
}
