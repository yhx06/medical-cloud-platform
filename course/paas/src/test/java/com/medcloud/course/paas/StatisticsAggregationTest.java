package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.statistics.*;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.*;
import javax.persistence.Query;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsAggregationTest extends StatisticsFixture {
    @Test void cohortStatesAndEventsUseTheirOwnDatesAndSumAcrossEveryHospitalAndDay() {
        StatisticsReport hospital = service.hospital(ADMIN, "2026-10-01", "2026-10-02");
        assertEquals(4L, hospital.getMetrics().getAppointmentTotal());
        assertEquals(1L, hospital.getMetrics().getAppointmentCanceled());
        assertEquals(1L, hospital.getMetrics().getConsultationCompleted());
        assertEquals(new BigDecimal("39.10"), hospital.getMetrics().getOrderAmount());
        assertEquals(new BigDecimal("0.00"), hospital.getMetrics().getPaidRevenue());
        assertEquals(1L, hospital.getMetrics().getAppointmentBooked());
        assertEquals(1L, hospital.getMetrics().getAppointmentInProgress());
        assertEquals(1L, hospital.getMetrics().getAppointmentCompleted());
        assertEquals(1L, hospital.getMetrics().getConsultationStarted(), "completed cohort appointment started outside range");
        assertEquals(4L, hospital.getDaily().get(0).getMetrics().getAppointmentTotal());
        assertEquals(0L, hospital.getDaily().get(0).getMetrics().getConsultationCompleted());
        assertEquals(1L, hospital.getDaily().get(1).getMetrics().getOrderGenerated());
        assertNull(hospital.getTenantCounts());
        assertEquals("HOSPITAL", hospital.getScope());
        assertEquals("tenant-a", hospital.getHospitals().get(0).getTenant().getId(), "database affiliation overrides stale actor tenant-b");
        StatisticsReport platform = service.platform(PLATFORM, "2026-10-01", "2026-10-02");
        assertEquals(5L, platform.getMetrics().getAppointmentTotal());
        assertEquals(3, platform.getHospitals().size());
        assertEquals(2, platform.getDaily().size());
        assertEquals(3L, platform.getTenantCounts().getTotal());
        assertEquals(2L, platform.getTenantCounts().getActive());
        assertEquals(1L, platform.getTenantCounts().getDisabled());
        assertEquals("PLATFORM", platform.getScope());
        assertEquals("Asia/Shanghai", platform.getTimezone());
        assertEquals("2026-10-03T12:00:00+08:00", platform.getGeneratedAt());
        assertEquals("2026-10-01", platform.getFrom());
        assertEquals("2026-10-02", platform.getTo());
        assertZero(platform.getHospitals().get(2).getMetrics());
        assertSums(platform);
        assertSums(hospital);
    }

    @Test void paymentAfterGenerationCountsRevenueOnPaymentDayOnly() {
        StatisticsReport report = service.hospital(ADMIN, "2026-10-03", "2026-10-03");
        assertEquals(new BigDecimal("39.10"), report.getMetrics().getPaidRevenue());
        assertEquals(new BigDecimal("0.00"), report.getMetrics().getOrderAmount());
        assertEquals(1L, report.getMetrics().getPaidOrderCount());
        assertEquals(0L, report.getMetrics().getOrderGenerated());
    }

    @Test void unknownTimesAreIndependentAllTimeSupplementAndAreNeverBackfilled() {
        StatisticsReport report = service.hospital(ADMIN, "2026-10-01", "2026-10-02");
        LegacyUnknownTime unknown = report.getLegacyUnknownTime();
        assertEquals(1L, unknown.getStartedAppointments());
        assertEquals(1L, unknown.getCompletedRecords());
        assertEquals(1L, unknown.getGeneratedOrders());
        assertEquals(1L, unknown.getPaidOrders());
        assertEquals(new BigDecimal("12.30"), unknown.getOrderAmount());
        assertEquals(new BigDecimal("12.30"), unknown.getPaidRevenue());
        StatisticsReport future = service.hospital(ADMIN, "2099-01-01", "2099-01-01");
        assertZero(future.getMetrics());
        assertEquals(1, future.getLegacyUnknownTime().getCompletedRecords());
        Object[] row = (Object[]) manager.createNativeQuery("select booked_at, started_at from appointment where id = 'legacy'").getSingleResult();
        assertNull(row[0]); assertNull(row[1]);
        assertNull(manager.createNativeQuery("select completed_at from clinical_record where id = 'legacy-record'").getSingleResult());
        assertNull(manager.createNativeQuery("select paid_at from payment_order where id = 'legacy-order'").getSingleResult());
        assertEquals(1, report.getHospitals().get(0).getLegacyUnknownTime().getGeneratedOrders());
    }

    @Test void aggregatesMoreThanExistingListLimitsAndKeepsOtherTenantOut() {
        for (int i=0; i<201; i++) appointment("bulk-" + i, "tenant-a", "BOOKED", "2026-10-02", null);
        manager.flush();
        assertEquals(205L, service.hospital(ADMIN, "2026-10-01", "2026-10-02").getMetrics().getAppointmentTotal());
        assertEquals(206L, service.platform(PLATFORM, "2026-10-01", "2026-10-02").getMetrics().getAppointmentTotal());
    }

    @Test void mismatchedTenantOrMissingRecordCannotGenerateOrdersInDateRange() {
        record("foreign-record", "foreign-appointment", "tenant-a", "2026-10-02 10:00:00");
        order("wrong-tenant", "foreign-appointment", "tenant-b", "9.99", "UNPAID", null);
        order("missing-record", "no-record", "tenant-a", "1.23", "UNPAID", null);
        StatisticsReport report = service.platform(PLATFORM, "2026-10-01", "2026-10-02");
        assertEquals(1L, report.getMetrics().getOrderGenerated());
        assertEquals(new BigDecimal("39.10"), report.getMetrics().getOrderAmount());
        assertEquals(3L, report.getLegacyUnknownTime().getGeneratedOrders());
        assertEquals(new BigDecimal("23.52"), report.getLegacyUnknownTime().getOrderAmount());
    }

    @Test void eventBoundsIncludeShanghaiMidnightAndExcludeNextMidnightEvenUnderForeignJvmZone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            appointment("boundary-start", "tenant-a", "COMPLETED", "2026-09-01", "2026-10-01 00:00:00");
            appointment("boundary-last", "tenant-a", "IN_PROGRESS", "2026-09-01", "2026-10-02 23:59:59");
            appointment("boundary-next", "tenant-a", "IN_PROGRESS", "2026-09-01", "2026-10-03 00:00:00");
            record("edge-record", "boundary-start", "tenant-a", "2026-10-01 00:00:00");
            order("edge-order", "boundary-start", "tenant-a", "0.10", "PAID", "2026-10-02 23:59:59");
            for (DailyStatistics day : new StatisticsRepository(manager)
                    .daily(StatisticsRange.parse("2026-10-01", "2026-10-02", StatisticsRangeTest.CLOCK), "tenant-a").get("tenant-a"))
                assertTrue(day.getDate().compareTo("2026-10-01") >= 0 && day.getDate().compareTo("2026-10-02") <= 0,
                        "SQL grouped local date must survive JDBC unchanged: " + day.getDate());
            StatisticsReport report = service.hospital(ADMIN, "2026-10-01", "2026-10-02");
            assertEquals(3L, report.getMetrics().getConsultationStarted());
            assertEquals(2L, report.getMetrics().getConsultationCompleted());
            assertEquals(new BigDecimal("39.20"), report.getMetrics().getOrderAmount());
            assertEquals(new BigDecimal("0.10"), report.getMetrics().getPaidRevenue());
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void exactMoneyAndAll366ZeroDaysAndDisabledHospitalRemainReadable() {
        record("small-record-a", "small-a", "tenant-a", "2026-10-02 12:00:00");
        record("small-record-b", "small-b", "tenant-a", "2026-10-02 12:00:00");
        order("small-order-a", "small-a", "tenant-a", "0.10", "PAID", "2026-10-02 13:00:00");
        order("small-order-b", "small-b", "tenant-a", "0.20", "PAID", "2026-10-02 13:00:00");
        execute("update tenant set status = 'DISABLED' where id = 'tenant-a'");
        StatisticsReport small = service.hospital(ADMIN, "2026-10-01", "2026-10-02");
        assertEquals(new BigDecimal("39.40"), small.getMetrics().getOrderAmount());
        assertEquals(new BigDecimal("0.30"), small.getMetrics().getPaidRevenue());
        StatisticsReport empty = service.platform(PLATFORM, "2024-01-01", "2024-12-31");
        assertEquals(366, empty.getDaily().size());
        assertEquals("2024-01-01", empty.getDaily().get(0).getDate());
        assertEquals("2024-12-31", empty.getDaily().get(365).getDate());
        for (DailyStatistics day : empty.getDaily()) assertZero(day.getMetrics());
        assertSums(empty);
    }

    @Test void nativeAggregatesUseDatabaseValuesRatherThanStaleManagedBusinessEntities() {
        manager.find(com.medcloud.course.paas.appointment.Appointment.class, "book");
        execute("update appointment set status = 'CANCELED' where id = 'book'");
        StatisticsReport report = service.hospital(ADMIN, "2026-10-01", "2026-10-02");
        assertEquals(0, report.getMetrics().getAppointmentBooked());
        assertEquals(2, report.getMetrics().getAppointmentCanceled());
    }

    @Test void repositoryStatementCountDoesNotGrowWithDaysOrHospitalsAndBindsTenantLiterally() {
        org.hibernate.stat.Statistics sql = manager.unwrap(org.hibernate.Session.class)
                .getSessionFactory().getStatistics();
        sql.setStatisticsEnabled(true);
        StatisticsRepository repository = new StatisticsRepository(manager);
        for (String tenant : new String[] {"tenant-a", null, "tenant-a' OR '1'='1"}) {
            for (String[] dates : new String[][] {{"2026-10-01","2026-10-02"}, {"2024-01-01","2024-12-31"}}) {
                sql.clear();
                java.util.List<TenantView> hospitals = repository.tenants(tenant);
                java.util.Map<String,java.util.List<DailyStatistics>> days = repository.daily(
                        StatisticsRange.parse(dates[0],dates[1],StatisticsRangeTest.CLOCK),tenant);
                java.util.Map<String,LegacyUnknownTime> unknown = repository.legacy(tenant);
                assertEquals(10L, sql.getPrepareStatementCount(), "one tenant, five daily, four unknown-time queries");
                if (tenant != null && tenant.contains("'")) {
                    assertTrue(hospitals.isEmpty()); assertTrue(days.isEmpty()); assertTrue(unknown.isEmpty());
                }
            }
        }
    }

    private void assertSums(StatisticsReport report) {
        StatisticsMetrics daily = StatisticsMetrics.zero();
        for (DailyStatistics day : report.getDaily()) daily = daily.plus(day.getMetrics());
        assertMetricEquals(report.getMetrics(), daily);
        StatisticsMetrics hospitals = StatisticsMetrics.zero();
        LegacyUnknownTime legacy = LegacyUnknownTime.zero();
        for (HospitalStatisticsView hospital : report.getHospitals()) {
            hospitals = hospitals.plus(hospital.getMetrics());
            legacy = legacy.plus(hospital.getLegacyUnknownTime());
        }
        assertMetricEquals(report.getMetrics(), hospitals);
        assertEquals(report.getLegacyUnknownTime().getStartedAppointments(), legacy.getStartedAppointments());
        assertEquals(report.getLegacyUnknownTime().getCompletedRecords(), legacy.getCompletedRecords());
        assertEquals(report.getLegacyUnknownTime().getGeneratedOrders(), legacy.getGeneratedOrders());
        assertEquals(report.getLegacyUnknownTime().getPaidOrders(), legacy.getPaidOrders());
        assertEquals(report.getLegacyUnknownTime().getOrderAmount(), legacy.getOrderAmount());
        assertEquals(report.getLegacyUnknownTime().getPaidRevenue(), legacy.getPaidRevenue());
    }

    private void assertMetricEquals(StatisticsMetrics a, StatisticsMetrics b) {
        assertEquals(a.getAppointmentTotal(), b.getAppointmentTotal());
        assertEquals(a.getAppointmentBooked(), b.getAppointmentBooked());
        assertEquals(a.getAppointmentInProgress(), b.getAppointmentInProgress());
        assertEquals(a.getAppointmentCanceled(), b.getAppointmentCanceled());
        assertEquals(a.getAppointmentCompleted(), b.getAppointmentCompleted());
        assertEquals(a.getConsultationStarted(), b.getConsultationStarted());
        assertEquals(a.getConsultationCompleted(), b.getConsultationCompleted());
        assertEquals(a.getOrderGenerated(), b.getOrderGenerated());
        assertEquals(a.getPaidOrderCount(), b.getPaidOrderCount());
        assertEquals(a.getOrderAmount(), b.getOrderAmount());
        assertEquals(a.getPaidRevenue(), b.getPaidRevenue());
    }

    private void assertZero(StatisticsMetrics metrics) { assertMetricEquals(StatisticsMetrics.zero(), metrics); }
}

