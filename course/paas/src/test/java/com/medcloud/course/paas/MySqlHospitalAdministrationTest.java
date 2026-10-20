package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real schema-005 tests. The coordinator provisions the schema and grants; this class never issues DDL. */
class MySqlHospitalAdministrationTest extends HospitalAdministrationFixture {
    private static final String PREFIX = "COURSE_ONBOARDING_MYSQL_";
    // Child before parent, without FOREIGN_KEY_CHECKS or truncation. Unknown tables prohibit cleanup.
    private static final List<String> TABLES = Collections.unmodifiableList(Arrays.asList(
            "audit_event", "hospital_admin_operation", "file_metadata", "record_correction", "clinical_draft",
            "clinical_record", "payment_order", "appointment", "schedule_slot", "doctor_profile", "department", "user_account", "tenant"));
    private static final List<String> CASES = Collections.unmodifiableList(Arrays.asList(
            "schemaJpaValidate",
            "sameKeyBothDirections:open-first", "sameKeyBothDirections:add-first",
            "caseInsensitiveGlobalUsernameRace:PATIENT:onboarding-first", "caseInsensitiveGlobalUsernameRace:PATIENT:other-first",
            "caseInsensitiveGlobalUsernameRace:DOCTOR:onboarding-first", "caseInsensitiveGlobalUsernameRace:DOCTOR:other-first",
            "disableLastTwoAdminsBothDirections:original-first", "disableLastTwoAdminsBothDirections:extra-first",
            "disableVsEachHospitalWriterBothDirections:createDepartment:disable-first", "disableVsEachHospitalWriterBothDirections:createDepartment:writer-first",
            "disableVsEachHospitalWriterBothDirections:updateDepartment:disable-first", "disableVsEachHospitalWriterBothDirections:updateDepartment:writer-first",
            "disableVsEachHospitalWriterBothDirections:createDoctor:disable-first", "disableVsEachHospitalWriterBothDirections:createDoctor:writer-first",
            "disableVsEachHospitalWriterBothDirections:updateDoctor:disable-first", "disableVsEachHospitalWriterBothDirections:updateDoctor:writer-first",
            "disableVsEachHospitalWriterBothDirections:createSlot:disable-first", "disableVsEachHospitalWriterBothDirections:createSlot:writer-first",
            "disableVsEachHospitalWriterBothDirections:updateSlot:disable-first", "disableVsEachHospitalWriterBothDirections:updateSlot:writer-first",
            "freezeVsAdminMaintenance:add:freeze-first", "freezeVsAdminMaintenance:add:maintenance-first",
            "freezeVsAdminMaintenance:enable:freeze-first", "freezeVsAdminMaintenance:enable:maintenance-first",
            "freezeVsAdminMaintenance:disable:freeze-first", "freezeVsAdminMaintenance:disable:maintenance-first",
            "auditFailureRollsBackBusiness:open", "auditFailureRollsBackBusiness:add", "auditFailureRollsBackBusiness:status",
            "replayFrozenAndDisabled", "zeroActiveRecovery"));

    static Stream<String> requiredCases() { return CASES.stream(); }
    @BeforeAll static void freezeManifest() {
        assertEquals(32, CASES.size());
        assertEquals(32, new HashSet<String>(CASES).size());
        for (String label : CASES) {
            System.out.println("ONBOARDING_REQUIRED_CASE=" + label);
            if (isRace(label)) System.out.println("ONBOARDING_REQUIRED_LOCK=" + label + ":innodb-wait");
        }
    }
    private static boolean isRace(String label) {
        return label.startsWith("sameKey") || label.startsWith("caseInsensitive") || label.startsWith("disable") || label.startsWith("freeze");
    }
    private static String env(String name) { return System.getenv(PREFIX + name); }

