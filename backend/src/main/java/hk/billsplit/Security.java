package hk.billsplit;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/** No accounts: the secret group link is the credential, so the API is open but rate limited. */
@Configuration
public class Security {
  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .addFilterAfter(new WriteRateLimit(), AnonymousAuthenticationFilter.class)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                                    + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }
}
