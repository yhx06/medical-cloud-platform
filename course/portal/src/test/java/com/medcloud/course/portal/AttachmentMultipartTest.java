package com.medcloud.course.portal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentMultipartTest {
    static class MemoryPart extends MockPart {
        boolean deleted;
        MemoryPart(String name,byte[] body){super(name,body);}
        MemoryPart(String name,String file,byte[] body){super(name,file,body);}
        @Override public void delete(){deleted=true;}
    }
    @TempDir Path temp;
    static final String ID="11111111-1111-1111-1111-111111111111";
    MockHttpServletRequest request() {
        MockHttpServletRequest r=new MockHttpServletRequest();
        r.setContentType("multipart/form-data; boundary=test");
        r.getSession().setAttribute("csrfToken", "csrf");
        r.addPart(new MemoryPart("csrfToken", "csrf".getBytes()));
        r.addPart(new MemoryPart("uploadKey", ID.getBytes()));
        r.addPart(new MemoryPart("targetId", ID.getBytes()));
        MockPart file=new MemoryPart("file","demo.pdf",new byte[]{1,2,3});
        file.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        r.addPart(file); return r;
    }
    Object read(MockHttpServletRequest r,long max) throws Exception {
        Class<?> c=assertDoesNotThrow(()->Class.forName("com.medcloud.course.portal.web.AttachmentMultipart"));
        try {return c.getMethod("read",HttpServletRequest.class,Path.class,long.class,int.class).invoke(c.newInstance(),r,temp,max,1);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    @Test void fileIsCountedHashedAndDeletedOnClose() throws Exception {
        Object f=read(request(),3); Path path=(Path)f.getClass().getMethod("getPath").invoke(f);
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(path));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",f.getClass().getMethod("getSha256").invoke(f));
        ((AutoCloseable)f).close(); assertFalse(Files.exists(path));
    }
    @Test void duplicateFileAndTargetFieldsRejected() throws Exception {
        for(String field:new String[]{"file","targetId","csrfToken","tenantId","token","destination"}) {
            MockHttpServletRequest r=request(); r.addPart(new MemoryPart(field,ID.getBytes()));
            assertThrows(IllegalArgumentException.class,()->read(r,10));
            try(java.util.stream.Stream<Path> s=Files.list(temp)){assertEquals(0,s.count());}
        }
    }
    @Test void oversizeRejectedWithoutResidualFile() throws Exception {
        assertThrows(IllegalArgumentException.class,()->read(request(),2));
        try(java.util.stream.Stream<Path> s=Files.list(temp)){assertEquals(0,s.count());}
    }
    @Test void missingOrWrongCsrfRejected() throws Exception {
        MockHttpServletRequest missing=request(); missing.getSession().removeAttribute("csrfToken");
        assertThrows(IllegalArgumentException.class,()->read(missing,10));
        MockHttpServletRequest wrong=request(); wrong.getSession().setAttribute("csrfToken","wrong");
        assertThrows(IllegalArgumentException.class,()->read(wrong,10));
    }
    @Test void slowChunkedMultipartDeadline() throws Exception {
        MockHttpServletRequest r=new MockHttpServletRequest();
        r.setContentType("multipart/form-data; boundary=test");r.getSession().setAttribute("csrfToken","csrf");
        r.addPart(new MemoryPart("csrfToken","csrf".getBytes()));r.addPart(new MemoryPart("targetId",ID.getBytes()));r.addPart(new MemoryPart("uploadKey",ID.getBytes()));
        MockPart slow=new MemoryPart("file","demo.pdf",new byte[]{1}) {
            public java.io.InputStream getInputStream() {return new java.io.ByteArrayInputStream(new byte[]{1}) {
                public synchronized int read(byte[] b,int o,int n) {try {Thread.sleep(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();}return super.read(b,o,n);}
            };}
        };slow.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_PDF);r.addPart(slow);
        assertThrows(IllegalArgumentException.class,()->read(r,10));
        try(java.util.stream.Stream<Path> s=Files.list(temp)){assertEquals(0,s.count());}
    }
}
