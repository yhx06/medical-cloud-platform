package com.medcloud.course.portal;

import org.junit.jupiter.api.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsJspTest {
    @BeforeAll static void start() throws Exception { WorkspaceJspTest.start(); }
    @AfterAll static void stop() throws Exception { WorkspaceJspTest.stop(); }

    @Test void zeroTrendAndHostileHospitalNameRenderSafely() throws Exception {
        String html = WorkspaceJspTest.render("platform?statistics=1");
        for (String value : new String[]{"Asia/Shanghai", "39.10", "19.55", "2024-01-01", "2024-12-31", "全量补充说明，不属于所选区间", "不受日期筛选影响", "booked_on", "started_at", "completed_at", "paid_at"})
            assertTrue(html.contains(value), value);
        assertTrue(html.contains("&lt;script&gt;unsafe&lt;/script&gt;"));
        assertFalse(html.contains("<script>unsafe</script>"));
        assertTrue(html.contains("零业务医院ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(html.contains("NaN")); assertFalse(html.contains("Infinity"));
        assertEquals(366, matches(html, "data-trend-date="));
        assertEquals(366, matches(html, "width:0%"));
        assertTrue(html.contains("tabindex=\"0\" role=\"region\" aria-label=\"每日趋势"));
        for (String id : new String[]{"tenant-management", "platform-statistics", "daily-trend", "architecture"})
            assertTrue(html.contains("id=\"" + id + "\""));
        assertFalse(html.contains("/patient")); assertFalse(html.contains("/doctor"));
        assertFalse(html.contains("appointmentId"));
    }

    @Test void platformStatusFormCarriesOptimisticStateCsrfAndValidFilters() throws Exception {
        String html = WorkspaceJspTest.render("platform?statistics=1");
        Matcher form = Pattern.compile("<form[^>]*action=\"/portal/platform/tenant/status\"[^>]*>([\\s\\S]*?)</form>").matcher(html);
        assertTrue(form.find());
        for (String field : new String[]{"id", "status", "expectedStatus", "csrfToken", "page", "size", "statsFrom", "statsTo"})
            assertTrue(form.group(1).contains("name=\"" + field + "\""), field);
        assertTrue(form.group(1).contains("value=\"DISABLED\""));
        assertTrue(form.group(1).contains("value=\"ACTIVE\""));
        assertTrue(html.contains("暂停预约、取消、接诊、缴费和资源维护，历史可查"));
        assertTrue(html.contains("page=1") && html.contains("page=3"));
    }

    @Test void populatedTrendIncludesEveryMetricAndBoundedBar() throws Exception {
        String html = WorkspaceJspTest.render("platform?statistics=1&populated=1");
        Matcher row = Pattern.compile("<tr data-trend-date=\"2024-01-01\">([\\s\\S]*?)</tr>").matcher(html);
        assertTrue(row.find());
        assertTrue(row.group(1).contains("width:100%"));
        assertTrue(row.group(1).contains("<td>5</td><td>2</td><td>1</td><td>1</td><td>1</td><td>2</td><td>3</td><td>3</td><td>39.10</td><td>1</td><td>19.55</td>"));
        assertEquals(365, matches(html, "width:0%"));
    }

    @Test void hospitalFiltersAndResetLinksRetainOnlyOtherValidConditions() throws Exception {
        String html = WorkspaceJspTest.render("hospital?statistics=1");
        assertTrue(html.contains("id=\"hospital-statistics\""));
        assertTrue(html.contains("href=\"/portal/hospital?from=2026-09-01&amp;to=2026-09-30&amp;status=PAID#hospital-statistics\""));
        assertTrue(html.contains("href=\"/portal/hospital?statsFrom=2024-01-01&amp;statsTo=2024-12-31#orders\""));
        assertTrue(html.contains("type=\"hidden\" name=\"statsFrom\" value=\"2024-01-01\""));
        assertTrue(html.contains("type=\"hidden\" name=\"from\" value=\"2026-09-01\""));
        String invalid = WorkspaceJspTest.render("hospital?statistics=1&invalid=1");
        assertTrue(invalid.contains("value=\"&lt;script&gt;bad&lt;/script&gt;\""));
        assertFalse(invalid.contains("&amp;lt;script"));
        assertTrue(invalid.contains("href=\"/portal/hospital#orders\""));
        assertTrue(invalid.contains("href=\"/portal/hospital?to=2026-09-30&amp;status=PAID#hospital-statistics\""));
    }

    @Test void statisticsResetRetainsSingleOrderBoundsButDropsInvertedRange() throws Exception {
        String fromOnly = WorkspaceJspTest.render("hospital?statistics=1&orderTo=");
        assertTrue(fromOnly.contains("href=\"/portal/hospital?from=2026-09-01&amp;status=PAID#hospital-statistics\""));
        String toOnly = WorkspaceJspTest.render("hospital?statistics=1&orderFrom=");
        assertTrue(toOnly.contains("href=\"/portal/hospital?to=2026-09-30&amp;status=PAID#hospital-statistics\""));
        String inverted = WorkspaceJspTest.render("hospital?statistics=1&orderFrom=2026-10-01");
        assertTrue(inverted.contains("href=\"/portal/hospital?status=PAID#hospital-statistics\""));
        assertFalse(inverted.contains("type=\"hidden\" name=\"from\""));
        assertFalse(inverted.contains("type=\"hidden\" name=\"to\""));
    }

    @Test void statisticsFailureDoesNotPretendZeroOrEraseOrderHistory() throws Exception {
        String html = WorkspaceJspTest.render("hospital?statistics=1&error=1");
        assertTrue(html.contains("统计服务暂不可用 &lt;unsafe&gt;"));
        assertTrue(html.contains("order-id"));
        assertFalse(html.contains("data-trend-date="));
        assertFalse(html.contains("class=\"metric-value\""));
        String platform = WorkspaceJspTest.render("platform?statistics=1&listError=1");
        assertTrue(platform.contains("医院列表暂不可用"));
        assertTrue(platform.contains("39.10"));
        assertFalse(platform.contains("action=\"/portal/platform/tenant/status\""));
    }

    private static int matches(String html, String text) {
        int count = 0; Matcher matcher = Pattern.compile(Pattern.quote(text)).matcher(html);
        while (matcher.find()) count++; return count;
    }
}