    @Override protected Map<String,Object> databaseProperties() {
        String required = env("REQUIRED");
        assertTrue(required == null || "true".equals(required) || "false".equals(required), "REQUIRED must be true or false");
        List<String> missing = new ArrayList<String>();
        for (String key : Arrays.asList("URL", "USER", "PASSWORD", "UUID", "OBSERVER_URL", "OBSERVER_USER", "OBSERVER_PASSWORD"))
            if (env(key) == null || env(key).isEmpty()) missing.add(key);
        boolean anyConfigured = false;
        for (String key : Arrays.asList("URL", "USER", "PASSWORD", "UUID", "OBSERVER_URL", "OBSERVER_USER", "OBSERVER_PASSWORD"))
            anyConfigured |= env(key) != null;
        if (!missing.isEmpty()) {
            if ("true".equals(required) || anyConfigured) fail("Required onboarding MySQL fixture missing: " + String.join(",", missing));
            assumeTrue(false, "Optional onboarding MySQL fixture is unconfigured; this is not acceptance GREEN");
        }
        assertTrue(env("URL").matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/course_onboarding_test_[a-f0-9]{32}\\?[^#]*$"), "Only a new dedicated onboarding test schema is allowed");
        assertTrue(env("UUID").matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"), "A fixed database UUID is required");
        assertNotEquals(env("USER"), env("OBSERVER_USER"), "Independent observer account required");
        String observerUrl = env("OBSERVER_URL");
        assertTrue(observerUrl.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/course_onboarding_test_[a-f0-9]{32}\\?[^#]*$"), "Observer must target a dedicated onboarding schema");
        assertEquals(env("URL").substring(0, env("URL").indexOf('?')), observerUrl.substring(0, observerUrl.indexOf('?')), "Observer must use the exact same owned schema endpoint");
        try (Connection writer = connection(false); Connection observer = connection(true)) {
            assertUuid(writer); assertUuid(observer);
            Set<String> actual = new HashSet<String>();
            try (ResultSet rows = writer.getMetaData().getTables(writer.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rows.next()) actual.add(rows.getString("TABLE_NAME"));
            }
            assertEquals(new HashSet<String>(TABLES), actual, "Refuse cleanup unless exact schema-005 table set is present");
            writer.setAutoCommit(false);
            try (Statement sql = writer.createStatement()) { for (String table : TABLES) sql.executeUpdate("DELETE FROM " + table); }
            writer.commit();
            // Prove observer has real lock visibility before starting any competing transaction.
            String version;
            try (Statement sql = writer.createStatement(); ResultSet rows = sql.executeQuery("SELECT VERSION()")) { assertTrue(rows.next()); version = rows.getString(1); }
            String lockProbe = version.startsWith("8.")
                    ? "SELECT COUNT(*) FROM performance_schema.data_lock_waits"
                    : "SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS";
            try (Statement sql = observer.createStatement(); ResultSet rows = sql.executeQuery(lockProbe)) { assertTrue(rows.next()); }
        } catch (SQLException failure) { throw new AssertionError("Owned onboarding MySQL preflight failed (SQL state " + failure.getSQLState() + ")"); }
        Map<String,Object> settings = new HashMap<String,Object>();
        settings.put("javax.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
        settings.put("javax.persistence.jdbc.url", env("URL"));
        settings.put("javax.persistence.jdbc.user", env("USER"));
        settings.put("javax.persistence.jdbc.password", env("PASSWORD"));
        settings.put("hibernate.dialect", "org.hibernate.dialect.MySQL5InnoDBDialect");
        settings.put("hibernate.connection.isolation", "4");
        settings.put("hibernate.hbm2ddl.auto", "validate");
        settings.put("hibernate.show_sql", "false");
        return settings;
    }
    private static Connection connection(boolean observer) throws SQLException {
        return DriverManager.getConnection(env(observer ? "OBSERVER_URL" : "URL"), env(observer ? "OBSERVER_USER" : "USER"), env(observer ? "OBSERVER_PASSWORD" : "PASSWORD"));
    }
    private static void assertUuid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT @@server_uuid")) {
            assertTrue(rows.next()); assertEquals(env("UUID"), rows.getString(1), "Database identity changed");
        }
    }

    @ParameterizedTest(name="{0}") @MethodSource("requiredCases")
    void requiredMySqlCase(String label) throws Exception {
        String[] parts = label.split(":");
        switch (parts[0]) {
            case "schemaJpaValidate": schema(); break;
            case "sameKeyBothDirections": sameKey(label, parts[1].equals("open-first")); break;
            case "caseInsensitiveGlobalUsernameRace": username(label, parts[1], parts[2].equals("onboarding-first")); break;
            case "disableLastTwoAdminsBothDirections": lastTwo(label, parts[1].equals("original-first")); break;
            case "disableVsEachHospitalWriterBothDirections": disableWriter(label, parts[1], parts[2].equals("disable-first")); break;
            case "freezeVsAdminMaintenance": freezeMaintenance(label, parts[1], parts[2].equals("freeze-first")); break;
            case "auditFailureRollsBackBusiness": rollback(parts[1]); break;
            case "replayFrozenAndDisabled": replay(); break;
            case "zeroActiveRecovery": recover(); break;
            default: fail("Unknown mandatory case");
        }
        System.out.println("ONBOARDING_PASSED_CASE=" + label);
    }
    private void schema() {
        assertEquals("validate", factory.getProperties().get("hibernate.hbm2ddl.auto"));
        String version = serverVersion();
        assertTrue(version.startsWith("8.") || version.startsWith("5.6."), "Unexpected MySQL version: " + version);
        System.out.println("ONBOARDING_MYSQL_VERSION=" + version);
        assertEquals(2L, count(Tenant.class)); assertEquals(8L, count(Account.class));
        assertEquals(0L, count(HospitalAdminOperation.class)); assertEquals(0L, count(AuditEvent.class));
    }
    private HospitalAdminCreationView create(EntityManager em, boolean opening, String key, String username, CurrentActor actor) {
        return opening ? new PlatformAdministrationService(em).openHospital(actor, opening("Fictional hospital", username, PASSWORD, key), audit(AuditEventType.TENANT_OPEN))
                : new PlatformAdministrationService(em).addAdministrator(actor, "tenant-a", new HospitalAdministratorCreateRequest(username, PASSWORD, key), audit(AuditEventType.HOSPITAL_ADMIN_CREATE));
    }
    private void sameKey(String label, boolean openFirst) throws Exception {
        String key = UUID.randomUUID().toString();
        Race race = race(label, em -> create(em, openFirst, key, "shared_admin", PLATFORM),
                em -> create(em, !openFirst, key, "shared_admin", PLATFORM));
        assertNull(race.first.failure); assertConflict(race.second, "OPERATION_KEY_CONFLICT");
        long committedOperationRowsForTheSameActorAndKey = inTx(em -> em.createQuery("select count(o) from HospitalAdminOperation o where o.actorId=:a and o.operationKey=:k",Long.class)
                .setParameter("a", PLATFORM.getUserId()).setParameter("k", key).getSingleResult());
        assertEquals(1L, committedOperationRowsForTheSameActorAndKey);
        assertEquals(openFirst ? 3L : 2L, count(Tenant.class)); assertEquals(9L, count(Account.class)); assertEquals(1L, count(AuditEvent.class));
        HospitalAdminCreationView winner = (HospitalAdminCreationView) race.first.value;
        String recoveredTenantId = inTx(em -> create(em, openFirst, key, "SHARED_ADMIN", PLATFORM)).getTenantId();
        String firstTenantId = winner.getTenantId(); assertEquals(firstTenantId, recoveredTenantId);
        assertEquals(1L, count(HospitalAdminOperation.class)); assertEquals(2L, count(AuditEvent.class));
    }
    private void username(String label, String role, boolean onboardingFirst) throws Exception {
        String key = UUID.randomUUID().toString();
        Function<EntityManager,Object> onboarding = em -> create(em, true, key, "RACE_USER", PLATFORM);
        Function<EntityManager,Object> other = em -> role.equals("PATIENT")
                ? new AuthService(em, "fictional-signing-secret-32-characters").register(new RegisterRequest("race_user", PASSWORD))
                : new HospitalDoctorService(em).create(ADMIN, "race_user", "Fictional doctor", "department-a");
        Race race = race(label, onboardingFirst ? onboarding : other, onboardingFirst ? other : onboarding);
        assertNull(race.first.failure); assertNotNull(race.second.failure);
        if (onboardingFirst) assertTrue(race.second.failure instanceof IllegalStateException, "Other creator must reject duplicate username");
        else assertConflict(race.second, "USERNAME_EXISTS");
        inTx(em -> {
            List<Account> accounts = em.createQuery("select a from Account a where lower(a.username)='race_user'", Account.class).getResultList();
            assertEquals(1, accounts.size()); assertEquals(onboardingFirst ? Role.HOSPITAL_ADMIN : Role.valueOf(role), accounts.get(0).getRole());
            assertEquals("ACTIVE", accounts.get(0).getStatus()); return null;
        });
        assertEquals(onboardingFirst ? 3L : 2L, count(Tenant.class)); assertEquals(9L, count(Account.class));
        assertEquals(onboardingFirst ? 1L : 0L, count(HospitalAdminOperation.class));
        assertEquals(onboardingFirst || role.equals("DOCTOR") ? 1L : 0L, count(AuditEvent.class));
    }
    private Object change(EntityManager em, CurrentActor actor, String id, String desired, String expected) {
        return new PlatformAdministrationService(em).changeAdministratorStatus(actor,"tenant-a",id,new TenantStatusRequest(desired,expected),audit(AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE));
    }
    private void lastTwo(String label, boolean originalFirst) throws Exception {
        String extra = add("tenant-a", "extra_admin", UUID.randomUUID().toString()).getAdministratorId();
        String first = originalFirst ? "admin-a" : extra, second = originalFirst ? extra : "admin-a";
        Race race = race(label, em -> change(em, PLATFORM, first, "DISABLED", "ACTIVE"),
                em -> change(em, PLATFORM_OTHER, second, "DISABLED", "ACTIVE"));
        assertNull(race.first.failure); assertConflict(race.second, "LAST_ACTIVE_HOSPITAL_ADMIN");
        long activeAdministratorsAfterBothTransactions = active(); assertEquals(1L, activeAdministratorsAfterBothTransactions);
        assertEquals("DISABLED", statusOf(first)); assertEquals("ACTIVE", statusOf(second));
        assertEquals("ACTIVE", tenantStatus()); assertEquals(1L, count(HospitalAdminOperation.class)); assertEquals(2L, count(AuditEvent.class));
    }
    private void disableWriter(String label, String operation, boolean disableFirst) throws Exception {
        add("tenant-a", "extra_admin", UUID.randomUUID().toString());
        List<String> before = snapshot();
        Map<String,String> administratorBefore = accountRow("admin-a");
        List<String> otherAccountsBefore = accountRowsExcept("admin-a");
        Function<EntityManager,Object> disable = em -> change(em, PLATFORM, "admin-a", "DISABLED", "ACTIVE");
        Function<EntityManager,Object> writer = em -> write(em, operation);
        Race race = race(label, disableFirst ? disable : writer, disableFirst ? writer : disable);
        assertNull(race.first.failure);
        if (disableFirst) assertTrue(race.second.failure instanceof SecurityException, "Committed disabled writer must be rejected after real lock wait");
        else assertNull(race.second.failure);
        assertEquals("DISABLED", statusOf("admin-a")); assertEquals(1L, active()); assertEquals("ACTIVE", tenantStatus());
        assertEquals(1L, count(HospitalAdminOperation.class)); assertEquals(disableFirst ? 2L : 3L, count(AuditEvent.class));
        if (disableFirst) {
            // Only the target account's status may change; every other account column stays byte-identical.
            Map<String,String> administratorAfter = accountRow("admin-a");
            assertEquals("DISABLED", administratorAfter.get("status"));
            for (String column : administratorBefore.keySet()) {
                if (!column.equals("status")) assertEquals(administratorBefore.get(column), administratorAfter.get(column), "Unexpected administrator column change: " + column);
            }
            assertEquals(otherAccountsBefore, accountRowsExcept("admin-a"), "Another account row changed during disable-first");
            assertEquals(withoutAccountRows(before), withoutAccountRows(snapshot()), "A non-account table changed during disable-first");
        } else {
            assertWriterPersisted(operation);
            assertNotEquals(before, snapshot(), "Successful writer must persist its mutation");
        }
    }
    private static List<String> withoutAccountRows(List<String> values) {
        List<String> copy = new ArrayList<String>(); for (String row : values) if (!row.startsWith("user_account:")) copy.add(row); return copy;
    }
    private String serverVersion() {
        return inTx(em -> String.valueOf(em.createNativeQuery("SELECT VERSION()").getSingleResult()));
    }
    private Map<String,String> accountRow(String id) {
        return inTx(em -> {
            Object[] row = (Object[]) em.createNativeQuery("SELECT username,password_hash,role,status,tenant_id FROM user_account WHERE id=?1").setParameter(1, id).getSingleResult();
            String[] names = {"username", "password_hash", "role", "status", "tenant_id"};
            Map<String,String> values = new LinkedHashMap<String,String>();
            for (int i = 0; i < names.length; i++) values.put(names[i], row[i] == null ? null : String.valueOf(row[i]));
            return values;
        });
    }
    private List<String> accountRowsExcept(String id) {
        return inTx(em -> {
            List<?> rows = em.createNativeQuery("SELECT id,username,password_hash,role,status,tenant_id FROM user_account WHERE id<>?1 ORDER BY id").setParameter(1, id).getResultList();
            List<String> values = new ArrayList<String>();
            for (Object row : rows) {
                StringBuilder line = new StringBuilder();
                for (Object column : (Object[]) row) line.append(column == null ? "NULL" : String.valueOf(column)).append('|');
                values.add(line.toString());
            }
            return values;
        });
    }
    private void assertWriterPersisted(String operation) {
        inTx(em -> {
            switch (operation) {
                case "createDepartment": assertEquals(1L, number(em, "SELECT COUNT(*) FROM department WHERE tenant_id='tenant-a' AND name='New department' AND status='ACTIVE'")); break;
                case "updateDepartment": assertEquals(1L, number(em, "SELECT COUNT(*) FROM department WHERE id='department-a' AND name='Updated department' AND status='ACTIVE'")); break;
                case "createDoctor": assertEquals(1L, number(em, "SELECT COUNT(*) FROM user_account a JOIN doctor_profile d ON d.user_id=a.id WHERE a.username='new_doctor' AND d.tenant_id='tenant-a' AND d.display_name='New doctor'")); break;
                case "updateDoctor": assertEquals(1L, number(em, "SELECT COUNT(*) FROM doctor_profile WHERE user_id='doctor-a' AND display_name='Updated doctor' AND department_id='department-a'")); break;
                case "createSlot": assertEquals(1L, number(em, "SELECT COUNT(*) FROM schedule_slot WHERE tenant_id='tenant-a' AND department_id='department-a' AND doctor_id='doctor-a' AND schedule_date='2099-05-21' AND start_time='11:00:00' AND capacity=21 AND fee=40.00")); break;
                case "updateSlot": assertEquals(1L, number(em, "SELECT COUNT(*) FROM schedule_slot WHERE id='slot-a' AND schedule_date='2099-05-21' AND start_time='11:00:00' AND capacity=21 AND fee=40.00")); break;
                default: throw new AssertionError(operation);
            }
            return null;
        });
    }
    private static long number(EntityManager em, String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
    private Object write(EntityManager em, String operation) {
        SlotRequest slot = new SlotRequest("department-a", "doctor-a", java.sql.Date.valueOf("2099-05-21"), Time.valueOf("11:00:00"), Time.valueOf("12:00:00"), 21, new BigDecimal("40.00"), "PUBLISHED");
        switch (operation) {
            case "createDepartment": return new HospitalDepartmentService(em).create(ADMIN, "New department");
            case "updateDepartment": return new HospitalDepartmentService(em).update(ADMIN, "department-a", "Updated department", "ACTIVE");
            case "createDoctor": return new HospitalDoctorService(em).create(ADMIN, "new_doctor", "New doctor", "department-a");
            case "updateDoctor": return new HospitalDoctorService(em).update(ADMIN, "doctor-a", "Updated doctor", "department-a", "ACTIVE");
            case "createSlot": return new SlotAdminService(em).create(ADMIN, slot);
            case "updateSlot": return new SlotAdminService(em).update(ADMIN, "slot-a", slot);
            default: throw new AssertionError(operation);
        }
    }
    private void freezeMaintenance(String label, String operation, boolean freezeFirst) throws Exception {
        String extra = add("tenant-a", "extra_admin", UUID.randomUUID().toString()).getAdministratorId();
        if (operation.equals("enable")) status("tenant-a", extra, "DISABLED", "ACTIVE");
        long audits = count(AuditEvent.class), operations = count(HospitalAdminOperation.class), accounts = count(Account.class);
        Function<EntityManager,Object> freeze = em -> new PlatformTenantService(em).changeStatus(PLATFORM_OTHER,"tenant-a",new TenantStatusRequest("DISABLED","ACTIVE"));
        Function<EntityManager,Object> maintenance = em -> operation.equals("add") ? create(em,false,UUID.randomUUID().toString(),"frozen_admin",PLATFORM)
                : change(em,PLATFORM,extra,operation.equals("enable") ? "ACTIVE" : "DISABLED",operation.equals("enable") ? "DISABLED" : "ACTIVE");
        Race race = race(label, freezeFirst ? freeze : maintenance, freezeFirst ? maintenance : freeze);
        assertNull(race.first.failure); assertNull(race.second.failure); assertEquals("DISABLED", tenantStatus());
        assertEquals(operation.equals("disable") ? 1L : operation.equals("add") ? 3L : 2L, active());
        assertEquals(operations + (operation.equals("add") ? 1 : 0), count(HospitalAdminOperation.class));
        assertEquals(accounts + (operation.equals("add") ? 1 : 0), count(Account.class)); assertEquals(audits + 2, count(AuditEvent.class));
    }
    private void rollback(String operation) {
        if (operation.equals("status")) add("tenant-a", "extra_admin", UUID.randomUUID().toString());
        List<String> before = snapshot(); long audits = count(AuditEvent.class);
        assertThrows(IllegalStateException.class, () -> inTx(em -> {
            PlatformAdministrationService service = new PlatformAdministrationService(em, (context,id,result,at) -> { throw new IllegalStateException("fictional audit fault"); });
            switch (operation) {
                case "open": return service.openHospital(PLATFORM, opening(), audit(AuditEventType.TENANT_OPEN));
                case "add": return service.addAdministrator(PLATFORM,"tenant-a",new HospitalAdministratorCreateRequest("fault_admin",PASSWORD,UUID.randomUUID().toString()),audit(AuditEventType.HOSPITAL_ADMIN_CREATE));
                case "status": return service.changeAdministratorStatus(PLATFORM,"tenant-a","admin-a",new TenantStatusRequest("DISABLED","ACTIVE"),audit(AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE));
                default: throw new AssertionError(operation);
            }
        }));
        assertEquals(before, snapshot(), "All 12 non-audit tables retain exact NULL/byte values after rollback"); assertEquals(audits,count(AuditEvent.class));
        assertEquals("ACTIVE",statusOf("admin-a")); assertEquals("ACTIVE",tenantStatus());
        // A fresh transaction can acquire both control locks after rollback.
        inTx(em -> { em.find(Tenant.class,"tenant-a",LockModeType.PESSIMISTIC_WRITE); em.find(Account.class,"admin-a",LockModeType.PESSIMISTIC_WRITE); return null; });
    }
    private void replay() {
        HospitalOpenRequest request = opening(); HospitalAdminCreationView first = open(request);
        inTx(em -> { em.find(Tenant.class,first.getTenantId()).setStatus("DISABLED"); em.find(Account.class,first.getAdministratorId()).disable(); return null; });
        HospitalAdminCreationView retry = open(request);
        assertEquals(first.getTenantId(), retry.getTenantId()); assertEquals(first.getAdministratorId(),retry.getAdministratorId());
        assertEquals("DISABLED",statusOf(first.getAdministratorId())); assertEquals("DISABLED",inTx(em -> em.find(Tenant.class,first.getTenantId()).getStatus()));
        assertEquals(3L,count(Tenant.class)); assertEquals(9L,count(Account.class)); assertEquals(1L,count(HospitalAdminOperation.class)); assertEquals(2L,count(AuditEvent.class));
    }
    private void recover() {
        inTx(em -> { em.find(Tenant.class,"tenant-a").setStatus("DISABLED"); em.find(Account.class,"admin-a").disable(); return null; });
        assertEquals(0L,active()); HospitalAdminCreationView added = add("tenant-a","recovery_admin",UUID.randomUUID().toString());
        assertEquals("tenant-a",added.getTenantId()); assertEquals(1L,active()); assertEquals("DISABLED",tenantStatus());
        assertEquals("ACTIVE",statusOf(added.getAdministratorId())); assertEquals("DISABLED",statusOf("admin-a"));
        assertEquals(2L,count(Tenant.class)); assertEquals(9L,count(Account.class)); assertEquals(1L,count(HospitalAdminOperation.class)); assertEquals(1L,count(AuditEvent.class));
    }
    private long active() { return inTx(em -> em.createQuery("select count(a) from Account a where a.tenantId='tenant-a' and a.role=:role and a.status='ACTIVE'",Long.class).setParameter("role",Role.HOSPITAL_ADMIN).getSingleResult()); }
    private String statusOf(String id) { return inTx(em -> em.find(Account.class,id).getStatus()); }
    private String tenantStatus() { return inTx(em -> em.find(Tenant.class,"tenant-a").getStatus()); }
    private List<String> snapshot() {
        return inTx(em -> {
            List<String> values = new ArrayList<String>();
            em.unwrap(org.hibernate.Session.class).doWork(connection -> {
                for (String table : TABLES) if (!table.equals("audit_event")) {
                    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                        int columns = rows.getMetaData().getColumnCount();
                        while (rows.next()) {
                            StringBuilder row = new StringBuilder(table).append(':');
                            for (int i=1;i<=columns;i++) { byte[] bytes=rows.getBytes(i); row.append(bytes==null ? "NULL" : Base64.getEncoder().encodeToString(bytes)).append('|'); }
                            values.add(row.toString());
                        }
                    }
                }
            });
            Collections.sort(values); return values;
        });
    }
    private static void assertConflict(Outcome outcome, String code) {
        assertTrue(outcome.failure instanceof HospitalAdministrationException, "Expected safe onboarding conflict");
        assertEquals(code, ((HospitalAdministrationException)outcome.failure).getCode());
        assertEquals(409, ((HospitalAdministrationException)outcome.failure).getStatus());
    }
    private static final class Outcome { Object value; Throwable failure; }
    private static final class Race { Outcome first, second; }
    private Outcome attempt(EntityManager em, Function<EntityManager,Object> action) {
        Outcome result = new Outcome();
        try { result.value = action.apply(em); em.flush(); }
        catch (RuntimeException failure) { result.failure = failure; }
        return result;
    }
    private long connectionId(EntityManager em) { return ((Number)em.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue(); }
    private Race race(String label, Function<EntityManager,Object> firstAction, Function<EntityManager,Object> secondAction) throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        EntityManager first = factory.createEntityManager();
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicLong secondId = new AtomicLong(-1);
        Future<Outcome> future = null;
        try {
            first.getTransaction().begin();
            first.createNativeQuery("SET SESSION innodb_lock_wait_timeout=20").executeUpdate();
            long firstId = connectionId(first);
            Race result = new Race(); result.first = attempt(first,firstAction); assertNull(result.first.failure,"First transaction must perform real mutation before competing writer starts");
            future = workers.submit(() -> {
                EntityManager second = factory.createEntityManager();
                try {
                    second.getTransaction().begin(); second.createNativeQuery("SET SESSION innodb_lock_wait_timeout=20").executeUpdate();
                    secondId.set(connectionId(second)); secondStarted.countDown();
                    Outcome outcome = attempt(second,secondAction);
                    if (outcome.failure == null) second.getTransaction().commit(); else second.getTransaction().rollback();
                    return outcome;
                } finally { if(second.getTransaction().isActive())second.getTransaction().rollback(); second.close(); }
            });
            assertTrue(secondStarted.await(10,TimeUnit.SECONDS)); assertNotEquals(firstId,secondId.get(),"Separate physical transaction connections required");
            try { observeWait(firstId,secondId.get(),label); }
            catch (AssertionError noWait) {
                if (future.isDone()) {
                    Outcome early = future.get(1, TimeUnit.SECONDS);
                    throw new AssertionError(noWait.getMessage() + "; second outcome=" + (early.failure == null ? "COMMITTED" : early.failure.getClass().getSimpleName() + ":" + early.failure.getMessage()), noWait);
                }
                throw noWait;
            }
            first.getTransaction().commit(); result.second = future.get(15,TimeUnit.SECONDS);
            return result;
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback(); first.close();
            if (future != null && !future.isDone()) { try { future.get(25,TimeUnit.SECONDS); } catch (Exception ignored) { future.cancel(true); } }
            workers.shutdownNow(); assertTrue(workers.awaitTermination(25,TimeUnit.SECONDS),"All transaction workers terminate before fixture cleanup");
        }
    }
    private void observeWait(long blocker, long waiter, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // MySQL 8 exposes data_lock_waits; MySQL 5.6/5.7 uses INNODB_LOCK_WAITS. Both join exact physical connection IDs.
        String query = serverVersion().startsWith("8.")
                ? "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID "
                    + "JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID "
                    + "WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=?"
                : "SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS w "
                    + "JOIN information_schema.INNODB_TRX r ON r.trx_id=w.requesting_trx_id "
                    + "JOIN information_schema.INNODB_TRX b ON b.trx_id=w.blocking_trx_id "
                    + "WHERE r.trx_mysql_thread_id=? AND b.trx_mysql_thread_id=? AND r.trx_state='LOCK WAIT'";
        try (Connection observer = connection(true); PreparedStatement statement = observer.prepareStatement(query)) {
            assertUuid(observer); statement.setLong(1,waiter); statement.setLong(2,blocker);
            do {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getLong(1)>0) { System.out.println("ONBOARDING_OBSERVED_LOCK=" + label + ":innodb-wait"); return; }
                }
                Thread.sleep(25); // Poll interval only; success requires a matching real InnoDB waiting edge.
            } while(System.nanoTime()<deadline);
            // MySQL 8 reports duplicate-key waits as a self-referential data_lock_waits edge
            // (requesting == blocking == waiter). Keep the exact connection ids: the waiter must be in
            // LOCK WAIT, the other test transaction must still be RUNNING, and a real wait must exist.
            String duplicateEdge = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID "
                    + "WHERE r.PROCESSLIST_ID=?";
            try (PreparedStatement edge = observer.prepareStatement(duplicateEdge)) {
                edge.setLong(1, waiter);
                try (ResultSet rows = edge.executeQuery()) {
                    if (rows.next() && rows.getLong(1) > 0
                            && trxState(observer, waiter, "LOCK WAIT") == 1
                            && trxState(observer, blocker, "RUNNING") == 1) {
                        System.out.println("ONBOARDING_OBSERVED_LOCK=" + label + ":innodb-wait-duplicate-key");
                        return;
                    }
                }
            }
            String diagnostic;
            try (Statement probe = observer.createStatement()) {
                long totalWaits = single(probe, "SELECT COUNT(*) FROM performance_schema.data_lock_waits");
                long waitingLocks = single(probe, "SELECT COUNT(*) FROM performance_schema.data_locks WHERE LOCK_STATUS='WAITING'");
                long waiterTrx = single(probe, "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=" + waiter + " AND trx_state='LOCK WAIT'");
                long threads = single(probe, "SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_ID IN (" + blocker + "," + waiter + ")");
                String edge = "none";
                try (ResultSet rows = probe.executeQuery("SELECT r.PROCESSLIST_ID,b.PROCESSLIST_ID FROM performance_schema.data_lock_waits w LEFT JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID LEFT JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID LIMIT 1")) {
                    if (rows.next()) edge = rows.getLong(1) + "->" + rows.getLong(2);
                }
                diagnostic = "totalWaits=" + totalWaits + ",waitingLocks=" + waitingLocks + ",waiterTrxWait=" + waiterTrx + ",mappedThreads=" + threads + ",ids=" + blocker + "/" + waiter + ",edge=" + edge;
            }
            fail("No matching real InnoDB lock wait: " + label + " [" + diagnostic + "]");
        }
    }
    private static long single(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) { return rows.next() ? rows.getLong(1) : -1; }
    }
    private static long trxState(Connection connection, long processListId, String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=? AND trx_state=?")) {
            statement.setLong(1, processListId); statement.setString(2, state);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : -1; }
        }
    }
}
