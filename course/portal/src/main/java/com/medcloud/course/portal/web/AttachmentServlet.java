package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Independent exact mappings keep failures isolated from the core consultation portal. */
public final class AttachmentServlet extends HttpServlet {
    private PaasClient paas;private Path temp;private long maxBytes;private Set<String> origins;private boolean available;
    private final Semaphore processing;
    private int receiveSeconds=120,transferSeconds=120;
    public AttachmentServlet(){processing=new Semaphore(4);}
    public AttachmentServlet(PaasClient paas,Path temp,long maxBytes,Set<String> origins){this(paas,temp,maxBytes,origins,120,120,4);}
    public AttachmentServlet(PaasClient paas,Path temp,long maxBytes,Set<String> origins,int receiveSeconds,int transferSeconds,int concurrency){
        if(paas==null||temp==null||maxBytes<1||maxBytes>Long.MAX_VALUE-65536||origins==null||origins.isEmpty()||receiveSeconds<1||transferSeconds<1||concurrency<1)throw new IllegalArgumentException("invalid attachment configuration");
        this.paas=paas;this.temp=temp;this.maxBytes=maxBytes;this.origins=new HashSet<>(origins);this.receiveSeconds=receiveSeconds;this.transferSeconds=transferSeconds;this.processing=new Semaphore(concurrency);this.available=true;
    }
    @Override public void init(){if(paas==null)available=false;}
    @Override protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException,ServletException {
        HttpSession session=authorize(r,s);if(session==null)return;
        Role role=(Role)session.getAttribute("role");String home=home(role);
        if(!(home+"/files").equals(r.getServletPath())){s.sendError(405);return;}
        prepareView(r,session,home);
        if(!available){r.setAttribute("attachmentError","附件服务尚未配置或配置无效，诊疗服务仍可使用");show(r,s,"attachments.jsp");return;}
        try {
            new AttachmentWorkspace(paas).load(r,token(session),role);
            Object target=r.getAttribute("targetId");
            synchronized(session) {
                String pending=(String)session.getAttribute("attachmentTargetId");
                if(pending!=null && !pending.equals(target))r.setAttribute("attachmentWritable",false);
                r.setAttribute("uploadKey",session.getAttribute("attachmentUploadKey")==null?UUID.randomUUID().toString():session.getAttribute("attachmentUploadKey"));r.setAttribute("uploadId",session.getAttribute("attachmentUploadId"));
                r.setAttribute("uploadState",session.getAttribute("attachmentUploadState"));
            }
        }catch(RuntimeException e){if(expired(e,session,s,r))return;r.setAttribute("attachmentError",safeMessage(e));}
        show(r,s,"attachments.jsp");
    }
    @Override protected void doPost(HttpServletRequest r,HttpServletResponse s)throws IOException,ServletException {
        HttpSession session=authorize(r,s);if(session==null)return;Role role=(Role)session.getAttribute("role");String home=home(role),path=r.getServletPath();
        r.setCharacterEncoding("UTF-8");
        if(!available){s.sendError(503,"附件服务尚未配置或配置无效");return;}
        boolean upload=(home+"/files/upload").equals(path);
        if(!upload && !"/files/download".equals(path) && !"/files/upload-status".equals(path) && !(role==Role.PATIENT&&"/patient/files/delete".equals(path))){s.sendError(404);return;}
        if(!upload && !csrf(r,session)){s.sendError(403);return;}
        if(!processing.tryAcquire()){s.sendError(429,"附件处理中，请稍后重试");return;}
        String redirect=home;boolean newUploadIntent=false;
        try {
            if(upload) {
                try(PreparedPortalFile file=new AttachmentMultipart().read(r,temp,maxBytes,receiveSeconds)) {
                    redirect=targetPath(home,file.getTargetId());
                    synchronized(session) {
                        Object key=session.getAttribute("attachmentUploadKey"),target=session.getAttribute("attachmentTargetId");
                        if(key!=null&&(!key.equals(file.getUploadKey())||!file.getTargetId().equals(target)))throw new IllegalArgumentException("请先确认原上传结果，重试须保留原上传编号");
                        newUploadIntent=key==null;
                        session.setAttribute("attachmentUploadKey",file.getUploadKey());session.setAttribute("attachmentTargetId",file.getTargetId());session.setAttribute("attachmentUploadState","PENDING");
                    }
                    JsonNode operation=paas.post(role==Role.PATIENT?"/patient/appointments/"+file.getTargetId()+"/files/uploads":"/doctor/records/"+file.getTargetId()+"/files/uploads",token(session),
                            new PrepareUploadRequest(file.getUploadKey(),file.getFileName(),file.getContentType(),file.getSizeBytes(),file.getSha256()));
                    String operationId=AttachmentWorkspace.id(operation.path("uploadId").asText());session.setAttribute("attachmentUploadId",operationId);
                    JsonNode result=paas.putBinary("/files/uploads/"+operationId+"/content",token(session),file.getPath(),maxBytes,transferSeconds);
                    status(session,result);session.setAttribute("attachmentNotice","AVAILABLE".equals(result.path("state").asText())?"附件上传完成":"附件处理中，请查询原上传结果");
                }
            } else if("/files/download".equals(path)) {
                AttachmentWorkspace.whitelist(r,"csrfToken","fileId");String fileId=AttachmentWorkspace.id(AttachmentWorkspace.optional(r,"fileId"));
                JsonNode result=paas.post("/files/"+fileId+"/download-url",token(session),null);
                String url=AttachmentDownloadPolicy.requireAllowed(result.path("url").asText(),origins).toASCIIString();
                prepareView(r,session,home);r.setAttribute("downloadUrl",url);r.setAttribute("downloadExpiresAt",result.path("expiresAt").asText());
                show(r,s,"attachment-download.jsp");return;
            } else if("/patient/files/delete".equals(path)) {
                AttachmentWorkspace.whitelist(r,"csrfToken","fileId","appointmentId");String file=AttachmentWorkspace.id(AttachmentWorkspace.optional(r,"fileId"));
                redirect=targetPath(home,AttachmentWorkspace.id(AttachmentWorkspace.optional(r,"appointmentId")));
                JsonNode result=paas.post("/patient/files/"+file+"/delete",token(session),null);
                session.setAttribute("attachmentNotice","DELETE_PENDING".equals(result.path("state").asText())?"已移出资料，存储清理待重试":"附件已移出资料");
            } else {
                AttachmentWorkspace.whitelist(r,"csrfToken");String op=AttachmentWorkspace.id((String)session.getAttribute("attachmentUploadId"));
                redirect=targetPath(home,AttachmentWorkspace.id((String)session.getAttribute("attachmentTargetId")));
                JsonNode result=paas.get("/files/uploads/"+op,token(session));status(session,result);
                session.setAttribute("attachmentNotice","AVAILABLE".equals(result.path("state").asText())?"原上传已完成":"原上传状态："+state(result.path("state").asText()));
            }
        } catch(AttachmentMultipart.CsrfRejected e){s.sendError(403);return;}
        catch(PaasUnavailableException e){session.setAttribute("attachmentNotice","结果待确认，请查询原上传结果；重试时保留原上传编号并重新选择同一文件");}
        catch(PaasRejectedException e){
            if(expired(e,session,s,r))return;
            // A known prepare rejection created no new operation; unknown outcomes retain the key.
            if(newUploadIntent && session.getAttribute("attachmentUploadId")==null && (e.getStatus()==400||e.getStatus()==403||e.getStatus()==413||e.getStatus()==415)){
                session.removeAttribute("attachmentUploadKey");session.removeAttribute("attachmentTargetId");session.removeAttribute("attachmentUploadState");
            }
            session.setAttribute("attachmentNotice",safeMessage(e));
        }
        catch(RuntimeException e){if(expired(e,session,s,r))return;session.setAttribute("attachmentNotice",safeMessage(e));}
        catch(ServletException | IOException e){session.setAttribute("attachmentNotice","附件接收失败或超出限额，请检查文件后重试");}
        finally{processing.release();}
        s.setStatus(303);s.setHeader("Location",r.getContextPath()+redirect);s.setHeader("Cache-Control","no-store");
    }
    private static void status(HttpSession session,JsonNode result){String state=result.path("state").asText();state(state);session.setAttribute("attachmentUploadState",state);
        if("AVAILABLE".equals(state)||"ABORTED".equals(state)||"DELETED".equals(state)||"DELETE_PENDING".equals(state))for(String field:new String[]{"attachmentUploadKey","attachmentUploadId","attachmentTargetId"})session.removeAttribute(field);
    }
    private static String state(String value){switch(value){case "RESERVED":return "已准备，请选择同一文件重试";case "UPLOADING":return "处理中";case "AVAILABLE":return "已完成";case "ABORTED":return "已终止，可重新上传";case "DELETE_PENDING":return "已移出资料，存储清理待重试";case "DELETED":return "已删除";default:throw new IllegalArgumentException("上传结果待确认");}}
    private void prepareView(HttpServletRequest r,HttpSession session,String home){r.setAttribute("attachmentHome",home);r.setAttribute("csrfToken",session.getAttribute("csrfToken"));r.setAttribute("attachmentMaxBytes",maxBytes);
        r.setAttribute("uploadId",session.getAttribute("attachmentUploadId"));
        Object notice=session.getAttribute("attachmentNotice");if(notice!=null){r.setAttribute("attachmentNotice",notice);session.removeAttribute("attachmentNotice");}}
    private static String targetPath(String home,String id){return home+"/files?"+("/patient".equals(home)?"appointmentId=":"recordId=")+AttachmentWorkspace.id(id);}
    private static boolean csrf(HttpServletRequest r,HttpSession session){String[] value=r.getParameterValues("csrfToken");return value!=null&&value.length==1&&session.getAttribute("csrfToken") instanceof String&&session.getAttribute("csrfToken").equals(value[0]);}
    private static String token(HttpSession session){return (String)session.getAttribute("accessToken");}
    private static String home(Role role){return role==Role.PATIENT?"/patient":"/doctor";}
    private static HttpSession authorize(HttpServletRequest r,HttpServletResponse s)throws IOException {
        HttpSession session=r.getSession(false);if(session==null || !(session.getAttribute("role") instanceof Role)){s.sendRedirect(r.getContextPath()+"/login");return null;}
        Object role=session.getAttribute("role");String path=r.getServletPath();
        if((role!=Role.PATIENT&&role!=Role.DOCTOR)||(path.startsWith("/patient/")&&role!=Role.PATIENT)||(path.startsWith("/doctor/")&&role!=Role.DOCTOR)){s.sendError(403);return null;}
        if(!(session.getAttribute("accessToken") instanceof String)||((String)session.getAttribute("accessToken")).isEmpty()){session.invalidate();s.sendRedirect(r.getContextPath()+"/login");return null;}return session;
    }
    private static boolean expired(RuntimeException e,HttpSession session,HttpServletResponse s,HttpServletRequest r)throws IOException {
        if(e instanceof PaasRejectedException&&((PaasRejectedException)e).getStatus()==401){session.invalidate();s.sendRedirect(r.getContextPath()+"/login");return true;}return false;
    }
    private static String safeMessage(RuntimeException e){
        if(e instanceof PaasRejectedException){switch(((PaasRejectedException)e).getStatus()){
            case 413:return "附件大小超出限制";
            case 415:return "附件类型不支持，仅接受 JPG、PNG、PDF";
            case 429:return "附件处理中，请等待现有上传完成后重试";
            case 503:return "附件存储暂时不可用，请稍后查询结果或重试";
            default:return e.getMessage();
        }}
        if(e instanceof IllegalArgumentException)return e.getMessage();return "附件服务暂不可用，请稍后重试";
    }
    private static void show(HttpServletRequest r,HttpServletResponse s,String view)throws ServletException,IOException{s.setCharacterEncoding("UTF-8");s.setContentType("text/html; charset=UTF-8");s.setHeader("Cache-Control","no-store");s.setHeader("Referrer-Policy","no-referrer");r.getRequestDispatcher("/WEB-INF/views/"+view).forward(r,s);}
}
