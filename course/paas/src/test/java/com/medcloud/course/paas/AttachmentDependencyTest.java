package com.medcloud.course.paas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;

class AttachmentDependencyTest {
    private static final String FIXTURE_READ="$f=[IO.File]::ReadAllText($env:COURSE_ATTACHMENTS_FIXTURE) | ConvertFrom-Json;";
    @Test void fixtureReaderHandlesUtf8UnicodePaths() throws Exception {
        Path file=Files.createTempFile(Paths.get("target"),"fixture-json-unicode-",".json");
        Files.write(file,"{\"runtimeRoot\":\"C:\\\\云\\\\q\"}".getBytes("UTF-8"));
        ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command","$ErrorActionPreference='Stop';"+FIXTURE_READ+"if($f.runtimeRoot -cne $env:EXPECTED_ROOT){throw 'Unicode path changed'};[Console]::WriteLine('ok')");
        builder.environment().put("COURSE_ATTACHMENTS_FIXTURE",file.toAbsolutePath().toString());
        builder.environment().put("EXPECTED_ROOT","C:\\云\\q");builder.redirectErrorStream(true);
        Process process=builder.start();ByteArrayOutputStream output=new ByteArrayOutputStream();copy(process.getInputStream(),output);
        assertEquals(0,process.waitFor(),new String(output.toByteArray(),"UTF-8"));assertEquals("ok",new String(output.toByteArray(),"UTF-8").trim());
    }
    @Test void privateSdkRelocationExistsWithoutPublicJacksonLeak() throws Exception {
        Class<?> sdk=Class.forName("com.medcloud.course.paas.sdk.internal.minio.MinioClient");
        assertTrue(sdk.getProtectionDomain().getCodeSource().getLocation().toString().contains("attachment-sdk"));
        assertNotNull(Class.forName("com.medcloud.course.paas.sdk.internal.jackson.databind.ObjectMapper"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("io.minio.MinioClient"));
        assertEquals(Paths.get("../attachment-sdk/target/attachment-sdk-0.1.0-SNAPSHOT.jar").toRealPath(),Paths.get(sdk.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),"Use freshly built reactor artifact, not an installed SDK");
        try(ZipFile jar=new ZipFile(Paths.get(sdk.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile())) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"),"Retain upstream license");
            assertNotNull(jar.getEntry("META-INF/NOTICE"),"Retain merged upstream notices");
            assertNotNull(jar.getEntry("META-INF/medcloud-attachment-sdk.NOTICE"),"Retain assembly provenance and limitations");
            java.util.Properties metadata=new java.util.Properties();
            try(InputStream in=jar.getInputStream(jar.getEntry("META-INF/medcloud-attachment-sdk.properties"))){metadata.load(in);}
            assertEquals("8.6.0",metadata.getProperty("minio.version"));
            assertEquals("io.minio:minio:8.6.0",metadata.getProperty("minio.source.coordinate"));
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(Paths.get("../attachment-sdk/target/sdk-provenance/minio-upstream.jar"))) {
                byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);
            }
            StringBuilder checksum=new StringBuilder();for(byte value:digest.digest())checksum.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            assertEquals("295b5aec09e39ac4456a94577763477b025c3861d1dde0d23af8223432c80a77",checksum.toString(),"Independent official Maven Central published SHA-256 for MinIO8.6.0");
            assertEquals(checksum.toString(),metadata.getProperty("minio.source.sha256"),"Audit actual resolved input bytes; upstream runtime version marker is dev");
            assertEquals("2.21.7",metadata.getProperty("jackson.databind.version"));
            for(String[] artifact:new String[][]{{"com.fasterxml.jackson.core/jackson-databind","2.21.7"},{"com.fasterxml.jackson.core/jackson-core","2.21.7"},{"com.fasterxml.jackson.core/jackson-annotations","2.21"}}) {
                java.util.Properties original=new java.util.Properties();
                try(InputStream in=jar.getInputStream(jar.getEntry("META-INF/maven/"+artifact[0]+"/pom.properties"))){original.load(in);}
                assertEquals(artifact[1],original.getProperty("version"),"Retain actual upstream artifact provenance");
            }
            java.util.ServiceLoader<com.medcloud.course.paas.sdk.internal.jackson.core.JsonFactory> factories=java.util.ServiceLoader.load(com.medcloud.course.paas.sdk.internal.jackson.core.JsonFactory.class);
            assertTrue(factories.iterator().hasNext(),"Relocated Jackson service provider must be discoverable");
            assertNotNull(factories.iterator().next());
            java.util.Enumeration<? extends ZipEntry> entries=jar.entries();
            while(entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                assertFalse(name.endsWith(".class")&&name.contains("/com/fasterxml/jackson/"),"Private SDK must not retain unrelocated multi-release classes: "+name);
                assertFalse(name.contains("sdk-provenance/")||name.endsWith("minio-upstream.jar"),"Audit inputs must not enter application artifact");
                assertFalse(name.startsWith("io/minio/")||name.startsWith("com/fasterxml/jackson/"),"Unrelocated SDK namespace: "+name);
                assertFalse(name.endsWith(".SF")||name.endsWith(".RSA")||name.endsWith(".DSA"),"Invalid upstream signatures after shade: "+name);
                if(name.startsWith("META-INF/services/")) {
                    ByteArrayOutputStream service=new ByteArrayOutputStream();try(InputStream in=jar.getInputStream(jar.getEntry(name))){copy(in,service);}
                    assertFalse(name.contains("com.fasterxml.jackson"),"Service key must relocate");
                    assertFalse(new String(service.toByteArray(),"UTF-8").contains("com.fasterxml.jackson"),"Service implementations must relocate");
                }
            }
        }
    }
    @Test void sdkClassLoadsOnJava8() throws Exception {
        assertEquals("1.8", System.getProperty("java.specification.version"));
        assertNotNull(Class.forName("com.medcloud.course.paas.sdk.internal.minio.MinioClient"));
        assertNotNull(Class.forName("org.apache.pdfbox.pdmodel.PDDocument"));
        java.net.URL origin = Class.forName("com.medcloud.course.paas.sdk.internal.minio.MinioClient").getProtectionDomain().getCodeSource().getLocation();
        assertTrue(origin.toString().contains("attachment-sdk-0.1.0-SNAPSHOT.jar"), "SDK must be private artifact");
        assertTrue(Class.forName("org.apache.pdfbox.pdmodel.PDDocument").getProtectionDomain().getCodeSource().getLocation().toString().contains("pdfbox-2.0.37.jar"));
        assertNotNull(com.medcloud.course.paas.sdk.internal.minio.MinioClient.builder().endpoint("http://127.0.0.1:1").credentials("probe-access", "probe-secret-unused").build());
        assertEquals("2.21.7", new com.medcloud.course.paas.sdk.internal.jackson.databind.ObjectMapper().version().toString(), "Patched SDK Jackson is private");
        assertEquals("2.15.4",new com.fasterxml.jackson.databind.ObjectMapper().version().toString(),"Public JSON stays at baseline");
        assertTrue(com.google.common.collect.ImmutableList.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("guava-33.7.2-jre.jar"), "Guava uncontrolled allocation advisory requires patched candidate");
        assertTrue(org.bouncycastle.jce.provider.BouncyCastleProvider.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("bcprov-jdk18on-1.85.jar"), "Patched BC candidate must be the actual runtime artifact");
        assertTrue(org.apache.commons.io.IOUtils.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("commons-io-2.14.0.jar"), "Patched commons-io candidate must replace packaged baseline");
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
                assertFalse(name.contains("SdkProbeResource"), "Test resource leaked into product WAR");
                assertFalse(name.contains("sdk-provenance")||name.endsWith("minio-upstream.jar"),"Do not package audit inputs");
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
    @Test void probePackagesActualApplicationAndJaxRsResponsePath() throws Exception {
        Path probe=buildProbe("attachment-sdk-structure");
        try(ZipFile zip=new ZipFile(probe.toFile())) {
            assertNotNull(zip.getEntry("WEB-INF/classes/com/medcloud/course/paas/api/CourseApplication.class"), "Use real product JAX-RS application discovery");
            assertNotNull(zip.getEntry("WEB-INF/classes/com/medcloud/course/paas/probe/SdkProbeResource.class"), "Probe must exercise RESTEasy JSON provider");
        }
        Object response=Class.forName("com.medcloud.course.paas.probe.SdkProbeResource").getMethod("json").invoke(Class.forName("com.medcloud.course.paas.probe.SdkProbeResource").newInstance());
        assertTrue(response instanceof com.medcloud.course.contract.ApiResponse);
        com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response);
        assertEquals("OK",json.path("code").asText()); assertEquals("probe-file",json.path("data").path("fileId").asText()); assertEquals(6,json.path("data").size());
    }
    @Test void probeNeedsNoSpecialProviderOrDeploymentIsolation() throws Exception {
        Path probe=buildProbe("attachment-isolation-structure");
        String descriptor="WEB-INF/jboss-deployment-structure.xml";
        try(ZipFile product=new ZipFile("target/paas.war");ZipFile isolated=new ZipFile(probe.toFile())) {
            assertNull(product.getEntry(descriptor),"Restore original container provider without deployment override");
            assertNull(isolated.getEntry(descriptor),"Probe must not hide classloading with special overrides");
        }
    }
    @Test void descriptorResponseUsesExistingJsonRequestContract() throws Exception {
        Class<?> resource=Class.forName("com.medcloud.course.paas.probe.SdkProbeResource");
        com.medcloud.course.contract.PrepareUploadRequest request=new com.medcloud.course.contract.PrepareUploadRequest("12345678-1234-1234-1234-123456789abc","检查.pdf","application/pdf",17,"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        Object response=resource.getMethod("prepare",com.medcloud.course.contract.PrepareUploadRequest.class).invoke(resource.newInstance(),request);
        com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response);
        assertEquals("OK",json.path("code").asText()); assertEquals("检查.pdf",json.path("data").path("fileName").asText()); assertEquals(17,json.path("data").path("sizeBytes").asLong()); assertEquals(5,json.path("data").size());
    }
    @Test void existingRequestProbeReturnsOnlySafeFields() throws Exception {
        Class<?> resource=Class.forName("com.medcloud.course.paas.probe.SdkProbeResource");
        Object response=resource.getMethod("register",com.medcloud.course.contract.RegisterRequest.class).invoke(resource.newInstance(),new com.medcloud.course.contract.RegisterRequest("fictional-probe","probe-value-not-a-real-credential"));
        com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response);
        assertEquals("fictional-probe",json.path("data").asText());assertEquals(3,json.size());
        assertFalse(json.toString().contains("probe-value-not-a-real-credential"));
        Object tenant=resource.getMethod("tenantStatus",com.medcloud.course.contract.TenantStatusRequest.class).invoke(resource.newInstance(),new com.medcloud.course.contract.TenantStatusRequest("DISABLED","ENABLED"));
        assertEquals("DISABLED",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(tenant).path("data").asText());
    }
    private static Path buildProbe(String deploymentName) throws Exception {
        Path probe=Paths.get("target/"+deploymentName+".war").toAbsolutePath();
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
            out.putNextEntry(new ZipEntry("WEB-INF/classes/com/medcloud/course/paas/probe/SdkProbeResource.class"));
            Files.copy(Paths.get("target/test-classes/com/medcloud/course/paas/probe/SdkProbeResource.class"),out); out.closeEntry();
            String application="WEB-INF/classes/com/medcloud/course/paas/api/CourseApplication.class";
            assertNotNull(product.getEntry(application),"Actual product application must be present");
            out.putNextEntry(new ZipEntry(application));
            try(InputStream in=product.getInputStream(product.getEntry(application))) {copy(in,out);} out.closeEntry();
        }
        return probe;
    }
    @Test void ownedWildFlyJava8Probe() throws Exception {
        String fixture=System.getenv("COURSE_ATTACHMENTS_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null && !fixture.trim().isEmpty(), "Real Java8/WildFly gate NOT EXECUTED: Task9 fixture is required");
        String deploymentName="attachment-sdk-probe-"+java.util.UUID.randomUUID().toString().replace("-","");
        Path probe=buildProbe(deploymentName);
        // Run the Task9 owner guard immediately before this sole deployment mutation.
        String script="$ErrorActionPreference='Stop'; . $env:ATTACHMENT_PROBE_GUARD; "+FIXTURE_READ+" Assert-OwnedAttachmentsFixture $f paas; $deploy=Join-Path $f.paas.dataDirectory 'deployments'; $deploy=Assert-AttachmentsOwnedPath $deploy $f.runtimeRoot 'probe deployments'; if($env:ATTACHMENT_PROBE_NAME -notmatch '^attachment-sdk-probe-[0-9a-f]{32}$'){throw 'Invalid probe name'}; $dest=Join-Path $deploy ($env:ATTACHMENT_PROBE_NAME+'.war'); if(Test-Path -LiteralPath $dest){throw 'Probe target exists; refusing overwrite'}; Copy-Item -LiteralPath $env:ATTACHMENT_PROBE_WAR -Destination $dest; [Console]::WriteLine($f.paas.url)";
        ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",script);
        builder.environment().put("ATTACHMENT_PROBE_GUARD",Paths.get("../deploy/tests/attachments-fixture-ownership.ps1").toAbsolutePath().normalize().toString());
        builder.environment().put("ATTACHMENT_PROBE_WAR",probe.toString());
        builder.environment().put("ATTACHMENT_PROBE_NAME",deploymentName);
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
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(uri.getScheme()+"://"+uri.getAuthority()+"/"+deploymentName+"/api/sdk-probe").openConnection();
                connection.setConnectTimeout(1000);connection.setReadTimeout(3000);
                if(connection.getResponseCode()!=200) throw new IOException("Probe not deployed");
                com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(connection.getInputStream());
                assertEquals("OK",json.path("code").asText()); assertEquals("SDK probe",json.path("message").asText()); assertEquals(3,json.size());
                assertEquals("probe-file",json.path("data").path("fileId").asText()); assertEquals(6,json.path("data").size());
                assertEquals("PATIENT_APPOINTMENT",json.path("data").path("sourceType").asText());
                verifyRequestRead(uri,deploymentName);
                verifyExistingRequestAnnotations(uri,deploymentName); return;
            } catch(IOException failure) {last=failure;Thread.sleep(1000);}
        }
        throw new IOException("Owned WildFly SDK/serialization probe failed",last);
    }
    private static void verifyRequestRead(java.net.URI uri,String deploymentName) throws Exception {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(uri.getScheme()+"://"+uri.getAuthority()+"/"+deploymentName+"/api/sdk-probe/descriptor").openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(3000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
        String payload="{\"uploadKey\":\"12345678-1234-1234-1234-123456789abc\",\"fileName\":\"检查.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":17,\"sha256\":\"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\"}";
        try(OutputStream out=connection.getOutputStream()){out.write(payload.getBytes("UTF-8"));}
        assertEquals(200,connection.getResponseCode(),"Real RESTEasy JSON request binding failed");
        com.fasterxml.jackson.databind.JsonNode json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(connection.getInputStream());
        assertEquals("OK",json.path("code").asText());assertEquals("Descriptor probe",json.path("message").asText());assertEquals(3,json.size());
        assertEquals("12345678-1234-1234-1234-123456789abc",json.path("data").path("uploadKey").asText());assertEquals("检查.pdf",json.path("data").path("fileName").asText());
        assertEquals("application/pdf",json.path("data").path("contentType").asText());assertEquals(17,json.path("data").path("sizeBytes").asLong());assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",json.path("data").path("sha256").asText());assertEquals(5,json.path("data").size());
    }
    private static void verifyExistingRequestAnnotations(java.net.URI uri,String deploymentName) throws Exception {
        String base=uri.getScheme()+"://"+uri.getAuthority()+"/"+deploymentName+"/api/sdk-probe";
        java.net.HttpURLConnection register=postJson(base+"/register","{\"username\":\"fictional-probe\",\"password\":\"probe-value-not-a-real-credential\",\"ignoredExtra\":true}");
        assertEquals(200,register.getResponseCode(),"Existing RegisterRequest ignoreUnknown annotation must work through container provider");
        com.fasterxml.jackson.databind.JsonNode reply=new com.fasterxml.jackson.databind.ObjectMapper().readTree(register.getInputStream());
        assertEquals("OK",reply.path("code").asText());assertEquals("fictional-probe",reply.path("data").asText());assertEquals(3,reply.size());
        assertFalse(reply.toString().contains("probe-value-not-a-real-credential"));
        java.net.HttpURLConnection known=postJson(base+"/tenant-status","{\"status\":\"DISABLED\",\"expectedStatus\":\"ENABLED\"}");
        assertEquals(200,known.getResponseCode());
        assertEquals("DISABLED",new com.fasterxml.jackson.databind.ObjectMapper().readTree(known.getInputStream()).path("data").asText());
        java.net.HttpURLConnection unknown=postJson(base+"/tenant-status","{\"status\":\"DISABLED\",\"expectedStatus\":\"ENABLED\",\"unexpected\":true}");
        assertEquals(400,unknown.getResponseCode(),"Existing TenantStatusRequest must reject unknown fields");
    }
    private static java.net.HttpURLConnection postJson(String url,String payload) throws Exception {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(3000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
        try(OutputStream out=connection.getOutputStream()){out.write(payload.getBytes("UTF-8"));}
        return connection;
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
    }
}
