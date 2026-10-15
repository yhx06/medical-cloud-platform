package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.PrepareUploadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.cos.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentValidatorTest {
    @TempDir Path temp;
    final AttachmentValidator validator = new AttachmentValidator();
    final AttachmentLimits limits = new AttachmentLimits();
    static String digest(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x",b & 255));
        return result.toString();
    }
    static byte[] png() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }
    static byte[] pdf(int pages, String forbidden) throws Exception {
        try(PDDocument doc=new PDDocument()) {
            for(int i=0;i<pages;i++) doc.addPage(new PDPage());
            if("encrypted".equals(forbidden)) doc.protect(new StandardProtectionPolicy("owner","",new AccessPermission()));
            else if(forbidden!=null) {
                COSDictionary action=new COSDictionary(); action.setName(COSName.S,forbidden);
                COSDictionary nested=new COSDictionary(); nested.setItem(COSName.getPDFName("Hidden"),action);
                doc.getPage(0).getCOSObject().setItem(COSName.getPDFName("Custom"),nested);
            }
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.save(out);return out.toByteArray();
        }
    }
    PrepareUploadRequest descriptor(byte[] bytes,String name,String mime) throws Exception {
        return new PrepareUploadRequest(UUID.randomUUID().toString(),name,mime,bytes.length,digest(bytes));
    }
    ValidatedAttachment validate(byte[] bytes,String name,String mime) throws Exception {
        return validator.validate(new ByteArrayInputStream(bytes),(long)bytes.length,descriptor(bytes,name,mime),limits,temp);
    }
    void rejected(byte[] bytes,String name,String mime) throws Exception {
        PrepareUploadRequest d=descriptor(bytes,name,mime);
        assertThrows(AttachmentException.class,()->validator.validate(new ByteArrayInputStream(bytes),null,d,limits,temp));
        try(java.util.stream.Stream<Path> files=Files.list(temp)) { assertEquals(0,files.count()); }
    }
    @Test void exactTenMiBAcceptedAndOneByteMoreRejected() throws Exception {
        byte[] bytes=Arrays.copyOf(png(),10485760);
        ValidatedAttachment a=validate(bytes,"demo.png","image/png");assertEquals(10485760,a.getSizeBytes());Files.delete(a.getPath());
        byte[] tooLarge=Arrays.copyOf(bytes,10485761);
        AttachmentException ex=assertThrows(AttachmentException.class,()->validate(tooLarge,"demo.png","image/png"));assertEquals(413,ex.getStatus());
    }
    @Test void mismatchedDigestAndLengthRejected() throws Exception {
        byte[] bytes=png();PrepareUploadRequest d=descriptor(bytes,"demo.png","image/png");d.setSha256(String.join("",Collections.nCopies(64,"0")));
        assertThrows(AttachmentException.class,()->validator.validate(new ByteArrayInputStream(bytes),null,d,limits,temp));
        d.setSha256(digest(bytes)); d.setSizeBytes(bytes.length+1);
        assertThrows(AttachmentException.class,()->validator.validate(new ByteArrayInputStream(bytes),null,d,limits,temp));
        d.setSizeBytes(bytes.length);
        assertThrows(AttachmentException.class,()->validator.validate(new ByteArrayInputStream(bytes),(long)bytes.length+1,d,limits,temp));
    }
    @Test void spoofedMimeAndCorruptFileRejected() throws Exception {
        rejected(png(),"demo.pdf","application/pdf");rejected(Arrays.copyOf(png(),24),"demo.png","image/png");rejected("%PDF-1.4\ngarbage".getBytes("US-ASCII"),"demo.pdf","application/pdf");
    }
    @Test void oversizedImageDimensionsRejectedBeforeDecode() throws Exception {
        byte[] bytes=png(); // IHDR claims 20,000,001 pixels; invalid CRC must not reach decode/allocation.
        bytes[16]=1;bytes[17]=49;bytes[18]=45;bytes[19]=1; bytes[20]=0;bytes[21]=0;bytes[22]=0;bytes[23]=1;
        rejected(bytes,"large.png","image/png");
    }
    @Test void pdfEncryptedEmbeddedJavascriptAndNestedActionsRejected() throws Exception {
        for(String type:Arrays.asList("encrypted","JavaScript","Launch")) rejected(pdf(1,type),"demo.pdf","application/pdf");
        for(String key:Arrays.asList("EmbeddedFiles","EF","OpenAction","AA","JS")) {
            try(PDDocument doc=new PDDocument()) {
                doc.addPage(new PDPage());doc.getPage(0).getCOSObject().setItem(COSName.getPDFName(key),new COSDictionary());
                ByteArrayOutputStream out=new ByteArrayOutputStream();doc.save(out);rejected(out.toByteArray(),"demo.pdf","application/pdf");
            }
        }
    }
    @Test void cyclicAndDeepPdfActionsBounded() throws Exception {
        try(PDDocument doc=new PDDocument()) {
            doc.addPage(new PDPage());COSDictionary root=doc.getPage(0).getCOSObject(), current=root;
            for(int i=0;i<80;i++) {COSDictionary next=new COSDictionary();current.setItem(COSName.getPDFName("Next"),next);current=next;}
            current.setItem(COSName.getPDFName("Next"),root);
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.save(out);rejected(out.toByteArray(),"demo.pdf","application/pdf");
        }
    }
    @Test void pdfPageBoundsAndValidFormats() throws Exception {
        for(byte[] data:new byte[][]{pdf(1,null),pdf(200,null)}) {ValidatedAttachment a=validate(data,"demo.pdf","application/pdf");Files.delete(a.getPath());}
        rejected(pdf(201,null),"demo.pdf","application/pdf");rejected(pdf(0,null),"demo.pdf","application/pdf");
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"jpeg",out);
        ValidatedAttachment a=validate(out.toByteArray(),"demo.jpeg","image/jpeg");assertEquals("image/jpeg",a.getContentType());Files.delete(a.getPath());
    }
    @Test void unicodeNameBytesAndCrLfRejected() throws Exception {
        rejected(png(),String.join("",Collections.nCopies(85,"界"))+".png","image/png");rejected(png(),"a\r\nb.png","image/png");
        ValidatedAttachment a=validate(png(),"C:\\fakepath\\演示.png","image/png");assertEquals("演示.png",a.getFileName());Files.delete(a.getPath());
    }
    @Test void fourConcurrentProcessorsAndFifth429() throws Exception {
        AttachmentProcessingGate gate=new AttachmentProcessingGate(limits);List<AutoCloseable> leases=new ArrayList<>();
        for(int i=0;i<4;i++) leases.add(gate.tryAcquire());
        assertEquals(429,assertThrows(AttachmentException.class,gate::tryAcquire).getStatus());
        leases.get(0).close();leases.get(0).close();
        AutoCloseable replacement=gate.tryAcquire();assertThrows(AttachmentException.class,gate::tryAcquire);replacement.close();
        for(AutoCloseable lease:leases) lease.close();
    }
    @Test void dripFeedDeadlineWithoutContentLength() throws Exception {
        byte[] bytes=png(); PrepareUploadRequest d=descriptor(bytes,"demo.png","image/png");
        InputStream slow=new InputStream() { public int read() throws IOException {try{Thread.sleep(300);}catch(InterruptedException e){throw new IOException();}return 1;} };
        AttachmentLimits shortLimits=new AttachmentLimits(10485760,20000000,200,4,2,3,90,1);
        long start=System.nanoTime();assertThrows(AttachmentException.class,()->validator.validate(slow,null,d,shortLimits,temp));
        assertTrue((System.nanoTime()-start)/1000000<2500,"receive total deadline");
    }
    @Test void handoffKeepsFileUntilCallerAccepts() throws Exception {
        AttachmentValidator.TempFileHandoff handoff=new AttachmentValidator.TempFileHandoff();
        Path file=Files.createTempFile(temp,"handoff-",".tmp");
        assertTrue(handoff.offerFile(file));
        ValidatedAttachment result=new ValidatedAttachment(file,"demo.png","image/png",1,digest(png()));
        assertTrue(handoff.offerResult(result));
        assertTrue(handoff.accept());
        assertNull(handoff.abandon(),"accepted files belong to the caller and must not be reclaimed");
        assertTrue(Files.isRegularFile(file),"successfully returned files must not be deleted");
    }
    @Test void handoffCompletionThenAbandonAlwaysCleansFile() throws Exception {
        // Deterministic barrier interleaving of the completion/cancel race: whichever side loses owns cleanup.
        for(int round=0;round<50;round++) {
            AttachmentValidator.TempFileHandoff handoff=new AttachmentValidator.TempFileHandoff();
            final Path file=Files.createTempFile(temp,"handoff-",".tmp");
            handoff.offerFile(file);
            final ValidatedAttachment result=new ValidatedAttachment(file,"demo.png","image/png",1,digest(png()));
            final java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(2);
            final AtomicReference<Path> victim=new AtomicReference<>();
            Thread worker=new Thread(()->{
                await(barrier);
                handoff.offerResult(result);
                if(!handoff.handedOver())deleteQuietly(file);
            });
            Thread caller=new Thread(()->{
                await(barrier);
                victim.set(handoff.abandon());
                if(victim.get()!=null)deleteQuietly(victim.get());
            });
            worker.start();caller.start();worker.join(5000);caller.join(5000);
            assertFalse(Files.exists(file),"a completed validation abandoned by the caller must still be cleaned");
        }
    }
    @Test void handoffAbandonBeforeCompletionRefusesResult() throws Exception {
        AttachmentValidator.TempFileHandoff handoff=new AttachmentValidator.TempFileHandoff();
        Path file=Files.createTempFile(temp,"handoff-",".tmp");
        handoff.offerFile(file);
        assertEquals(file,handoff.abandon());
        Files.deleteIfExists(file);
        assertFalse(handoff.offerResult(new ValidatedAttachment(file,"demo.png","image/png",1,digest(png()))),"abandoned handoff must refuse a late result");
        assertFalse(handoff.handedOver(),"worker keeps cleanup duty after a refused result");
    }
    @Test void handoffAbandonBeforeFileCreationLetsWorkerClean() throws Exception {
        AttachmentValidator.TempFileHandoff handoff=new AttachmentValidator.TempFileHandoff();
        assertNull(handoff.abandon());
        Path file=Files.createTempFile(temp,"handoff-",".tmp");
        assertFalse(handoff.offerFile(file),"worker must learn the caller already abandoned");
        assertFalse(handoff.handedOver());
        Files.deleteIfExists(file);
    }
    @Test void timeoutWhileReceivingCleansCreatedTempFile() throws Exception {
        byte[] bytes=png(); PrepareUploadRequest d=descriptor(bytes,"demo.png","image/png");
        InputStream drip=new InputStream() {
            public int read() throws IOException {
                try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException();}
                return 1;
            }
        };
        AttachmentLimits shortLimits=new AttachmentLimits(10485760,20000000,200,4,2,3,90,1);
        assertThrows(AttachmentException.class,()->validator.validate(drip,null,d,shortLimits,temp));
        long waitUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<waitUntil) {
            try(java.util.stream.Stream<Path> files=Files.list(temp)) { if(files.count()==0)return; }
            Thread.sleep(50);
        }
        try(java.util.stream.Stream<Path> files=Files.list(temp)) { assertEquals(0,files.count(),"every failure path must eventually clean its temp file"); }
    }
    static void await(java.util.concurrent.CyclicBarrier barrier) {
        try{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    static void deleteQuietly(Path file) {
        try{Files.deleteIfExists(file);}catch(IOException ignored){}
    }
}
