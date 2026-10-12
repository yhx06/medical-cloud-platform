package com.medcloud.course.paas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;

class AttachmentDependencyTest {
    @Test void sdkClassLoadsOnJava8() throws Exception {
        assertEquals("1.8", System.getProperty("java.specification.version"));
        assertNotNull(Class.forName("io.minio.MinioClient"));
        assertNotNull(Class.forName("org.apache.pdfbox.pdmodel.PDDocument"));
        java.net.URL origin = Class.forName("io.minio.MinioClient").getProtectionDomain().getCodeSource().getLocation();
        assertTrue(origin.toString().contains("minio-8.6.0.jar"), "Known XML substitution advisory requires >=8.6.0");
        assertTrue(Class.forName("org.apache.pdfbox.pdmodel.PDDocument").getProtectionDomain().getCodeSource().getLocation().toString().contains("pdfbox-2.0.37.jar"));
        assertNotNull(io.minio.MinioClient.builder().endpoint("http://127.0.0.1:1").credentials("probe-access", "probe-secret-unused").build());
        assertEquals("2.21.7", new com.fasterxml.jackson.databind.ObjectMapper().version().toString(), "Patched Jackson is local to PaaS");
        assertTrue(com.google.common.collect.ImmutableList.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("guava-33.7.2-jre.jar"), "Guava uncontrolled allocation advisory requires patched candidate");
    }
    @Test void warDoesNotBundleJavaEeOrResteasy() throws Exception {
        Path war=Paths.get("target/paas.war");
        assertTrue(Files.isRegularFile(war), "Run package before the packaged dependency gate");
        try (ZipFile zip=new ZipFile(war.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries=zip.entries();
            while (entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                assertFalse(name.contains("resteasy") || name.contains("javaee-api"), name);
                assertFalse(name.contains("SdkProbeServlet"), "Test probe leaked into product WAR");
            }
        }
    }
    @Test void java8CanReadEveryBaseClassInPackagedLibraries() throws Exception {
        try (ZipFile war=new ZipFile("target/paas.war")) {
            java.util.Enumeration<? extends ZipEntry> jars=war.entries();
            while (jars.hasMoreElements()) {
                ZipEntry jar=jars.nextElement();
                if (!jar.getName().startsWith("WEB-INF/lib/") || !jar.getName().endsWith(".jar")) continue;
                try (ZipInputStream library=new ZipInputStream(war.getInputStream(jar))) {
                    ZipEntry entry;
                    while ((entry=library.getNextEntry()) != null) {
                        // Java8 ignores multi-release variants; module-info is not loadable application code.
                        if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/") || entry.getName().equals("module-info.class")) continue;
                        DataInputStream bytes=new DataInputStream(library);
                        assertEquals(0xCAFEBABE, bytes.readInt()); bytes.readUnsignedShort(); int major=bytes.readUnsignedShort();
                        assertTrue(major<=52, jar.getName()+"!"+entry.getName()+" major="+major);
                    }
                }
            }
        }
    }
    @Test void ownedWildFlyJava8Probe() throws Exception {
        String fixture=System.getenv("COURSE_ATTACHMENTS_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null && !fixture.trim().isEmpty(), "Real Java8/WildFly gate NOT EXECUTED: Task9 fixture is required");
        Path probe=Paths.get("target/attachment-sdk-probe.war").toAbsolutePath();
        try (ZipFile product=new ZipFile("target/paas.war"); ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(probe))) {
            java.util.Enumeration<? extends ZipEntry> entries=product.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if (!entry.getName().startsWith("WEB-INF/lib/") || entry.isDirectory()) continue;
                out.putNextEntry(new ZipEntry(entry.getName()));
                try(InputStream in=product.getInputStream(entry)) { copy(in,out); } out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("WEB-INF/classes/com/medcloud/course/paas/probe/SdkProbeServlet.class"));
            Files.copy(Paths.get("target/test-classes/com/medcloud/course/paas/probe/SdkProbeServlet.class"),out); out.closeEntry();
        }
        // Run the Task9 owner guard immediately before this sole deployment mutation.
        String script="$ErrorActionPreference='Stop'; . $env:ATTACHMENT_PROBE_GUARD; $f=Get-Content -Raw -LiteralPath $env:COURSE_ATTACHMENTS_FIXTURE | ConvertFrom-Json; Assert-OwnedAttachmentsFixture $f paas; $deploy=Join-Path $f.paas.dataDirectory 'deployments'; $deploy=Assert-AttachmentsOwnedPath $deploy $f.runtimeRoot 'probe deployments'; $dest=Join-Path $deploy 'attachment-sdk-probe.war'; if(Test-Path -LiteralPath $dest){throw 'Probe target exists; refusing overwrite'}; Copy-Item -LiteralPath $env:ATTACHMENT_PROBE_WAR -Destination $dest; [Console]::WriteLine($f.paas.url)";
        ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",script);
        builder.environment().put("ATTACHMENT_PROBE_GUARD",Paths.get("../deploy/tests/attachments-fixture-ownership.ps1").toAbsolutePath().normalize().toString());
        builder.environment().put("ATTACHMENT_PROBE_WAR",probe.toString());
        builder.redirectErrorStream(true);
        Process process=builder.start(); ByteArrayOutputStream result=new ByteArrayOutputStream(); copy(process.getInputStream(),result);
        assertEquals(0,process.waitFor(),new String(result.toByteArray(),"UTF-8"));
        String base=new String(result.toByteArray(),"UTF-8").trim();
        java.net.URI uri=java.net.URI.create(base);
        assertTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        assertEquals("http",uri.getScheme());
        Exception last=null;
        for(int retry=0;retry<30;retry++) {
            try {
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(uri.getScheme()+"://"+uri.getAuthority()+"/attachment-sdk-probe/probe").openConnection();
                connection.setConnectTimeout(1000);connection.setReadTimeout(3000);
                if(connection.getResponseCode()!=200) throw new IOException("Probe not deployed");
                com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(connection.getInputStream());
                assertEquals("probe-file",json.path("fileId").asText()); assertEquals(6,json.size());
                assertEquals("PATIENT_APPOINTMENT",json.path("sourceType").asText()); return;
            } catch(IOException failure) {last=failure;Thread.sleep(1000);}
        }
        throw new IOException("Owned WildFly SDK/serialization probe failed",last);
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
    }
}
