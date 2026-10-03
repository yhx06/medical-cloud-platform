package com.medcloud.course.paas.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.RegisterRequest;
import com.medcloud.course.contract.RegisterResponse;
import com.medcloud.course.contract.Role;
import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Stateless
public class AuthService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,64}");

    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;
    private String signingSecret;

    public AuthService() { }

    public AuthService(EntityManager manager, String signingSecret) {
        this.manager = manager;
        this.signingSecret = signingSecret;
    }

    public RegisterResponse register(RegisterRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null
                || !USERNAME.matcher(request.getUsername()).matches()) {
            throw new IllegalArgumentException("invalid registration");
        }
        int passwordBytes = request.getPassword().getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            throw new IllegalArgumentException("invalid registration");
        }
        long existing = manager.createQuery("select count(a) from Account a where lower(a.username) = :username", Long.class)
                .setParameter("username", request.getUsername().toLowerCase(java.util.Locale.ROOT))
                .getSingleResult();
        if (existing != 0) throw new IllegalStateException("username already exists");

        Account account = new Account(UUID.randomUUID().toString(), request.getUsername(),
                BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()), Role.PATIENT, null);
        try {
            manager.persist(account);
            manager.flush();
        } catch (PersistenceException failure) {
            if (isUniqueConflict(failure)) throw new IllegalStateException("username already exists", failure);
            throw failure;
        }
        return new RegisterResponse(account.getId(), account.getUsername());
    }

    private static boolean isUniqueConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                String state = ((SQLException) cause).getSQLState();
                if (state != null && state.startsWith("23")) return true;
            }
        }
        return false;
    }

    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new SecurityException("invalid credentials");
        }
        List<Account> accounts = manager.createQuery("select a from Account a where a.username = :username", Account.class)
                .setParameter("username", request.getUsername()).setMaxResults(1).getResultList();
        Account account = accounts.isEmpty() ? null : accounts.get(0);
        if (account != null) manager.refresh(account);
        if (account == null || !"ACTIVE".equals(account.getStatus())
                || account.getRole() == null
                || !BCrypt.checkpw(request.getPassword(), account.getPasswordHash())
                || !tenantReadable(account)) {
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
            if (account != null) manager.refresh(account);
            if (account == null || !"ACTIVE".equals(account.getStatus()) || account.getRole() == null || !tenantReadable(account)) {
                throw new SecurityException("inactive account");
            }
            return new CurrentActor(account.getId(), account.getRole(), account.getTenantId());
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("invalid token", e);
        }
    }

    private boolean tenantReadable(Account account) {
        if (account.getRole() != Role.DOCTOR && account.getRole() != Role.HOSPITAL_ADMIN) {
            return true;
        }
        if (account.getTenantId() == null) {
            return false;
        }
        List<String> statuses = manager.createQuery(
                "select t.status from Tenant t where t.id = :tenantId", String.class)
                .setParameter("tenantId", account.getTenantId()).getResultList();
        return statuses.size() == 1 && ("ACTIVE".equals(statuses.get(0)) || "DISABLED".equals(statuses.get(0)));
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
