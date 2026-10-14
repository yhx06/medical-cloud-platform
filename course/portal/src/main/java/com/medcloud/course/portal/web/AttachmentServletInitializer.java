package com.medcloud.course.portal.web;

import javax.servlet.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Invalid attachment configuration degrades only the attachment servlet. */
public final class AttachmentServletInitializer implements ServletContainerInitializer {
    @Override public void onStartup(Set<Class<?>> classes,ServletContext context)throws ServletException {
        AttachmentServlet servlet;Path temp=null;long max=10L*1024*1024;
        try {
            if(!"true".equals(config(context,"COURSE_ATTACHMENTS_ENABLED","false")))throw new IllegalArgumentException();
            String bytes=config(context,"COURSE_ATTACHMENTS_MAX_BYTES","10485760");
            if(!bytes.matches("[0-9]+"))throw new IllegalArgumentException();max=Long.parseLong(bytes);
            if(max<1||max>Long.MAX_VALUE-65536)throw new IllegalArgumentException();
            String dir=config(context,"COURSE_ATTACHMENTS_TEMP_DIR","");
            String originsValue=config(context,"COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS","");
            String parallel=config(context,"COURSE_ATTACHMENTS_CONCURRENCY","4");
            if(!parallel.matches("[0-9]+"))throw new IllegalArgumentException();
            int concurrency=Integer.parseInt(parallel);if(concurrency<1)throw new IllegalArgumentException();
            if(dir.isEmpty()||originsValue.isEmpty())throw new IllegalArgumentException();
            temp=Paths.get(dir);if(!temp.isAbsolute())throw new IllegalArgumentException();
            Files.createDirectories(temp);temp=temp.toRealPath();
            String webRoot=context.getRealPath("/");if(webRoot!=null&&temp.startsWith(Paths.get(webRoot).toRealPath()))throw new IllegalArgumentException();
            if(!Files.isWritable(temp))throw new IllegalArgumentException();
            Set<String> origins=new HashSet<>();for(String entry:originsValue.split(",",-1)) {
                String origin=entry.trim();URI uri=new URI(origin);
                if(uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null
                        ||!(uri.getRawPath().isEmpty()||"/".equals(uri.getRawPath())))throw new IllegalArgumentException();
                AttachmentDownloadPolicy.requireAllowed(origin,Collections.singleton(origin));origins.add(origin);
            }
            servlet=new AttachmentServlet(new PaasClient(config(context,"MEDICAL_COURSE_PAAS_URL","http://127.0.0.1:8080/paas/api")),temp,max,origins,120,120,concurrency);
        }catch(Exception invalid){servlet=new AttachmentServlet();temp=null;context.log("Attachment module unavailable: invalid configuration");}
        ServletRegistration.Dynamic registration=context.addServlet("attachmentServlet",servlet);
        if(registration==null)throw new ServletException("attachment servlet name already registered");
        registration.addMapping("/patient/files","/doctor/files","/patient/files/upload","/doctor/files/upload","/patient/files/delete","/files/download","/files/upload-status");
        registration.setLoadOnStartup(1);
        if(temp!=null)registration.setMultipartConfig(new MultipartConfigElement(temp.toString(),max,max+65536,0));
    }
    private static String config(ServletContext context,String name,String fallback){String value=context.getInitParameter(name);if(value==null||value.trim().isEmpty())value=System.getenv(name);return value==null||value.trim().isEmpty()?fallback:value.trim();}
}
