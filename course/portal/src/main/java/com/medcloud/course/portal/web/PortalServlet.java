package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.BookingRequest;
import com.medcloud.course.contract.CompleteConsultationRequest;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.RegisterRequest;
import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.DepartmentRequest;
import com.medcloud.course.contract.DoctorRequest;
import com.medcloud.course.contract.DoctorProfileRequest;
import com.medcloud.course.contract.HospitalNameRequest;
import com.medcloud.course.contract.SlotRequest;
import com.medcloud.course.portal.factory.CloudServiceFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.util.Base64;
import java.util.regex.Pattern;

@WebServlet(urlPatterns = {"/", "/login", "/register", "/logout", "/patient", "/patient/book", "/patient/cancel", "/patient/pay",
        "/doctor", "/doctor/start", "/doctor/complete", "/doctor/profile", "/hospital", "/hospital/name",
        "/hospital/department/create", "/hospital/department/edit", "/hospital/doctor/create", "/hospital/doctor/edit",
        "/hospital/slot/create", "/hospital/slot/edit", "/platform", "/platform/*"})
public class PortalServlet extends HttpServlet {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern UUID_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private PaasClient paas;
    private CloudServiceFactory factory;

    public PortalServlet() { }

    public PortalServlet(PaasClient paas, CloudServiceFactory factory) {
        this.paas = paas;
        this.factory = factory;
    }

