package com.medcloud.course.portal.web;

import java.net.URI;
import java.util.*;

public final class AttachmentDownloadPolicy {
    private AttachmentDownloadPolicy(){}
    public static URI requireAllowed(String url,Set<String> allowedOrigins) {
        URI uri=parse(url);String origin=origin(uri);
        if(allowedOrigins!=null)for(String allowed:allowedOrigins){URI candidate=parse(allowed);
            if(candidate.getRawQuery()!=null || !(candidate.getRawPath()==null || candidate.getRawPath().isEmpty() || "/".equals(candidate.getRawPath())))throw invalid();
            if(origin.equals(origin(candidate)))return uri;
        }
        throw invalid();
    }
    private static URI parse(String value){try{URI uri=new URI(value);origin(uri);return uri;}catch(Exception invalid){throw invalid();}}
    private static String origin(URI uri) {
        String scheme=uri.getScheme(),host=uri.getHost();
        if(scheme==null || !("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)) || host==null
                || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || uri.isOpaque() || uri.getPort()>65535 || uri.getPort()==0)throw invalid();
        int port=uri.getPort()<0?("https".equalsIgnoreCase(scheme)?443:80):uri.getPort();
        return scheme.toLowerCase(Locale.ROOT)+"://"+host.toLowerCase(Locale.ROOT)+":"+port;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("下载地址未通过安全检查，请稍后重试");}
}
