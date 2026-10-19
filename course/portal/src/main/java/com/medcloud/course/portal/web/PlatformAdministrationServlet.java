package com.medcloud.course.portal.web;

import com.medcloud.course.contract.HospitalAdminCreationView;
import com.medcloud.course.contract.HospitalAdministrationRules;
import com.medcloud.course.contract.Role;
import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

@WebServlet(urlPatterns={"/platform/hospital/open", "/platform/hospital/administrators", "/platform/hospital/administrator/create",
        "/platform/hospital/administrator/status", "/platform/hospital/result"})
public final class PlatformAdministrationServlet extends HttpServlet {
    private static final String RESULTS = "platformAdministrationResults";
    private PaasClient paas;
    public PlatformAdministrationServlet() { }
    public PlatformAdministrationServlet(PaasClient paas) { this.paas = paas; }
    @Override public void init() {
        if (paas != null) return;
        String url = getServletConfig().getInitParameter("paasBaseUrl");
        if (url == null || url.trim().isEmpty()) url = System.getenv("MEDICAL_COURSE_PAAS_URL");
        paas = new PaasClient(url == null || url.trim().isEmpty() ? "http://127.0.0.1:8080/paas/api" : url);
    }
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws ServletException,IOException {
        request.setCharacterEncoding("UTF-8");noStore(response);
        HttpSession session = authorize(request,response);if(session == null)return;
        String path = request.getServletPath();
        if(path.equals("/platform/hospital/administrator/status")){response.sendError(405);return;}
        if(!known(path)){response.sendError(404);return;}
        prepare(request,session);
        try {
            new PlatformAdministrationWorkspace(paas).load(request,token(session));
            if(path.equals("/platform/hospital/result")) {
                String key = PlatformAdministrationWorkspace.single(request,"resultKey");
                HospitalAdminCreationView result = result(session,key);
                if(result != null) {
                    request.setAttribute("creationResult",result);
                    request.setAttribute("resultTenantId",result.getTenantId());
                    request.setAttribute("resultHospitalName",result.getHospitalName());
                    request.setAttribute("resultAdministratorId",result.getAdministratorId());
                    request.setAttribute("resultUsername",result.getUsername());
                }
            }
        } catch(RuntimeException failure) {
            if(expired(failure,session,request,response))return;
            response.setStatus(status(failure));request.setAttribute("error",WebEscaper.html(safe(failure)));
        }
        show(request,response,view(path));
    }
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response)throws ServletException,IOException {
        request.setCharacterEncoding("UTF-8");noStore(response);
        HttpSession session=authorize(request,response);if(session==null)return;
        String[] csrf=request.getParameterValues("csrfToken");
        if(!(session.getAttribute("csrfToken") instanceof String) || csrf==null || csrf.length!=1
                || !session.getAttribute("csrfToken").equals(csrf[0])){response.sendError(403);return;}
        String path=request.getServletPath();
        if(!path.equals("/platform/hospital/open") && !path.equals("/platform/hospital/administrator/create")
                && !path.equals("/platform/hospital/administrator/status")){response.sendError(405);return;}
        prepare(request,session);retain(request);
        try {
            for(String[] values:request.getParameterMap().values())if(values==null || values.length!=1)throw new IllegalArgumentException("duplicate input");
            PlatformAdministrationWorkspace workspace=new PlatformAdministrationWorkspace(paas);
            String destination;
            if(path.equals("/platform/hospital/administrator/status")) {
                workspace.changeStatus(request,token(session));
                destination="/platform/hospital/administrators?tenantId="+PlatformAdministrationWorkspace.query(
                        HospitalAdministrationRules.requireTargetId(PlatformAdministrationWorkspace.single(request,"tenantId")));
            } else {
                HospitalAdminCreationView created=path.endsWith("/open")?workspace.open(request,token(session)):workspace.create(request,token(session));
                destination="/platform/hospital/result?resultKey="+remember(session,created);
            }
            response.setStatus(303);response.setHeader("Location",request.getContextPath()+destination);
        } catch(RuntimeException failure) {
            if(expired(failure,session,request,response))return;
            response.setStatus(status(failure));request.setAttribute("error",WebEscaper.html(safe(failure)));
            // Never retain a password, confirmation, request, exception or unverified result in a session.
            show(request,response,view(path));
        }
    }
    private static void retain(HttpServletRequest request) {
        for(String key:new String[]{"hospitalName","username","operationKey","tenantId","adminId","status","expectedStatus"}) {
            String[] values=request.getParameterValues(key);
            if(values!=null && values.length==1)request.setAttribute(key,values[0]);
        }
        request.removeAttribute("initialPassword");request.removeAttribute("confirmInitialPassword");
    }
    private static void prepare(HttpServletRequest request,HttpSession session) {
        request.setAttribute("csrfToken",session.getAttribute("csrfToken"));
        request.setAttribute("username","");
        request.setAttribute("portalTitle","医院开通与管理员管理");
    }
    private static HttpSession authorize(HttpServletRequest request,HttpServletResponse response)throws IOException {
        HttpSession session=request.getSession(false);
        if(session==null || session.getAttribute("role")==null){response.sendRedirect(request.getContextPath()+"/login");return null;}
        if(session.getAttribute("role")!=Role.PLATFORM_ADMIN){response.sendError(403);return null;}
        if(!(session.getAttribute("accessToken") instanceof String) || token(session).isEmpty()){session.invalidate();response.sendRedirect(request.getContextPath()+"/login");return null;}
        return session;
    }
    private static boolean expired(RuntimeException failure,HttpSession session,HttpServletRequest request,HttpServletResponse response)throws IOException {
        if(failure instanceof PaasRejectedException && ((PaasRejectedException)failure).getStatus()==401){
            session.invalidate();response.sendRedirect(request.getContextPath()+"/login");return true;
        }return false;
    }
    private static String token(HttpSession session){return (String)session.getAttribute("accessToken");}
    @SuppressWarnings("unchecked") private static String remember(HttpSession session,HospitalAdminCreationView created) {
        String key=UUID.randomUUID().toString();
        synchronized(session) {
            Object existing=session.getAttribute(RESULTS);
            LinkedHashMap<String,HospitalAdminCreationView> results=existing instanceof LinkedHashMap
                    ?(LinkedHashMap<String,HospitalAdminCreationView>)existing:new LinkedHashMap<String,HospitalAdminCreationView>();
            results.put(key,created);
            while(results.size()>10)results.remove(results.keySet().iterator().next());
            session.setAttribute(RESULTS,results);
        }return key;
    }
    private static HospitalAdminCreationView result(HttpSession session,String key) {
        if(key==null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return null;
        synchronized(session) {
            Object existing=session.getAttribute(RESULTS);
            if(!(existing instanceof Map))return null;
            Object found=((Map<?,?>)existing).get(key);
            return found instanceof HospitalAdminCreationView?(HospitalAdminCreationView)found:null;
        }
    }
    private static int status(RuntimeException failure){return failure instanceof IllegalArgumentException?400:failure instanceof PaasRejectedException?((PaasRejectedException)failure).getStatus():503;}
    private static String safe(RuntimeException failure) {
        if(failure instanceof IllegalArgumentException)return "提交信息无效，请检查必填项、重复参数、用户名、密码确认和操作编号";
        if(failure instanceof PaasRejectedException)return failure.getMessage();
        return "服务暂不可用，结果可能尚未确认。请保留相同操作编号，输入原密码后手动重试；不要换编号或自动重发。";
    }
    private static boolean known(String path){return path.equals("/platform/hospital/open") || path.equals("/platform/hospital/administrators")
            || path.equals("/platform/hospital/administrator/create") || path.equals("/platform/hospital/result");}
    private static String view(String path){return path.endsWith("/open")?"platform-hospital-open.jsp":path.endsWith("/create")?"platform-administrator-create.jsp":path.endsWith("/result")?"platform-administration-result.jsp":"platform-administrators.jsp";}
    private static void noStore(HttpServletResponse response){response.setCharacterEncoding("UTF-8");response.setContentType("text/html; charset=UTF-8");response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
    private static void show(HttpServletRequest request,HttpServletResponse response,String view)throws ServletException,IOException{noStore(response);request.getRequestDispatcher("/WEB-INF/views/"+view).forward(request,response);}
}
