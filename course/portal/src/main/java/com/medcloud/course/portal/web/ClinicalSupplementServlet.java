package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.*;
import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;

@WebServlet(urlPatterns={"/patient/complaint", "/doctor/clinical", "/doctor/record-details", "/patient/record-details", "/doctor/correction",
        "/patient/complaint/save", "/doctor/clinical/save", "/doctor/clinical/complete", "/doctor/correction/append"})
public final class ClinicalSupplementServlet extends HttpServlet {
    private PaasClient paas;
    public ClinicalSupplementServlet() { }
    public ClinicalSupplementServlet(PaasClient paas) { this.paas = paas; }
    @Override public void init() {
        if (paas != null) return;
        String url = getServletConfig().getInitParameter("paasBaseUrl");
        if (url == null || url.trim().isEmpty()) url = System.getenv("MEDICAL_COURSE_PAAS_URL");
        paas = new PaasClient(url == null || url.trim().isEmpty() ? "http://127.0.0.1:8080/paas/api" : url);
    }
    @Override protected void doGet(HttpServletRequest r, HttpServletResponse s) throws ServletException, IOException {
        HttpSession session = authorize(r,s); if (session == null) return;
        prepare(r,session);
        try { new ClinicalSupplementWorkspace(paas).load(r, token(session), (Role)session.getAttribute("role"), target(r)); }
        catch (RuntimeException e) { if (expired(e,session,r,s)) return; s.setStatus(e instanceof IllegalArgumentException ? 400 : e instanceof PaasRejectedException ? ((PaasRejectedException)e).getStatus() : 503);r.setAttribute("clinicalError", safe(e)); }
        show(r,s,view(r.getServletPath()));
    }
    @Override protected void doPost(HttpServletRequest r, HttpServletResponse s) throws ServletException, IOException {
        r.setCharacterEncoding("UTF-8"); HttpSession session = authorize(r,s); if (session == null) return;
        String[] csrf=r.getParameterValues("csrfToken");
        if (!(session.getAttribute("csrfToken") instanceof String) || csrf==null || csrf.length!=1 || !session.getAttribute("csrfToken").equals(csrf[0])) { s.sendError(403); return; }
        String path=r.getServletPath();
        if (!path.equals("/patient/complaint/save") && !path.equals("/doctor/clinical/save") && !path.equals("/doctor/clinical/complete") && !path.equals("/doctor/correction/append")) { s.sendError(405); return; }
        prepare(r,session); retain(r);
        try {
            String id=target(r); r.setAttribute("targetId",id); long revision=revision(r);
            String redirect;
            if (path.equals("/patient/complaint/save")) {
                SupplementComplaintRequest body=new SupplementComplaintRequest();body.setDescription(text(r,"description",500,false));body.setExpectedRevision(revision);
                paas.put("/patient/appointments/"+id+"/complaint",token(session),body); redirect="/patient/complaint?appointmentId="+id;
            } else if (path.equals("/doctor/correction/append")) {
                AppendCorrectionRequest body=new AppendCorrectionRequest();body.setExpectedRevision(revision);body.setOperationKey(ClinicalSupplementWorkspace.id(single(r,"operationKey")));body.setAfter(content(r,true));body.setReason(text(r,"reason",2000,true));
                paas.post("/doctor/records/"+id+"/corrections",token(session),body); redirect="/doctor/record-details?recordId="+id;
            } else {
                ClinicalContentRequest content=content(r,path.endsWith("/complete"));
                if ("1".equals(single(r,"copyPatientComplaint")) && path.endsWith("/save")) {
                    JsonNode current=paas.get("/doctor/appointments/"+id+"/clinical-workspace",token(session));
                    String description=current.path("patientComplaint").path("description").asText("");
                    validate(description,500,false);content.setComplaint(description);r.setAttribute("complaint",description);
                }
                if (path.endsWith("/save")) {
                    SaveClinicalDraftRequest body=new SaveClinicalDraftRequest();body.setComplaint(content.getComplaint());body.setDiagnosis(content.getDiagnosis());body.setAdvice(content.getAdvice());body.setNotes(content.getNotes());body.setExpectedRevision(revision);
                    paas.put("/doctor/appointments/"+id+"/draft",token(session),body);redirect="/doctor/clinical?appointmentId="+id;
                } else {
                    CompleteConsultationRequest body=new CompleteConsultationRequest(content.getDiagnosis(),content.getAdvice());body.setComplaint(content.getComplaint());body.setNotes(content.getNotes());body.setExpectedDraftRevision(revision);
                    paas.post("/doctor/appointments/"+id+"/complete",token(session),body);redirect="/doctor";
                }
            }
            s.setStatus(303);s.setHeader("Location",r.getContextPath()+redirect);s.setHeader("Cache-Control","no-store");
        } catch (RuntimeException e) {
            if (expired(e,session,r,s)) return;
            int status=e instanceof IllegalArgumentException?400:e instanceof PaasRejectedException?((PaasRejectedException)e).getStatus():503;
            s.setStatus(status);r.setAttribute("clinicalError",safe(e));
            // Preserve this request's exact inputs, version and retry key. No implicit rebase or session flash.
            r.setAttribute("clinicalWritable",status==400 || status==409 || status==503);
            show(r,s,view(path));
        }
    }
    private static ClinicalContentRequest content(HttpServletRequest r,boolean required) {
        ClinicalContentRequest c=new ClinicalContentRequest();c.setComplaint(text(r,"complaint",500,false));c.setDiagnosis(text(r,"diagnosis",500,required));c.setAdvice(text(r,"advice",2000,required));c.setNotes(text(r,"notes",2000,false));return c;
    }
    private static String text(HttpServletRequest r,String name,int max,boolean required) {String value=single(r,name);validate(value,max,required);return value==null || value.isEmpty()?null:value;}
    private static void validate(String value,int max,boolean required) {
        if (required && (value==null || value.trim().isEmpty())) throw new IllegalArgumentException("请填写诊断、建议及更正原因等必填内容");
        if (value==null) return;
        if (value.codePointCount(0,value.length())>max) throw new IllegalArgumentException("正文超出允许的Unicode码点长度");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c==0)throw new IllegalArgumentException("正文含无效字符");if(Character.isHighSurrogate(c)){if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i)))throw new IllegalArgumentException("正文含无效字符");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("正文含无效字符");}
    }
    private static long revision(HttpServletRequest r) {try{String v=single(r,"expectedRevision");if(v!=null && v.matches("[0-9]+")){long n=Long.parseLong(v);if(n>=0)return n;}}catch(NumberFormatException ignored){}throw new IllegalArgumentException("请保留原期望版本");}
    private static String single(HttpServletRequest r,String key) {String[] v=r.getParameterValues(key);if(v==null)return null;if(v.length!=1)throw new IllegalArgumentException("重复参数无效");return v[0];}
    private static String target(HttpServletRequest r) {return ClinicalSupplementWorkspace.id(single(r,r.getServletPath().contains("correction") || r.getServletPath().equals("/doctor/record-details")?"recordId":"appointmentId"));}
    private static void retain(HttpServletRequest r) {for(String key:new String[]{"description","complaint","diagnosis","advice","notes","reason","operationKey","expectedRevision"})r.setAttribute(key,r.getParameter(key));}
    private static void prepare(HttpServletRequest r,HttpSession session) {r.setAttribute("clinicalHome",session.getAttribute("role")==Role.PATIENT?"/patient":"/doctor");r.setAttribute("csrfToken",session.getAttribute("csrfToken"));r.setAttribute("clinicalWritable",false);}
    private static HttpSession authorize(HttpServletRequest r,HttpServletResponse s)throws IOException {
        HttpSession session=r.getSession(false);if(session==null || !(session.getAttribute("role") instanceof Role)){s.sendRedirect(r.getContextPath()+"/login");return null;}
        Object role=session.getAttribute("role");if((role!=Role.PATIENT && role!=Role.DOCTOR) || (r.getServletPath().startsWith("/patient/") && role!=Role.PATIENT) || (r.getServletPath().startsWith("/doctor/") && role!=Role.DOCTOR)){s.sendError(403);return null;}
        if(!(session.getAttribute("accessToken") instanceof String) || token(session).isEmpty()){session.invalidate();s.sendRedirect(r.getContextPath()+"/login");return null;}return session;
    }
    private static boolean expired(RuntimeException e,HttpSession session,HttpServletRequest r,HttpServletResponse s)throws IOException {if(e instanceof PaasRejectedException && ((PaasRejectedException)e).getStatus()==401){session.invalidate();s.sendRedirect(r.getContextPath()+"/login");return true;}return false;}
    private static String token(HttpSession session) {return (String)session.getAttribute("accessToken");}
    private static String safe(RuntimeException e) {return e instanceof IllegalArgumentException?"输入无效，请检查编号、版本、必填项及正文长度":e instanceof PaasRejectedException && ((PaasRejectedException)e).getStatus()==409?"版本或状态冲突，当前输入已保留。请另开详情核对；不要换键重发或直接覆盖最新内容。":e instanceof PaasRejectedException && ((PaasRejectedException)e).getStatus()==403?"当前账号或医院状态不允许此操作，请返回查阅历史":"诊疗服务暂不可用，当前输入已保留；更正重试请保留原操作编号";}
    private static String view(String path) {return path.contains("complaint")?"patient-complaint.jsp":path.contains("correction")?"clinical-correction.jsp":path.contains("record-details")?"clinical-record-details.jsp":"clinical-workspace.jsp";}
    private static void show(HttpServletRequest r,HttpServletResponse s,String view)throws ServletException,IOException {s.setCharacterEncoding("UTF-8");s.setContentType("text/html; charset=UTF-8");s.setHeader("Cache-Control","no-store");s.setHeader("Referrer-Policy","no-referrer");r.getRequestDispatcher("/WEB-INF/views/"+view).forward(r,s);}
}
