package com.project.ticket.support;

import com.project.ticket.global.auth.JwtProvider;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

public final class TestJwtKeys {
    public static final String SECRET = "test-secret-key-must-be-at-least-32-bytes";
    public static final String KEY_ID = "test-key-1";
    public static final String ISSUER = "https://issuer.test";
    public static final String AUDIENCE = "concert-service";
    public static final String PRIVATE_PEM;
    public static final String PUBLIC_PEM;
    public static final RSAPrivateKey PRIVATE_KEY;
    public static final RSAPublicKey PUBLIC_KEY;

    static {
        try {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application-test.yaml"));
            var properties = yaml.getObject();
            PRIVATE_PEM = properties.getProperty("jwt.private-key");
            PUBLIC_PEM = properties.getProperty("jwt.public-key");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PRIVATE_KEY = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(decode(PRIVATE_PEM)));
            PUBLIC_KEY = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(decode(PUBLIC_PEM)));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static byte[] decode(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----|\\s", ""));
    }

    public static JwtProvider provider(Clock clock) {
        return new JwtProvider(PRIVATE_PEM, PUBLIC_PEM, KEY_ID, ISSUER, AUDIENCE, clock);
    }

    public static JwtBuilder accessToken(Instant now) {
        return Jwts.builder().header().keyId(KEY_ID).type("JWT").and()
                .issuer(ISSUER).audience().add(AUDIENCE).and().subject("1")
                .claim("sid", "1").claim("typ", "AT").claim("role", "FAN")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(900)));
    }

    private TestJwtKeys() {}
}
