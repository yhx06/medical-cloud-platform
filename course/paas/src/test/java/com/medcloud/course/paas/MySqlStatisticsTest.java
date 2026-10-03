package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.statistics.StatisticsService;
import org.junit.jupiter.api.*;
import javax.persistence.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MySqlStatisticsTest extends MySqlPlatformFixture {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"),ZoneId.of("Asia/Shanghai"));
    private static final CurrentActor ADMIN=new CurrentActor("admin",Role.HOSPITAL_ADMIN,"tenant-b");
    @BeforeEach void statisticsFixture() {
        inTransaction(em -> {
            sql(em,"delete from payment_order"); sql(em,"delete from clinical_record"); sql(em,"delete from appointment");
            appointment(em,"book","tenant-a","BOOKED","2026-10-01",null);
            appointment(em,"progress","tenant-a","IN_PROGRESS","2026-10-01","2026-10-02 08:00:00");
            appointment(em,"cancel","tenant-a","CANCELED","2026-10-01",null);
            appointment(em,"done","tenant-a","COMPLETED","2026-10-01","2026-09-30 08:00:00");
            record(em,"done-record","done","2026-10-02 10:00:00");
            order(em,"done-order","done","39.10","PAID","2026-10-03 09:00:00");
            appointment(em,"b-cancel","tenant-b","CANCELED","2026-10-02",null);
            appointment(em,"legacy","tenant-a","COMPLETED","2020-01-01",null);
            record(em,"legacy-record","legacy",null); order(em,"legacy-order","legacy","12.30","PAID",null);
            return null;
        });
    }

    @Test void actualDateDatetimeCohortsEventsMoneyAndZeroHospitalMatchHandCheckedFixture() {
        StatisticsReport hospital=inTransaction(em -> service(em).hospital(ADMIN,"2026-10-01","2026-10-02"));
        assertMetrics(hospital.getMetrics(),new long[]{4,1,1,1,1,1,1,1,0},"39.10","0.00");
        assertEquals("tenant-a",hospital.getHospitals().get(0).getTenant().getId());
        assertEquals(4,hospital.getDaily().get(0).getMetrics().getAppointmentTotal());
        assertEquals(1,hospital.getDaily().get(1).getMetrics().getOrderGenerated());
        StatisticsReport platform=inTransaction(em -> service(em).platform(PLATFORM,"2026-10-01","2026-10-02"));
        assertMetrics(platform.getMetrics(),new long[]{5,1,1,2,1,1,1,1,0},"39.10","0.00");
        assertEquals(3,platform.getTenantCounts().getTotal()); assertEquals(2,platform.getTenantCounts().getActive()); assertEquals(1,platform.getTenantCounts().getDisabled());
        assertMetrics(platform.getHospitals().get(2).getMetrics(),new long[9],"0.00","0.00");
        assertConsistent(platform); assertConsistent(hospital);
        StatisticsReport payment=inTransaction(em -> service(em).hospital(ADMIN,"2026-10-03","2026-10-03"));
        assertMetrics(payment.getMetrics(),new long[]{0,0,0,0,0,0,0,0,1},"0.00","39.10");
    }

    @Test void realNullTimesStayNullAndRemainSeparateAllTimeSupplement() {
        StatisticsReport report=inTransaction(em -> service(em).hospital(ADMIN,"2099-01-01","2099-01-01"));
        assertMetrics(report.getMetrics(),new long[9],"0.00","0.00");
        LegacyUnknownTime u=report.getLegacyUnknownTime();
        assertEquals(1,u.getStartedAppointments()); assertEquals(1,u.getCompletedRecords()); assertEquals(1,u.getGeneratedOrders()); assertEquals(1,u.getPaidOrders());
        assertEquals(new BigDecimal("12.30"),u.getOrderAmount()); assertEquals(new BigDecimal("12.30"),u.getPaidRevenue());
        inTransaction(em -> {
            Object[] row=(Object[])em.createNativeQuery("select booked_at,started_at from appointment where id='legacy'").getSingleResult();
            assertNull(row[0]); assertNull(row[1]);
            assertNull(em.createNativeQuery("select completed_at from clinical_record where id='legacy-record'").getSingleResult());
            assertNull(em.createNativeQuery("select paid_at from payment_order where id='legacy-order'").getSingleResult()); return null;
        });
    }

    @Test void shanghaiMidnightBoundsUseMysqlWallDateEvenWithForeignJvmTimezone() {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            inTransaction(em -> {
                appointment(em,"edge-first","tenant-a","COMPLETED","2026-09-01","2026-10-01 00:00:00");
                appointment(em,"edge-last","tenant-a","IN_PROGRESS","2026-09-01","2026-10-02 23:59:59");
                appointment(em,"edge-next","tenant-a","IN_PROGRESS","2026-09-01","2026-10-03 00:00:00");
                record(em,"edge-record","edge-first","2026-10-01 00:00:00");
                order(em,"edge-order","edge-first","0.10","PAID","2026-10-02 23:59:59"); return null;
            });
            StatisticsReport report=inTransaction(em -> service(em).hospital(ADMIN,"2026-10-01","2026-10-02"));
            assertMetrics(report.getMetrics(),new long[]{4,1,1,1,1,3,2,2,1},"39.20","0.10");
            assertEquals("2026-10-01",report.getDaily().get(0).getDate()); assertEquals("2026-10-02",report.getDaily().get(1).getDate());
            assertEquals(1,report.getDaily().get(0).getMetrics().getConsultationStarted());
            assertEquals(2,report.getDaily().get(1).getMetrics().getConsultationStarted()); assertConsistent(report);
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void reportUsesOneRepeatableReadSnapshot() throws Exception {
        inTransaction(em -> { sql(em,"update payment_order set status='UNPAID',paid_at=null where id='done-order'"); return null; });
        CountDownLatch firstAggregate=new CountDownLatch(1), committed=new CountDownLatch(1);
        ExecutorService thread=Executors.newSingleThreadExecutor();
        Future<?> competitor=thread.submit(() -> {
            await(firstAggregate);
            inTransaction(em -> {
                sql(em,"update payment_order set status='PAID',paid_at='2026-10-02 12:00:00' where id='done-order'");
                sql(em,"update tenant set status='DISABLED' where id='tenant-a'"); return null;
            });
            committed.countDown();
        });
        try {
            StatisticsReport first=inTransaction(em -> {
                AtomicBoolean paused=new AtomicBoolean(false);
                EntityManager observed=(EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(p,m,args) -> {
                    try {
                        Object result=m.invoke(em,args);
                        if(m.getName().equals("createNativeQuery") && ((String)args[0]).startsWith("select a.tenant_id, cast(a.booked_on")) {
                            Query query=(Query)result;
                            return Proxy.newProxyInstance(Query.class.getClassLoader(),new Class<?>[]{Query.class},(qp,qm,qa) -> {
                                try {
                                    Object qr=qm.invoke(query,qa);
                                    if(qm.getName().equals("getResultList") && paused.compareAndSet(false,true)) { firstAggregate.countDown(); await(committed); }
                                    return qr==query ? qp : qr;
                                } catch(InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch(InvocationTargetException e) { throw e.getCause(); }
                });
                return service(observed).platform(PLATFORM,"2026-10-01","2026-10-02");
            });
            competitor.get(10,TimeUnit.SECONDS);
            assertEquals(new BigDecimal("0.00"),first.getMetrics().getPaidRevenue());
            assertEquals(2,first.getTenantCounts().getActive()); assertEquals(1,first.getTenantCounts().getDisabled()); assertConsistent(first);
            StatisticsReport next=inTransaction(em -> service(em).platform(PLATFORM,"2026-10-01","2026-10-02"));
            assertEquals(new BigDecimal("39.10"),next.getMetrics().getPaidRevenue());
            assertEquals(1,next.getTenantCounts().getActive()); assertEquals(2,next.getTenantCounts().getDisabled()); assertConsistent(next);
        } finally {
            firstAggregate.countDown(); competitor.cancel(true); thread.shutdownNow();
            assertTrue(thread.awaitTermination(10,TimeUnit.SECONDS));
        }
    }

    private StatisticsService service(EntityManager em) { return new StatisticsService(em,CLOCK); }
    private void appointment(EntityManager em,String id,String tenant,String state,String date,String started) {
        sql(em,"insert into appointment(id,patient_id,slot_id,tenant_id,doctor_id,fee,status,booked_on,started_at) values (?1,'patient','slot',?2,'doctor',39.10,?3,?4,?5)",id,tenant,state,date,started);
    }
    private void record(EntityManager em,String id,String appointment,String completed) {
        sql(em,"insert into clinical_record(id,appointment_id,tenant_id,doctor_id,patient_id,diagnosis,advice,completed_at) values (?1,?2,'tenant-a','doctor','patient','fictional','course',?3)",id,appointment,completed);
    }
    private void order(EntityManager em,String id,String appointment,String amount,String status,String paid) {
        sql(em,"insert into payment_order(id,appointment_id,patient_id,tenant_id,amount,status,paid_at) values (?1,?2,'patient','tenant-a',?3,?4,?5)",id,appointment,new BigDecimal(amount),status,paid);
    }
    private static void sql(EntityManager em,String text,Object...values) { Query q=em.createNativeQuery(text); for(int i=0;i<values.length;i++)q.setParameter(i+1,values[i]); q.executeUpdate(); }
    private static long[] counts(StatisticsMetrics m) { return new long[]{m.getAppointmentTotal(),m.getAppointmentBooked(),m.getAppointmentInProgress(),m.getAppointmentCanceled(),m.getAppointmentCompleted(),m.getConsultationStarted(),m.getConsultationCompleted(),m.getOrderGenerated(),m.getPaidOrderCount()}; }
    private static void assertMetrics(StatisticsMetrics m,long[] count,String amount,String revenue) { assertArrayEquals(count,counts(m)); assertEquals(new BigDecimal(amount),m.getOrderAmount()); assertEquals(new BigDecimal(revenue),m.getPaidRevenue()); }
    private static void assertConsistent(StatisticsReport report) {
        long[] days=new long[9], hospitals=new long[9]; BigDecimal da=BigDecimal.ZERO,dr=BigDecimal.ZERO,ha=BigDecimal.ZERO,hr=BigDecimal.ZERO;
        for(DailyStatistics d:report.getDaily()) { long[] c=counts(d.getMetrics()); for(int i=0;i<9;i++)days[i]+=c[i]; da=da.add(d.getMetrics().getOrderAmount()); dr=dr.add(d.getMetrics().getPaidRevenue()); }
        long active=0,disabled=0;
        for(HospitalStatisticsView h:report.getHospitals()) { long[] c=counts(h.getMetrics()); for(int i=0;i<9;i++)hospitals[i]+=c[i]; ha=ha.add(h.getMetrics().getOrderAmount()); hr=hr.add(h.getMetrics().getPaidRevenue()); if(h.getTenant().getStatus().equals("ACTIVE"))active++;else disabled++; }
        assertArrayEquals(counts(report.getMetrics()),days); assertArrayEquals(days,hospitals);
        assertEquals(report.getMetrics().getOrderAmount(),da); assertEquals(da,ha); assertEquals(report.getMetrics().getPaidRevenue(),dr); assertEquals(dr,hr);
        long[] c=counts(report.getMetrics()); assertEquals(c[0],c[1]+c[2]+c[3]+c[4]);
        if(report.getTenantCounts()!=null) { assertEquals(report.getHospitals().size(),report.getTenantCounts().getTotal()); assertEquals(active,report.getTenantCounts().getActive()); assertEquals(disabled,report.getTenantCounts().getDisabled()); }
    }
}