abstract class StatisticsFixture extends TenantFixture {
    StatisticsService service;

    @BeforeEach void statisticsFixture() {
        manager.getTransaction().begin();
        execute("delete from payment_order");
        execute("delete from clinical_record");
        execute("delete from appointment");
        Tenant empty = new Tenant("tenant-c", "虚构医院c");
        empty.setStatus("DISABLED");
        manager.persist(empty);
        manager.flush();
        appointment("book", "tenant-a", "BOOKED", "2026-10-01", null);
        appointment("progress", "tenant-a", "IN_PROGRESS", "2026-10-01", "2026-10-02 08:00:00");
        appointment("cancel", "tenant-a", "CANCELED", "2026-10-01", null);
        appointment("done", "tenant-a", "COMPLETED", "2026-10-01", "2026-09-30 08:00:00");
        record("done-record", "done", "tenant-a", "2026-10-02 10:00:00");
        order("done-order", "done", "tenant-a", "39.10", "PAID", "2026-10-03 09:00:00");
        appointment("b-cancel", "tenant-b", "CANCELED", "2026-10-02", null);
        appointment("legacy", "tenant-a", "COMPLETED", "2020-01-01", null);
        record("legacy-record", "legacy", "tenant-a", null);
        order("legacy-order", "legacy", "tenant-a", "12.30", "PAID", null);
        service = new StatisticsService(manager, StatisticsRangeTest.CLOCK);
    }

    void appointment(String id, String tenant, String status, String booked, String started) {
        execute("insert into appointment (id,patient_id,slot_id,tenant_id,doctor_id,fee,status,booked_on,started_at) values (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            id, "patient-private", "slot-private", tenant, "doctor-private", new BigDecimal("39.10"), status, booked, started);
    }

    void record(String id, String appointment, String tenant, String completed) {
        execute("insert into clinical_record (id,appointment_id,tenant_id,doctor_id,patient_id,diagnosis,advice,completed_at) values (?1,?2,?3,?4,?5,?6,?7,?8)",
            id, appointment, tenant, "doctor-private", "patient-private", "private diagnosis", "private advice", completed);
    }

    void order(String id, String appointment, String tenant, String amount, String status, String paid) {
        execute("insert into payment_order (id,appointment_id,patient_id,tenant_id,amount,status,paid_at) values (?1,?2,?3,?4,?5,?6,?7)",
            id, appointment, "patient-private", tenant, new BigDecimal(amount), status, paid);
    }

    void execute(String sql, Object... args) {
        Query query = manager.createNativeQuery(sql);
        for (int i=0;i<args.length;i++) query.setParameter(i+1,args[i]);
        query.executeUpdate();
    }
}
