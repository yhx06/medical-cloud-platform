package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.*;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.hibernate.stat.Statistics;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditPrivacyTest extends AuditFixture {
    @Test void dtoDoesNotExposeActorOrObjectIds() throws Exception {
        JsonNode serializedRow=new ObjectMapper().valueToTree(service.hospital(ADMIN,query()).getItems().get(0));
        assertFalse(serializedRow.has("actorId")); assertFalse(serializedRow.has("objectId"));
        Set<String> fields=new HashSet<String>(); serializedRow.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<String>(Arrays.asList("id","requestId","tenantId","tenantName","actorRole","actorLabel","eventType","objectType","result","reasonCode","recordedAt")),fields);
        assertEquals("患者（已脱敏）", serializedRow.path("actorLabel").asText());
        String json=serializedRow.toString(); assertFalse(json.contains("private-actor")); assertFalse(json.contains("private-object"));
        JsonNode page=new ObjectMapper().valueToTree(service.platform(PLATFORM,query()));
        Set<String> pageFields=new HashSet<String>(); page.fieldNames().forEachRemaining(pageFields::add);
        assertEquals(new HashSet<String>(Arrays.asList("items","total","page","size","from","to","timezone")),pageFields);
    }
    @Test void queryDoesNotLoadPrivateEntitiesIncludingAuthentication() {
        AuthService auth=new AuthService(manager,SECRET);
        String token=AuditLocalTransactions.login(manager, auth, new LoginRequest("admin",PASSWORD)).getAccessToken(); manager.clear();
        Statistics stats=manager.unwrap(org.hibernate.Session.class).getSessionFactory().getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        com.medcloud.course.paas.auth.CurrentActor actor=auth.authenticateAuditReader(token);
        service.requireReader(actor,false); service.hospital(actor,query()); service.platform(PLATFORM,query());
        assertEquals(0,stats.getEntityLoadCount(),"security and audit projection must select scalars, not identity/clinical entities");
        assertEquals(0,stats.getEntityUpdateCount()); assertEquals(0,stats.getEntityInsertCount());
    }
    @Test void authenticationSeesCurrentEnabledAccountAndReadableHospital() {
        AuthService auth=new AuthService(manager,SECRET); String token=AuditLocalTransactions.login(manager, auth, new LoginRequest("admin",PASSWORD)).getAccessToken();
        update("update tenant set status='DISABLED' where id=?1",A);
        assertEquals(A,auth.authenticateAuditReader(token).getTenantId());
        update("update user_account set status='DISABLED' where id='admin'");
        assertThrows(SecurityException.class,()->auth.authenticateAuditReader(token));
        assertThrows(SecurityException.class,()->auth.authenticateAuditReader("invalid"));
    }
    @Test void recordedTimeAlwaysUsesTheContractEightHourOffsetEvenForEarlyMysqlDates() {
        event(8,A,"LOGIN","SUCCESS","1800-01-01 12:00:00");
        AuditEventView row=service.hospital(ADMIN,com.medcloud.course.paas.audit.AuditQuery.parse(
                params("from","1800-01-01","to","1800-01-01"),false)).getItems().get(0);
        assertTrue(row.getRecordedAt().endsWith("+08:00"),row.getRecordedAt());
    }
    @Test void sharedTokenValidationRejectsExpiredTamperedUnsupportedAndMissingSubjects() throws Exception {
        AuthService auth=new AuthService(manager,SECRET);
        String valid=AuditLocalTransactions.login(manager, auth, new LoginRequest("admin",PASSWORD)).getAccessToken();
        String[] tokens={valid.substring(0,valid.lastIndexOf('.')+1)+"AAAA",
                signed("admin",System.currentTimeMillis()/1000-60,"HS256"),
                signed("admin",System.currentTimeMillis()/1000+60,"none"),
                signed("missing",System.currentTimeMillis()/1000+60,"HS256")};
        for(String token:tokens) {
            assertThrows(SecurityException.class,()->auth.authenticateAuditReader(token));
            assertThrows(SecurityException.class,()->auth.authenticate(token));
        }
    }
    private static String signed(String subject,long expiry,String algorithm) throws Exception {
        java.nio.charset.Charset utf8=java.nio.charset.StandardCharsets.UTF_8;
        String header=Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"alg\":\""+algorithm+"\"}").getBytes(utf8));
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\""+subject+"\",\"exp\":"+expiry+"}").getBytes(utf8));
        String body=header+"."+payload;
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(utf8),"HmacSHA256"));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(utf8)));
    }
}
