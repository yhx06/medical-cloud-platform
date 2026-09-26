package com.medcloud.course.portal.web;

public final class WebEscaper {
    private WebEscaper() { }

    public static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
