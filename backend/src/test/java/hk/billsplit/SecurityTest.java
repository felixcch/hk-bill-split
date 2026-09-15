package hk.billsplit;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class SecurityTest {
  private static final String ISSUER = "https://project.supabase.co/auth/v1";
  private static RSAKey signingKey;
  private static NimbusJwtDecoder decoder;

  @BeforeAll
  static void keys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).generate();
    decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
    decoder.setJwtValidator(Security.validator(ISSUER, "authenticated"));
  }

  private String token(RSAKey key, String issuer, String audience, String role, Instant expires)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .issuer(issuer)
            .audience(audience)
            .claim("role", role)
            .issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(expires))
            .build();
    SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }

  @Test
  void acceptsVerifiedAuthenticatedUser() throws Exception {
    assertThat(
            decoder
                .decode(
                    token(
                        signingKey,
                        ISSUER,
                        "authenticated",
                        "authenticated",
                        Instant.now().plusSeconds(300)))
                .getSubject())
        .isNotBlank();
  }

  @Test
  void rejectsWrongIssuerAudienceRoleAndExpiredTokens() {
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        signingKey,
                        "https://wrong.test",
                        "authenticated",
                        "authenticated",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtValidationException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        signingKey,
                        ISSUER,
                        "other",
                        "authenticated",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtValidationException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        signingKey,
                        ISSUER,
                        "authenticated",
                        "anon",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtValidationException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        signingKey,
                        ISSUER,
                        "authenticated",
                        "authenticated",
                        Instant.now().minusSeconds(120))))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void rejectsDifferentSigningKey() throws Exception {
    RSAKey other = new RSAKeyGenerator(2048).generate();
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        other,
                        ISSUER,
                        "authenticated",
                        "authenticated",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(BadJwtException.class);
  }
}
