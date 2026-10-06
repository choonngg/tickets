package com.project.ticket.global.auth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import io.jsonwebtoken.security.Jwks;
import org.springframework.util.Assert;

/** Shared PEM loading and public-only JWKS for independently configured JWT issuers. */
public record RsaJwtKeys(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    public static RsaJwtKeys fromPem(String privatePem, String publicPem) {
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY")));
            publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT keys must be PKCS#8 private / X.509 public RSA PEM", exception);
        }
        Assert.isTrue(publicKey.getModulus().bitLength() == 2048, "JWT RSA key must be 2048 bits");
        Assert.isTrue(privateKey.getModulus().equals(publicKey.getModulus()), "JWT RSA keys must match");
        return new RsaJwtKeys(privateKey, publicKey);
    }

    public Map<String, Object> jwks(String keyId) {
        Assert.hasText(keyId, "JWT key ID is required");
        return Map.of("keys", List.of(Jwks.builder().key(publicKey)
                .id(keyId).algorithm("RS256").add("use", "sig").build()));
    }

    private static byte[] decodePem(String pem, String label) {
        String normalized = pem.replace("\\n", "\n").trim();
        Assert.isTrue(normalized.startsWith("-----BEGIN " + label + "-----")
                && normalized.endsWith("-----END " + label + "-----"), "Invalid JWT PEM header");
        return Base64.getDecoder().decode(normalized
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "").replaceAll("\\s", ""));
    }
}
