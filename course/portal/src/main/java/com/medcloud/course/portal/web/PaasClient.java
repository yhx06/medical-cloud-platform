package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

public final class PaasClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader ERROR_JSON = JSON.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
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

    public JsonNode put(String path, String token, Object request) {
        return call("PUT", path, token, request);
    }

    /** The watchdog bounds blocked socket writes as well as slow response reads. */
    public JsonNode putBinary(String path, String token, Path file, long maxBytes, int totalTimeoutSeconds) {
        if(path==null || !path.matches("/files/uploads/[A-Za-z0-9-]+/content") || maxBytes<1 || totalTimeoutSeconds<1 || totalTimeoutSeconds>600)
            throw new IllegalArgumentException("invalid binary upload");
        HttpURLConnection connection=null;ScheduledFuture<?> timer=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(totalTimeoutSeconds);
        try {
            long size=Files.size(file);if(size<1 || size>maxBytes)throw new IllegalArgumentException("附件大小超出限制");
            connection=(HttpURLConnection)new URL(baseUrl+path).openConnection();
            final HttpURLConnection active=connection;
            timer=DEADLINES.schedule(active::disconnect,totalTimeoutSeconds,TimeUnit.SECONDS);
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("PUT");connection.setDoOutput(true);
            connection.setConnectTimeout(3000);connection.setReadTimeout((int)Math.min(Integer.MAX_VALUE,totalTimeoutSeconds*1000L));
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type","application/octet-stream");
            if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setFixedLengthStreamingMode(size);
            try(InputStream input=Files.newInputStream(file);OutputStream output=connection.getOutputStream()) {
                byte[] buffer=new byte[8192];long count=0;int n;
                while((n=input.read(buffer))!=-1){if(System.nanoTime()>deadline || n>maxBytes-count || n>size-count)throw new IOException("bounded upload");output.write(buffer,0,n);count+=n;}
                if(count!=size)throw new IOException("file changed");
            }
            int status=connection.getResponseCode();
            if(status<200 || status>=300)throw new PaasRejectedException(status,publicError(status));
            JsonNode envelope=JSON.readTree(readLimited(connection.getInputStream(),deadline));
            if(System.nanoTime()>deadline)throw new IOException("upload deadline");
            if(envelope==null || !"OK".equals(envelope.path("code").asText()))throw new PaasRejectedException(status,publicError(status));
            return envelope.path("data");
        }catch(IOException failure){throw new PaasUnavailableException(new IOException("bounded binary request failed"));}
        finally{if(timer!=null)timer.cancel(false);if(connection!=null)connection.disconnect();}
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"portal-upload-deadline");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);return executor;
    }

    private JsonNode call(String method, String path, String token, Object request) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("invalid PaaS path");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if ("POST".equals(method) || "PUT".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] body = request == null ? "{}".getBytes(StandardCharsets.UTF_8)
                        : JSON.writeValueAsBytes(request);
                // Streaming disables HttpURLConnection's silent replay of a buffered POST after a lost response.
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            // HTTP authorization status is authoritative even when a proxy returns HTML or no body.
            if (status < 200 || status >= 300) throw rejection(connection, status);
            InputStream stream = connection.getInputStream();
            JsonNode envelope = JSON.readTree(readLimited(stream));
            if (envelope == null || !"OK".equals(envelope.path("code").asText())) {
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

    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private static byte[] readLimited(InputStream stream) throws IOException {
        return readLimited(stream, NO_DEADLINE);
    }

    /**
     * The binary response reader owns the upload's absolute deadline instead of relying on the
     * watchdog, so a blocked watchdog or a continuous response trickle cannot extend the read.
     * Blocked connect/write/close paths remain bounded by the watchdog and socket timeouts.
     */
    private static byte[] readLimited(InputStream stream, long deadlineNanos) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while (true) {
                checkReadDeadline(deadlineNanos);
                count = input.read(buffer);
                checkReadDeadline(deadlineNanos);
                if (count == -1) {
                    break;
                }
                if (output.size() + count > 1024 * 1024) {
                    throw new IOException("response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void checkReadDeadline(long deadlineNanos) throws IOException {
        if (deadlineNanos != NO_DEADLINE && System.nanoTime() - deadlineNanos >= 0) {
            throw new IOException("response deadline");
        }
    }

    private static PaasRejectedException rejection(HttpURLConnection connection, int status) {
        // Authorization is determined by HTTP status alone; never parse a proxy's authorization body.
        if (status == 400 || status == 409) {
            try {
                JsonNode error = ERROR_JSON.readValue(readLimited(connection.getErrorStream()));
                if (error != null && error.isObject() && error.path("code").isTextual()) {
                    String code = error.path("code").textValue();
                    String message = safeBusinessMessage(status, code);
                    if (message != null) return new PaasRejectedException(status, message, code);
                }
            } catch (IOException ignored) { /* Bounded, malformed errors retain the original safe public error. */ }
        }
        return new PaasRejectedException(status, publicError(status));
    }

    private static String safeBusinessMessage(int status, String code) {
        if (status == 400 && "HOSPITAL_ONBOARDING_INVALID".equals(code)) return "开通信息无效，请检查医院名称、用户名、密码和操作编号";
        if (status != 409) return null;
        if ("USERNAME_EXISTS".equals(code)) return "用户名已被使用，请核对账号后再提交";
        if ("OPERATION_KEY_CONFLICT".equals(code)) return "操作编号与原提交内容冲突，请复核原信息，不要直接换编号重发";
        if ("ADMIN_STATUS_CONFLICT".equals(code)) return "管理员状态已变化，请另开列表复核，不要直接覆盖当前状态";
        if ("LAST_ACTIVE_HOSPITAL_ADMIN".equals(code)) return "不能停用最后一位启用的医院管理员，请先增补或启用另一位管理员后复核";
        return null;
    }

    private static String publicError(int status) {
        if (status == 401) return "账号或密码错误，请重新登录";
        if (status == 403) return "当前账号没有执行该操作的权限";
        if (status == 409) return "操作与当前状态冲突，请刷新后重试";
        if (status == 400) return "提交的信息不完整或格式不正确";
        return "诊疗服务暂时不可用，请稍后重试";
    }
}
