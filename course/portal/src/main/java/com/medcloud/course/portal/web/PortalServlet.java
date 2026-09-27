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
        "/doctor", "/doctor/start", "/doctor/complete", "/hospital", "/hospital/department/create",
        "/hospital/department/edit", "/hospital/doctor/create", "/hospital/doctor/edit",
        "/hospital/slot/create", "/hospital/slot/edit", "/platform"})
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
        String path = request.getServletPath();
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
            if ("/patient".equals(path)) {
                String tenantId = request.getParameter("tenantId");
                request.setAttribute("tenantId", WebEscaper.html(tenantId));
                request.setAttribute("departmentId", WebEscaper.html(request.getParameter("departmentId")));
                request.setAttribute("doctorId", WebEscaper.html(request.getParameter("doctorId")));
                request.setAttribute("date", WebEscaper.html(request.getParameter("date")));
                if (tenantId != null && !tenantId.trim().isEmpty()) {
                    String query = "tenantId=" + URLEncoder.encode(tenantId, StandardCharsets.UTF_8.name());
                    for (String key : new String[] {"departmentId", "doctorId", "date"}) {
                        String value = request.getParameter(key);
                        if (value != null && !value.trim().isEmpty())
                            query += "&" + key + "=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name());
                    }
                    request.setAttribute("slots", paas.get("/catalog/slots?" + query, token(session)));
                }
                String appointmentId = request.getParameter("appointmentId");
                request.setAttribute("appointmentId", WebEscaper.html(appointmentId));
                if (appointmentId != null && !appointmentId.trim().isEmpty()) {
                    String id = requiredId(appointmentId);
                    request.setAttribute("record", paas.get("/patient/appointments/" + id + "/record", token(session)));
                    request.setAttribute("order", paas.get("/patient/appointments/" + id + "/order", token(session)));
                }
                request.setAttribute("appointments", paas.get("/patient/appointments", token(session)));
            } else if ("/doctor".equals(path)) {
                request.setAttribute("appointments", paas.get("/doctor/appointments", token(session)));
                String from = request.getParameter("from");
                String to = request.getParameter("to");
                StringBuilder recordsPath = new StringBuilder("/doctor/records");
                if (from != null && !from.trim().isEmpty()) {
                    Date.valueOf(from);
                    recordsPath.append("?from=").append(URLEncoder.encode(from, "UTF-8"));
                }
                if (to != null && !to.trim().isEmpty()) {
                    Date.valueOf(to);
                    recordsPath.append(recordsPath.indexOf("?") < 0 ? "?" : "&")
                            .append("to=").append(URLEncoder.encode(to, "UTF-8"));
                }
                request.setAttribute("from", WebEscaper.html(from));
                request.setAttribute("to", WebEscaper.html(to));
                request.setAttribute("records", paas.get(recordsPath.toString(), token(session)));
            } else if ("/hospital".equals(path)) {
                request.setAttribute("departments", paas.get("/hospital/departments", token(session)));
                request.setAttribute("doctors", paas.get("/hospital/doctors", token(session)));
                request.setAttribute("slots", paas.get("/hospital/slots", token(session)));
                String status = request.getParameter("status");
                String from = request.getParameter("from");
                String to = request.getParameter("to");
                StringBuilder ordersPath = new StringBuilder("/hospital/orders");
                if (status != null && !status.trim().isEmpty()) {
                    if (!"PAID".equals(status) && !"UNPAID".equals(status))
                        throw new IllegalArgumentException("缴费状态无效");
                    ordersPath.append("?status=").append(URLEncoder.encode(status, "UTF-8"));
                }
                if (from != null && !from.trim().isEmpty()) {
                    Date.valueOf(from);
                    ordersPath.append(ordersPath.indexOf("?") < 0 ? "?" : "&")
                            .append("from=").append(URLEncoder.encode(from, "UTF-8"));
                }
                if (to != null && !to.trim().isEmpty()) {
                    Date.valueOf(to);
                    ordersPath.append(ordersPath.indexOf("?") < 0 ? "?" : "&")
                            .append("to=").append(URLEncoder.encode(to, "UTF-8"));
                }
                request.setAttribute("status", WebEscaper.html(status));
                request.setAttribute("from", WebEscaper.html(from));
                request.setAttribute("to", WebEscaper.html(to));
                request.setAttribute("orders", paas.get(ordersPath.toString(), token(session)));
            }
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            request.setAttribute("error", WebEscaper.html(error.getMessage()));
        }
        show(request, response, path.substring(1) + ".jsp");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        String path = request.getServletPath();
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
        if (!String.valueOf(session.getAttribute("csrfToken")).equals(request.getParameter("csrfToken"))) {
            response.sendError(403);
            return;
        }
        String home = factory.forRole(expected).getHomePath();
        try {
            JsonNode result;
            if ("/patient/book".equals(path)) {
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
            } else {
                response.sendError(404);
                return;
            }
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException error) {
            session.setAttribute("flash", error.getMessage());
        }
        response.sendRedirect(request.getContextPath() + home);
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
        if ("/platform".equals(path)) return Role.PLATFORM_ADMIN;
        return null;
    }

    private static String token(HttpSession session) {
        return String.valueOf(session.getAttribute("accessToken"));
    }

    private static void show(HttpServletRequest request, HttpServletResponse response, String view)
            throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        request.getRequestDispatcher("/WEB-INF/views/" + view).forward(request, response);
    }
}
