package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.RegisterRequest;
import com.medcloud.course.contract.RegisterResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.api.AuthResource;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationTest {
    private static final String SECRET = "course-test-signing-secret-at-least-32-characters";

    @Test
    void registerCreatesActivePatientWhoCanLoginWithoutManagementRoleOrPasswordEcho() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            AuthService service = new AuthService(manager, SECRET);
            AuthResource api = new AuthResource(service, AuditLocalTransactions.runner(manager));
            manager.getTransaction().begin();
            RegisterResponse registered = api.register(new RegisterRequest("new_patient", "Sëcret123")).getData();
            manager.getTransaction().commit();

            assertEquals(0L, manager.createQuery("select count(e) from AuditEvent e", Long.class).getSingleResult());

            Account account = manager.find(Account.class, registered.getId());
            assertEquals("new_patient", account.getUsername());
            assertEquals(Role.PATIENT, account.getRole());
            assertEquals("ACTIVE", account.getStatus());
            assertNull(account.getTenantId());
            assertTrue(BCrypt.checkpw("Sëcret123", account.getPasswordHash()));
            assertNotEquals("Sëcret123", account.getPasswordHash());
            manager.getTransaction().begin();
            assertEquals(Role.PATIENT, api.login(new LoginRequest("new_patient", "Sëcret123")).getData().getRole());
            manager.getTransaction().commit();
            String json = new ObjectMapper().writeValueAsString(registered);
            assertFalse(json.toLowerCase().contains("password"));
            assertFalse(json.contains("Sëcret123"));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void forgedRoleTenantAndStatusAreIgnored() throws Exception {
        RegisterRequest request = new ObjectMapper().readValue("{\"username\":\"safe_patient\","
                + "\"password\":\"Secret123\",\"role\":\"PLATFORM_ADMIN\","
                + "\"tenantId\":\"other-hospital\",\"status\":\"DISABLED\"}", RegisterRequest.class);
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            RegisterResponse result = new AuthService(manager, SECRET).register(request);
            manager.getTransaction().commit();
            Account saved = manager.find(Account.class, result.getId());
            assertEquals(Role.PATIENT, saved.getRole());
            assertEquals("ACTIVE", saved.getStatus());
            assertNull(saved.getTenantId());
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void invalidUsernamesAndPasswordByteLengthsAreRejected() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            AuthService service = new AuthService(manager, SECRET);
            for (String username : Arrays.asList(null, "", "ab", "a-b", "ééé", "a b", repeat('a', 65))) {
                assertThrows(IllegalArgumentException.class,
                        () -> service.register(new RegisterRequest(username, "Secret123")), String.valueOf(username));
            }
            for (String password : Arrays.asList(null, "", "1234567", repeat('é', 37), repeat('x', 73))) {
                assertThrows(IllegalArgumentException.class,
                        () -> service.register(new RegisterRequest("valid_name", password)), String.valueOf(password));
            }
            assertEquals(72, repeat('é', 36).getBytes(StandardCharsets.UTF_8).length);
            manager.getTransaction().begin();
            RegisterResponse boundary = service.register(new RegisterRequest(repeat('a', 64), repeat('é', 36)));
            manager.getTransaction().commit();
            assertEquals(repeat('a', 64), manager.find(Account.class, boundary.getId()).getUsername());
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void usernameUniquenessIsCaseInsensitiveEvenWhenDatabaseIsNot() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            AuthService service = new AuthService(manager, SECRET);
            manager.getTransaction().begin();
            service.register(new RegisterRequest("Alice", "Secret123"));
            manager.getTransaction().commit();
            assertEquals(0L, manager.createQuery("select count(a) from Account a where a.username = :username", Long.class)
                    .setParameter("username", "alice").getSingleResult().longValue(),
                    "this fixture must use a case-sensitive lookup to prove the service handles MySQL's collation");
            manager.getTransaction().begin();
            RuntimeException conflict = assertThrows(IllegalStateException.class,
                    () -> service.register(new RegisterRequest("alice", "Secret123")));
            assertEquals(409, new ApiFailureMapper().toResponse(conflict).getStatus());
            manager.getTransaction().rollback();
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void concurrentDuplicateUsernameHasOneWinnerAndControlledConflict() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> attempt = () -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    start.await();
                    manager.getTransaction().begin();
                    try {
                        new AuthService(manager, SECRET).register(new RegisterRequest("race_patient", "Secret123"));
                        manager.getTransaction().commit();
                        return 201;
                    } catch (RuntimeException failure) {
                        if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                        return new ApiFailureMapper().toResponse(failure).getStatus();
                    }
                } finally {
                    manager.close();
                }
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();
            List<Integer> outcomes = Arrays.asList(first.get(), second.get());
            assertTrue(outcomes.contains(201));
            assertTrue(outcomes.contains(409), outcomes.toString());
        } finally {
            pool.shutdownNow();
            factory.close();
        }
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
