package com.medcloud.course.paas.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

@Stateless
public class AuthService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOKEN_LIFETIME_SECONDS = 3600;

    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;
    private String signingSecret;

    public AuthService() { }

    public AuthService(EntityManager manager, String signingSecret) {
        this.manager = manager;
        this.signingSecret = signingSecret;
    }

    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new SecurityException("invalid credentials");
        }
        List<Account> accounts = manager.createQuery("select a from Account a where a.username = :username", Account.class)
                .setParameter("username", request.getUsername()).setMaxResults(1).getResultList();
        Account account = accounts.isEmpty() ? null : accounts.get(0);
        if (account == null || !"ACTIVE".equals(account.getStatus())
                || !BCrypt.checkpw(request.getPassword(), account.getPasswordHash())) {
            throw new SecurityException("invalid credentials");
        }
        return new LoginResponse(sign(account.getId()), account.getUsername(), account.getRole());
    }

    public CurrentActor authenticate(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !MessageDigest.isEqual(signature(parts[0] + "." + parts[1]),
                    Base64.getUrlDecoder().decode(parts[2]))) {
                throw new SecurityException("invalid token");
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())
                    || payload.path("exp").asLong(0) <= System.currentTimeMillis() / 1000) {
                throw new SecurityException("expired or unsupported token");
            }
            Account account = manager.find(Account.class, payload.path("sub").asText());
            if (account == null || !"ACTIVE".equals(account.getStatus())) {
                throw new SecurityException("inactive account");
            }
            return new CurrentActor(account.getId(), account.getRole(), account.getTenantId());
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("invalid token", e);
        }
    }

    private String sign(String userId) {
        try {
            String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = encode(JSON.writeValueAsString(new TokenPayload(userId,
                    System.currentTimeMillis() / 1000 + TOKEN_LIFETIME_SECONDS)));
            String body = header + "." + payload;
            return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(body));
        } catch (Exception e) {
            throw new IllegalStateException("token creation failed", e);
        }
    }

    private byte[] signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }

    private String secret() {
        String result = signingSecret == null ? System.getenv("MEDICAL_COURSE_JWT_SECRET") : signingSecret;
        if (result == null || result.length() < 32) {
            throw new IllegalStateException("MEDICAL_COURSE_JWT_SECRET must be at least 32 characters");
        }
        return result;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TokenPayload {
        public final String sub;
        public final long exp;

        private TokenPayload(String sub, long exp) {
            this.sub = sub;
            this.exp = exp;
        }
    }
}
