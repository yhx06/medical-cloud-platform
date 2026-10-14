package com.medcloud.course.portal.web;

import javax.servlet.ServletException;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Servlet container parsing limits and stream limits are both required. */
public final class AttachmentMultipart {
    public static final class CsrfRejected extends IllegalArgumentException {
        CsrfRejected(){super("表单校验失败，请刷新页面后重试");}
    }
    public PreparedPortalFile read(HttpServletRequest request, Path tempDirectory, long maxBytes, int deadlineSeconds)
            throws IOException, ServletException {
        long start=System.nanoTime();
        if(maxBytes<1 || maxBytes>Long.MAX_VALUE-65536 || deadlineSeconds<1)throw invalid();
        String type=request.getContentType();
        if(type==null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")
                || request.getQueryString()!=null || request.getContentLengthLong()>maxBytes+65536)throw invalid();
        Collection<Part> parts=null;Path owned=null;boolean prepared=false;
        try {
            parts=request.getParts();checkTime(start,deadlineSeconds);
            Map<String,Part> fields=new HashMap<>();
            Set<String> allowed=new HashSet<>(Arrays.asList("csrfToken","uploadKey","targetId","file"));
            for(Part part:parts) {
                if(!allowed.contains(part.getName()))throw invalid();
                if(fields.put(part.getName(),part)!=null){if("csrfToken".equals(part.getName()))throw new CsrfRejected();throw invalid();}
            }
            if(!fields.containsKey("csrfToken"))throw new CsrfRejected();
            if(fields.size()!=4)throw invalid();
            String csrf=text(fields.get("csrfToken"),start,deadlineSeconds);
            HttpSession session=request.getSession(false);
            if(session==null || !(session.getAttribute("csrfToken") instanceof String)
                    || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),((String)session.getAttribute("csrfToken")).getBytes(StandardCharsets.UTF_8)))throw new CsrfRejected();
            String key=AttachmentWorkspace.id(text(fields.get("uploadKey"),start,deadlineSeconds));
            String target=AttachmentWorkspace.id(text(fields.get("targetId"),start,deadlineSeconds));
            Part file=fields.get("file");String name=file.getSubmittedFileName();
            if(name==null)throw invalid();name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
            if(name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length>255 || name.chars().anyMatch(c->Character.isISOControl(c)))throw invalid();
            String mime=file.getContentType(),lower=name.toLowerCase(Locale.ROOT);
            if(!((lower.endsWith(".jpg")||lower.endsWith(".jpeg"))&&"image/jpeg".equals(mime))
                    && !(lower.endsWith(".png")&&"image/png".equals(mime))
                    && !(lower.endsWith(".pdf")&&"application/pdf".equals(mime)))throw invalid();
            if(file.getSize()<1 || file.getSize()>maxBytes)throw invalid();
            MessageDigest digest;
            try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            Files.createDirectories(tempDirectory);owned=Files.createTempFile(tempDirectory,"upload-",".tmp");long count=0;
            try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(owned)) {
                byte[] buffer=new byte[8192];int n;
                while(true){checkTime(start,deadlineSeconds);n=in.read(buffer);checkTime(start,deadlineSeconds);if(n<0)break;
                    if(n>maxBytes-count)throw invalid();count+=n;digest.update(buffer,0,n);out.write(buffer,0,n);}
            }
            if(count<1 || count!=file.getSize())throw invalid();
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            PreparedPortalFile result=new PreparedPortalFile(owned,name,mime,count,hash.toString(),key,target);prepared=true;return result;
        } finally {
            try {if(!prepared && owned!=null)Files.deleteIfExists(owned);}
            finally {if(parts!=null)for(Part part:parts)try{part.delete();}catch(IOException ignored){/* Container owns its spool cleanup too. */}}
        }
    }
    private static String text(Part p,long start,int seconds)throws IOException {
        if(p.getSubmittedFileName()!=null || p.getSize()>1024)throw invalid();
        try(InputStream in=p.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[256];int n;while((n=in.read(b))!=-1){checkTime(start,seconds);if(out.size()+n>1024)throw invalid();out.write(b,0,n);}
            checkTime(start,seconds);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static void checkTime(long start,int seconds){if(System.nanoTime()-start>seconds*1000000000L)throw new IllegalArgumentException("上传接收超时，请重新选择文件后重试");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("附件字段、大小或类型无效，请检查后重试");}
}