    @Override
    public void init() {
        if (paas == null) {
            String configured = getServletConfig().getInitParameter("paasBaseUrl");
            if (configured == null || configured.trim().isEmpty()) {
                configured = System.getenv("MEDICAL_COURSE_PAAS_URL");
            }
            if (configured == null || configured.trim().isEmpty()) {
                configured = "http://127.0.0.1:8080/paas/api";
            }
            paas = new PaasClient(configured);
        }
        if (factory == null) {
            factory = new CloudServiceFactory();
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = requestPath(request);
        if ("/register".equals(path)) {
            HttpSession session = request.getSession(true);
            csrfToken(session);
            request.setAttribute("csrfToken", session.getAttribute("csrfToken"));
            show(request, response, "register.jsp");
            return;
        }
        if ("/".equals(path) || "/login".equals(path)) {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute("role") instanceof Role) {
                response.sendRedirect(request.getContextPath() + factory.forRole((Role) session.getAttribute("role"))
                        .getHomePath());
            } else {
                if (session != null && session.getAttribute("flash") != null) {
                    request.setAttribute("notice", WebEscaper.html(String.valueOf(session.getAttribute("flash"))));
                    session.removeAttribute("flash");
                }
                show(request, response, "login.jsp");
            }
            return;
        }
        if ("/logout".equals(path)) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            response.sendRedirect(request.getContextPath() + "/login");
            return;
        }
        Role expected = roleForPath(path);
        if (expected == null) {
            response.sendError(404);
            return;
        }
        HttpSession session = requireRole(request, response, expected);
        if (session == null) return;
        if (!path.equals(factory.forRole(expected).getHomePath())) {
            response.sendError(404);
            return;
        }
        request.setAttribute("portalTitle", factory.forRole(expected).getTitle());
        request.setAttribute("username", session.getAttribute("username"));
        request.setAttribute("csrfToken", session.getAttribute("csrfToken"));
        Object flash = session.getAttribute("flash");
        if (flash != null) {
            request.setAttribute("notice", WebEscaper.html(String.valueOf(flash)));
            session.removeAttribute("flash");
        }
        if ("/hospital".equals(path) && session.getAttribute("initialPassword") != null) {
            request.setAttribute("initialPassword", session.getAttribute("initialPassword"));
            session.removeAttribute("initialPassword");
        }
        try {
            WorkspaceAccess access = new WorkspaceAccess(paas);
            if ("/patient".equals(path)) {
                patient(request, session);
            } else if ("/doctor".equals(path)) {
                access.loadPage(request, token(session), "appointments", "/doctor/appointments",
                        pageNumber(request, "page", 1, Integer.MAX_VALUE), pageNumber(request, "size", 20, 100));
                loadFilteredSection(request, token(session), access, "records", "/doctor/records", false);
                access.loadSection(request, token(session), "doctorProfile", "/doctor/profile");
                access.loadContext(request, token(session), expected);
            } else if ("/hospital".equals(path)) {
                access.loadPage(request, token(session), "departments", "/hospital/departments",
                        pageNumber(request, "page", 1, Integer.MAX_VALUE), pageNumber(request, "size", 100, 100));
                access.loadPage(request, token(session), "doctors", "/hospital/doctors",
                        pageNumber(request, "page", 1, Integer.MAX_VALUE), pageNumber(request, "size", 100, 100));
                access.loadPage(request, token(session), "slots", "/hospital/slots",
                        pageNumber(request, "page", 1, Integer.MAX_VALUE), pageNumber(request, "size", 100, 100));
                loadFilteredSection(request, token(session), access, "orders", "/hospital/orders", true);
                access.loadContext(request, token(session), expected);
                new StatisticsWorkspace(paas).load(request, token(session), expected);
                new AuditWorkspace(paas).load(request, token(session), expected);
            } else if ("/platform".equals(path)) {
                new PlatformWorkspace(paas).load(request, token(session));
                new StatisticsWorkspace(paas).load(request, token(session), expected);
                new AuditWorkspace(paas).load(request, token(session), expected);
            }
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            if (unauthorized(error)) {
                authorizationExpired(request, response, session);
                return;
            }
            request.setAttribute("error", WebEscaper.html(error.getMessage()));
        }
        show(request, response, path.substring(1) + ".jsp");
    }

    private void patient(HttpServletRequest request, HttpSession session) throws IOException {
        // Independent reads: a catalog failure must not hide existing appointments or records.
        WorkspaceAccess access = new WorkspaceAccess(paas);
        access.loadPage(request, token(session), "appointments", "/patient/appointments",
                pageNumber(request, "page", 1, Integer.MAX_VALUE), pageNumber(request, "size", 20, 100));
        String tenant = parameter(request, "tenantId");
        String department = parameter(request, "departmentId");
        String doctor = parameter(request, "doctorId");
        String date = parameter(request, "date");
        boolean refreshDirectory = "1".equals(request.getParameter("refreshDirectory"));
        if (refreshDirectory && request.getParameter("previousTenantId") != null
                && !tenant.equals(parameter(request, "previousTenantId"))) {
            department = ""; doctor = "";
        } else if (refreshDirectory && request.getParameter("previousDepartmentId") != null
                && !department.equals(parameter(request, "previousDepartmentId"))) {
            doctor = "";
        }
        try {
            JsonNode hospitals = paas.get("/catalog/hospitals", null);
            request.setAttribute("hospitals", hospitals);
            if (!tenant.isEmpty()) {
                if (!containsOption(hospitals, tenant)) {
                    tenant = ""; department = ""; doctor = "";
                    throw new IllegalArgumentException("所选医院已不可用，请重新选择医院");
                }
                String tenantQuery = "tenantId=" + encode(tenant);
                JsonNode departments = paas.get("/catalog/departments?" + tenantQuery, null);
                request.setAttribute("departments", departments);
                if (!department.isEmpty() && !containsOption(departments, department)) {
                    department = ""; doctor = "";
                    throw new IllegalArgumentException("所选科室不属于当前医院或已停用，请重新选择科室");
                }
                JsonNode doctors = paas.get("/catalog/doctors?" + tenantQuery
                        + (department.isEmpty() ? "" : "&departmentId=" + encode(department)), null);
                request.setAttribute("doctors", doctors);
                if (!doctor.isEmpty() && !containsOption(doctors, doctor)) {
                    doctor = "";
                    throw new IllegalArgumentException("所选医生不属于当前科室或已停用，请重新选择医生");
                }
                if (!refreshDirectory) {
                    com.fasterxml.jackson.databind.JsonNode slotPage = paas.get("/catalog/slots?"
                            + patientFilters(tenant, department, doctor, date), null);
                    request.setAttribute("slots", slotPage.isObject() ? slotPage.path("items") : slotPage);
                }
            } else {
                department = ""; doctor = "";
            }
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
            patientError(request, failure);
        }
        request.setAttribute("tenantId", WebEscaper.html(tenant));
        request.setAttribute("departmentId", WebEscaper.html(department));
        request.setAttribute("doctorId", WebEscaper.html(doctor));
        request.setAttribute("date", WebEscaper.html(date));
        request.setAttribute("patientFilters", patientFilters(tenant, department, doctor, date));
        String appointment = parameter(request, "appointmentId");
        request.setAttribute("appointmentId", WebEscaper.html(appointment));
        if (!appointment.isEmpty()) {
            try {
                String id = requiredId(appointment);
                access.loadSection(request, token(session), "record", "/patient/appointments/" + id + "/record");
                access.loadSection(request, token(session), "order", "/patient/appointments/" + id + "/order");
            } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
                patientError(request, failure);
            }
        }
    }

    private static String parameter(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        return value == null ? "" : value.trim();
    }

    private static int pageNumber(HttpServletRequest request, String key, int fallback, int max) {
        String value = parameter(request, key);
        if (value.isEmpty()) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int n = Integer.parseInt(value);
            if (n < 1 || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException bad) { throw new IllegalArgumentException("分页参数无效"); }
    }

    private static boolean containsOption(JsonNode options, String id) {
        if (options != null && options.isArray())
            for (JsonNode option : options) if (id.equals(option.path("id").asText())) return true;
        return false;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String patientFilters(String tenant, String department, String doctor, String date) throws IOException {
        StringBuilder query = new StringBuilder();
        String[] keys = {"tenantId", "departmentId", "doctorId", "date"};
        String[] values = {tenant, department, doctor, date};
        for (int i = 0; i < keys.length; i++) if (!values[i].isEmpty()) {
            if (query.length() > 0) query.append('&');
            query.append(keys[i]).append('=').append(encode(values[i]));
        }
        return query.toString();
    }

    private static void patientError(HttpServletRequest request, RuntimeException failure) {
        WorkspaceAccess.rethrowUnauthorized(failure);
        String escaped = WebEscaper.html(failure.getMessage());
        Object previous = request.getAttribute("error");
        request.setAttribute("error", previous == null ? escaped : previous + "；" + escaped);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        String path = requestPath(request);
        if ("/login".equals(path)) {
            login(request, response);
            return;
        }
        if ("/register".equals(path)) {
            register(request, response);
            return;
        }
        Role expected = roleForPath(path);
        if (expected == null) {
            response.sendError(404);
            return;
        }
        HttpSession session = requireRole(request, response, expected);
        if (session == null) return;
        if (session.getAttribute("csrfToken") == null
                || !session.getAttribute("csrfToken").equals(request.getParameter("csrfToken"))) {
            response.sendError(403);
            return;
        }
        String home = factory.forRole(expected).getHomePath();
        if (expected == Role.PLATFORM_ADMIN) home += new PlatformWorkspace(paas).redirectQuery(request);
        String redirectHome = home;
        try {
            JsonNode result;
            if ("/platform/tenant/status".equals(path)) {
                new PlatformWorkspace(paas).changeStatus(request, token(session));
                session.setAttribute("flash", "医院状态已更新");
            } else if ("/patient/book".equals(path)) {
                result = paas.post("/patient/appointments", token(session),
                        new BookingRequest(required(request, "slotId")));
                session.setAttribute("flash", "预约成功，预约编号：" + result.path("id").asText());
            } else if ("/patient/cancel".equals(path)) {
                paas.post("/patient/appointments/" + requiredId(required(request, "appointmentId")) + "/cancel",
                        token(session), null);
                session.setAttribute("flash", "预约已取消");
            } else if ("/doctor/start".equals(path)) {
                paas.post("/doctor/appointments/" + requiredId(required(request, "appointmentId")) + "/start",
                        token(session), null);
                session.setAttribute("flash", "接诊已开始");
            } else if ("/doctor/complete".equals(path)) {
                CompleteConsultationRequest clinicalRequest = new CompleteConsultationRequest(
                        required(request, "diagnosis"), required(request, "advice"));
                clinicalRequest.setComplaint(required(request, "complaint"));
                clinicalRequest.setNotes(request.getParameter("notes"));
                paas.post("/doctor/appointments/" + requiredId(required(request, "appointmentId")) + "/complete",
                        token(session), clinicalRequest);
                session.setAttribute("flash", "病历与账单已生成");
            } else if ("/patient/pay".equals(path)) {
                paas.post("/patient/orders/" + requiredId(required(request, "orderId")) + "/pay", token(session), null);
                session.setAttribute("flash", "模拟缴费成功");
            } else if ("/hospital/department/create".equals(path)) {
                paas.post("/hospital/departments", token(session), new DepartmentRequest(required(request, "name"), null));
                session.setAttribute("flash", "科室已创建");
            } else if ("/hospital/department/edit".equals(path)) {
                paas.put("/hospital/departments/" + requiredId(required(request, "id")), token(session),
                        new DepartmentRequest(required(request, "name"), required(request, "status")));
                session.setAttribute("flash", "科室已更新");
            } else if ("/hospital/doctor/create".equals(path)) {
                result = paas.post("/hospital/doctors", token(session), new DoctorRequest(required(request, "username"),
                        required(request, "displayName"), requiredId(required(request, "departmentId")), null));
                session.setAttribute("initialPassword", result.path("initialPassword").asText());
                session.setAttribute("flash", "医生账号已创建，请记录一次性初始密码");
            } else if ("/hospital/doctor/edit".equals(path)) {
                paas.put("/hospital/doctors/" + requiredId(required(request, "id")), token(session),
                        new DoctorRequest(null, required(request, "displayName"),
                                requiredId(required(request, "departmentId")), required(request, "status")));
                session.setAttribute("flash", "医生已更新");
            } else if ("/hospital/slot/create".equals(path)) {
                paas.post("/hospital/slots", token(session), slotRequest(request, false));
                session.setAttribute("flash", "号源已发布");
            } else if ("/hospital/slot/edit".equals(path)) {
                paas.put("/hospital/slots/" + requiredId(required(request, "id")), token(session), slotRequest(request, true));
                session.setAttribute("flash", "号源已更新");
            } else if ("/hospital/name".equals(path)) {
                paas.put("/hospital/name", token(session), new HospitalNameRequest(required(request, "name")));
                session.setAttribute("flash", "医院名称已更新");
            } else if ("/doctor/profile".equals(path)) {
                paas.put("/doctor/profile", token(session),
                        new DoctorProfileRequest(parameter(request, "title"), parameter(request, "bio")));
                session.setAttribute("flash", "个人资料已更新");
            } else {
                response.sendError(404);
                return;
            }
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            if (unauthorized(error)) {
                authorizationExpired(request, response, session);
                return;
            }
            session.removeAttribute("initialPassword");
            session.setAttribute("flash", error.getMessage());
            if ("/patient/book".equals(path)) {
                String filters = patientFilters(parameter(request, "tenantId"), parameter(request, "departmentId"),
                        parameter(request, "doctorId"), parameter(request, "date"));
                redirectHome = home + (filters.isEmpty() ? "" : "?" + filters) + "#slot-search";
            }
        }
        response.sendRedirect(request.getContextPath() + redirectHome);
    }

    private void login(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            LoginResponse login = paas.login(new LoginRequest(request.getParameter("username"),
                    request.getParameter("password")));
            HttpSession old = request.getSession(false);
            if (old != null) old.invalidate();
            HttpSession session = request.getSession(true);
            session.setAttribute("accessToken", login.getAccessToken());
            session.setAttribute("username", login.getUsername());
            session.setAttribute("role", login.getRole());
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            session.setAttribute("csrfToken", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            response.sendRedirect(request.getContextPath() + factory.forRole(login.getRole()).getHomePath());
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            request.setAttribute("error", WebEscaper.html(error.getMessage()));
            show(request, response, "login.jsp");
        }
    }

    private void register(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("csrfToken") == null
                || !session.getAttribute("csrfToken").equals(request.getParameter("csrfToken"))) {
            response.sendError(403);
            return;
        }
        try {
            String username = request.getParameter("username");
            String password = request.getParameter("password");
            String confirmation = request.getParameter("confirmPassword");
            if (username == null || !username.matches("[A-Za-z0-9_]{3,64}") || password == null
                    || password.getBytes(StandardCharsets.UTF_8).length < 8
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("账号须为 3–64 位字母、数字或下划线，密码须为 8–72 个 UTF-8 字节");
            }
            if (!password.equals(confirmation)) {
                throw new IllegalArgumentException("两次输入的密码不一致");
            }
            paas.post("/auth/register", null, new RegisterRequest(username, password));
            session.setAttribute("flash", "注册成功，请登录");
            response.sendRedirect(request.getContextPath() + "/login");
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            request.setAttribute("error", WebEscaper.html(error.getMessage()));
            request.setAttribute("csrfToken", session.getAttribute("csrfToken"));
            show(request, response, "register.jsp");
        }
    }

    private static String csrfToken(HttpSession session) {
        Object existing = session.getAttribute("csrfToken");
        if (existing != null) return String.valueOf(existing);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute("csrfToken", token);
        return token;
    }

    private static String required(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("请填写完整信息");
        return value.trim();
    }

    private static SlotRequest slotRequest(HttpServletRequest request, boolean edit) {
        try {
            int capacity = Integer.parseInt(required(request, "capacity"));
            BigDecimal fee = new BigDecimal(required(request, "fee"));
            Date date = Date.valueOf(required(request, "date"));
            Time start = Time.valueOf(clock(required(request, "startTime")));
            Time end = Time.valueOf(clock(required(request, "endTime")));
            return new SlotRequest(requiredId(required(request, "departmentId")),
                    requiredId(required(request, "doctorId")), date, start, end, capacity, fee,
                    edit ? required(request, "status") : null);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("号源信息格式不正确");
        }
    }

    private static String clock(String value) { return value.matches("[0-9]{2}:[0-9]{2}") ? value + ":00" : value; }

    private static String requiredId(String value) {
        if (value == null || !UUID_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("请输入有效编号");
        }
        return value;
    }

    private static HttpSession requireRole(HttpServletRequest request, HttpServletResponse response, Role role)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("role") == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return null;
        }
        if (session.getAttribute("role") != role) {
            response.sendError(403);
            return null;
        }
        return session;
    }

    private static Role roleForPath(String path) {
        if (path.startsWith("/patient")) return Role.PATIENT;
        if (path.startsWith("/doctor")) return Role.DOCTOR;
        if ("/hospital".equals(path) || path.startsWith("/hospital/")) return Role.HOSPITAL_ADMIN;
        if ("/platform".equals(path) || path.startsWith("/platform/")) return Role.PLATFORM_ADMIN;
        return null;
    }

    private static String requestPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    private static String token(HttpSession session) {
        return String.valueOf(session.getAttribute("accessToken"));
    }

    private void loadFilteredSection(HttpServletRequest request, String token, WorkspaceAccess access,
            String attribute, String path, boolean withStatus) throws IOException {
        String from = parameter(request, "from"); String to = parameter(request, "to"); String status = parameter(request, "status");
        request.setAttribute("from", WebEscaper.html(from)); request.setAttribute("to", WebEscaper.html(to));
        if (withStatus) request.setAttribute("status", WebEscaper.html(status));
        try {
            StringBuilder query = new StringBuilder(path);
            if (withStatus && !status.isEmpty()) {
                if (!"PAID".equals(status) && !"UNPAID".equals(status)) throw new IllegalArgumentException("缴费状态无效");
                query.append("?status=").append(status);
            }
            for (String key : new String[] {"from", "to"}) {
                String value = parameter(request, key);
                if (!value.isEmpty()) {
                    StatisticsWorkspace.date(value);
                    query.append(query.indexOf("?") < 0 ? "?" : "&").append(key).append('=').append(encode(value));
                }
            }
            if (!from.isEmpty() && !to.isEmpty() && StatisticsWorkspace.date(from).isAfter(StatisticsWorkspace.date(to))) {
                throw new IllegalArgumentException("起始日期不得晚于结束日期");
            }
            int page = pageNumber(request, "page", 1, Integer.MAX_VALUE);
            int size = pageNumber(request, "size", 20, 100);
            access.loadPage(request, token, attribute, query.toString(), page, size);
        } catch (IllegalArgumentException failure) { WorkspaceAccess.sectionError(request, attribute + "Error", failure); }
    }

    private static boolean unauthorized(RuntimeException failure) {
        return failure instanceof PaasRejectedException && ((PaasRejectedException) failure).getStatus() == 401;
    }

    private void authorizationExpired(HttpServletRequest request, HttpServletResponse response, HttpSession session)
            throws ServletException, IOException {
        new WorkspaceAccess(paas).clearPrivateAttributes(request);
        session.removeAttribute("flash"); session.removeAttribute("initialPassword"); session.invalidate();
        request.setAttribute("error", "登录状态已失效，请重新登录");
        show(request, response, "login.jsp");
    }

    private static void show(HttpServletRequest request, HttpServletResponse response, String view)
            throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        request.getRequestDispatcher("/WEB-INF/views/" + view).forward(request, response);
    }
}
