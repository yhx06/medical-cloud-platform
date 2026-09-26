package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.Role;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class PaasClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;

    public PaasClient(String baseUrl) {
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("PaaS base URL must be HTTP(S)");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public LoginResponse login(LoginRequest request) {
        JsonNode data = post("/auth/login", null, request);
        return new LoginResponse(data.path("accessToken").asText(), data.path("username").asText(),
                Role.valueOf(data.path("role").asText()));
    }

    public JsonNode get(String path, String token) {
        return call("GET", path, token, null);
    }

    public JsonNode post(String path, String token, Object request) {
        return call("POST", path, token, request);
    }

    private JsonNode call(String method, String path, String token, Object request) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("invalid PaaS path");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] body = request == null ? "{}".getBytes(StandardCharsets.UTF_8)
                        : JSON.writeValueAsBytes(request);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JsonNode envelope = JSON.readTree(readLimited(stream));
            if (status >= 400 || envelope == null || !"OK".equals(envelope.path("code").asText())) {
                throw new PaasRejectedException(status, publicError(status));
            }
            return envelope.path("data");
        } catch (PaasRejectedException rejected) {
            throw rejected;
        } catch (IOException failure) {
            throw new PaasUnavailableException(failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) {
                    throw new IOException("response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String publicError(int status) {
        if (status == 401) return "账号或密码错误，请重新登录";
        if (status == 403) return "当前账号没有执行该操作的权限";
        if (status == 409) return "操作与当前状态冲突，请刷新后重试";
        if (status == 400) return "提交的信息不完整或格式不正确";
        return "诊疗服务暂时不可用，请稍后重试";
    }
}
