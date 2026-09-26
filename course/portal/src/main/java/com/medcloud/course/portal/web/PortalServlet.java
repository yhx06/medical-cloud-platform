package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.BookingRequest;
import com.medcloud.course.contract.CompleteConsultationRequest;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.RegisterRequest;
import com.medcloud.course.contract.Role;
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
import java.util.Base64;
import java.util.regex.Pattern;

@WebServlet(urlPatterns = {"/", "/login", "/register", "/logout", "/patient", "/patient/book", "/patient/pay",
        "/doctor", "/doctor/start", "/doctor/complete", "/hospital", "/platform"})
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
        try {
            if ("/patient".equals(path)) {
                String tenantId = request.getParameter("tenantId");
                request.setAttribute("tenantId", WebEscaper.html(tenantId));
                if (tenantId != null && !tenantId.trim().isEmpty()) {
                    String query = URLEncoder.encode(tenantId, StandardCharsets.UTF_8.name());
                    request.setAttribute("slots", paas.get("/catalog/slots?tenantId=" + query, token(session)));
                }
                String appointmentId = request.getParameter("appointmentId");
                request.setAttribute("appointmentId", WebEscaper.html(appointmentId));
                if (appointmentId != null && !appointmentId.trim().isEmpty()) {
                    String id = requiredId(appointmentId);
                    request.setAttribute("record", paas.get("/patient/appointments/" + id + "/record", token(session)));
                    request.setAttribute("order", paas.get("/patient/appointments/" + id + "/order", token(session)));
                }
            } else if ("/doctor".equals(path)) {
                request.setAttribute("appointments", paas.get("/doctor/appointments", token(session)));
            } else if ("/hospital".equals(path)) {
                String tenantId = request.getParameter("tenantId");
                request.setAttribute("tenantId", WebEscaper.html(tenantId));
                if (tenantId != null && !tenantId.trim().isEmpty()) {
                    request.setAttribute("slots", paas.get("/catalog/slots?tenantId="
                            + URLEncoder.encode(tenantId, StandardCharsets.UTF_8.name()), token(session)));
                }
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
            } else if ("/doctor/start".equals(path)) {
                paas.post("/doctor/appointments/" + requiredId(required(request, "appointmentId")) + "/start",
                        token(session), null);
                session.setAttribute("flash", "接诊已开始");
            } else if ("/doctor/complete".equals(path)) {
                paas.post("/doctor/appointments/" + requiredId(required(request, "appointmentId")) + "/complete",
                        token(session), new CompleteConsultationRequest(required(request, "diagnosis"),
                                required(request, "advice")));
                session.setAttribute("flash", "病历与账单已生成");
            } else if ("/patient/pay".equals(path)) {
                paas.post("/patient/orders/" + requiredId(required(request, "orderId")) + "/pay", token(session), null);
                session.setAttribute("flash", "模拟缴费成功");
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
        if ("/hospital".equals(path)) return Role.HOSPITAL_ADMIN;
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
