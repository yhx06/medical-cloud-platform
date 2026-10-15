package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.PrepareUploadRequest;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

@ApplicationScoped
public class AttachmentValidator {
    /**
     * Explicit temp-file ownership shared by the validating worker and its caller.
     * The worker offers the exact path and the successful result; ownership only
     * passes with {@link #accept()}. If the caller gives up first, {@link #abandon()}
     * returns the path to clean, so a completion/cancel race can never orphan it.
     */
    static final class TempFileHandoff {
        private Path file;
        private boolean completed, accepted, abandoned;
        synchronized boolean offerFile(Path path) {this.file=path;return !abandoned;}
        synchronized boolean offerResult(ValidatedAttachment result) {if(abandoned)return false;completed=true;return true;}
        synchronized boolean accept() {if(abandoned||!completed)return false;accepted=true;return true;}
        synchronized Path abandon() {abandoned=true;if(accepted)return null;return file;}
        synchronized boolean handedOver() {return completed||accepted;}
        synchronized boolean isAbandoned() {return abandoned;}
    }
    private ThreadPoolExecutor workers;
    private synchronized ThreadPoolExecutor workers(AttachmentLimits limits) {
        if(workers==null) {
            workers=new ThreadPoolExecutor(limits.getContainerConcurrency(),limits.getContainerConcurrency(),30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),r->{Thread t=new Thread(r,"attachment-validation");t.setDaemon(true);return t;});
            workers.allowCoreThreadTimeOut(true);
        }
        return workers;
    }
    /** On success caller owns the returned exact path; every failure is cleaned by worker and caller coordination. */
    public ValidatedAttachment validate(InputStream input,Long declaredLength,PrepareUploadRequest descriptor,AttachmentLimits limits,Path tempDirectory) {
        PrepareUploadRequest safe=AttachmentDescriptors.requireValid(descriptor,limits);
        if(input==null || tempDirectory==null || declaredLength!=null && declaredLength.longValue()!=safe.getSizeBytes())throw invalid();
        TempFileHandoff handoff=new TempFileHandoff();
        Future<ValidatedAttachment> future;
        try { future=workers(limits).submit(()->receive(input,safe,limits,tempDirectory,handoff)); }
        catch(RejectedExecutionException ex) {throw new AttachmentException(429,"FILE_LIMIT_REACHED");}
        try {
            ValidatedAttachment result=future.get(limits.getReceiveDeadlineSeconds(),TimeUnit.SECONDS);
            if(!handoff.accept()) {abandon(handoff);throw invalid();}
            return result;
        }
        catch(InterruptedException ex) {abandon(handoff);future.cancel(true);Thread.currentThread().interrupt();throw invalid();}
        catch(TimeoutException ex) {abandon(handoff);future.cancel(true);throw invalid();}
        catch(ExecutionException ex) {abandon(handoff);if(ex.getCause() instanceof AttachmentException)throw (AttachmentException)ex.getCause();throw invalid();}
    }
    /** Caller gives up: claim the exact path from the shared state and clean whatever it still owns. */
    private static void abandon(TempFileHandoff handoff) {
        Path victim=handoff.abandon();
        if(victim!=null)try{Files.deleteIfExists(victim);}catch(IOException ignored){}
    }
    private ValidatedAttachment receive(InputStream input,PrepareUploadRequest d,AttachmentLimits limits,Path directory,TempFileHandoff handoff) {
        Path file=null;
        try {
            Path root=directory.toAbsolutePath().normalize();Files.createDirectories(root);
            if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw invalid();
            file=Files.createTempFile(root,"attachment-",".tmp");
            if(!handoff.offerFile(file))throw invalid();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0;
            try(OutputStream out=Files.newOutputStream(file)) {
                byte[] buffer=new byte[8192];int read;
                while((read=input.read(buffer))!=-1) {
                    if(handoff.isAbandoned()||Thread.currentThread().isInterrupted())throw invalid();
                    if(read==0)continue;
                    length+=read;if(length>limits.getMaxBytes())throw new AttachmentException(413,"FILE_TOO_LARGE");
                    if(length>d.getSizeBytes())throw invalid();out.write(buffer,0,read);digest.update(buffer,0,read);
                }
            }
            if(length!=d.getSizeBytes() || !MessageDigest.isEqual(hex(digest.digest()).getBytes("US-ASCII"),d.getSha256().getBytes("US-ASCII")))throw invalid();
            String actual=verifyFormat(file,limits,root);
            if(!actual.equals(d.getContentType()))throw new AttachmentException(415,"FILE_TYPE_UNSUPPORTED");
            ValidatedAttachment result=new ValidatedAttachment(file,d.getFileName(),actual,length,d.getSha256());
            if(!handoff.offerResult(result))throw invalid();
            return result;
        } catch(AttachmentException ex) {throw ex;}
        catch(Exception ex) {throw invalid();}
        finally {if(!handoff.handedOver() && file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){} }
    }
    private String verifyFormat(Path file,AttachmentLimits limits,Path directory) throws Exception {
        byte[] signature=new byte[8];try(InputStream in=Files.newInputStream(file)){if(in.read(signature)<5)throw invalid();}
        if(signature[0]=='%' && signature[1]=='P' && signature[2]=='D' && signature[3]=='F' && signature[4]=='-') {
            try(PDDocument pdf=PDDocument.load(file.toFile(),MemoryUsageSetting.setupTempFileOnly().setTempDir(directory.toFile()))) {
                if(pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>limits.getMaxPdfPages())throw invalid();
                Set<COSBase> seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
                inspect(pdf.getDocument().getTrailer(),seen,0);
                for(COSObject object:pdf.getDocument().getObjects())inspect(object,seen,0);
            }
            return "application/pdf";
        }
        boolean png=Arrays.equals(signature,new byte[]{(byte)137,80,78,71,13,10,26,10});
        boolean jpeg=(signature[0]&255)==255 && (signature[1]&255)==216 && (signature[2]&255)==255;
        if(!png && !jpeg)throw new AttachmentException(415,"FILE_TYPE_UNSUPPORTED");
        try(ImageInputStream image=ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(image);if(!readers.hasNext())throw invalid();
            ImageReader reader=readers.next();
            try {
                reader.setInput(image,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>limits.getMaxPixels())throw invalid();
                if(reader.read(0)==null)throw invalid();
            }finally{reader.dispose();}
        }
        return png?"image/png":"image/jpeg";
    }
    private void inspect(COSBase value,Set<COSBase> seen,int depth) {
        if(value==null || seen.contains(value))return;
        if(depth>64 || seen.size()>=100000 || Thread.currentThread().isInterrupted())throw invalid();
        seen.add(value);
        if(value instanceof COSObject) {inspect(((COSObject)value).getObject(),seen,depth+1);return;}
        if(value instanceof COSDictionary) {
            COSDictionary dictionary=(COSDictionary)value;
            String action=dictionary.getNameAsString(COSName.S);
            if("JavaScript".equals(action)||"Launch".equals(action))throw invalid();
            if("EmbeddedFile".equals(dictionary.getNameAsString(COSName.TYPE)))throw invalid();
            for(COSName key:dictionary.keySet()) {
                String name=key.getName();
                if("EmbeddedFiles".equals(name)||"EF".equals(name)||"JS".equals(name)||"JavaScript".equals(name)||"OpenAction".equals(name)||"AA".equals(name))throw invalid();
                inspect(dictionary.getItem(key),seen,depth+1);
            }
        } else if(value instanceof COSArray)for(COSBase item:(COSArray)value)inspect(item,seen,depth+1);
    }
    private static String hex(byte[] bytes) {StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    private static AttachmentException invalid(){return new AttachmentException(400,"FILE_INVALID");}
    @PreDestroy public synchronized void close(){if(workers!=null)workers.shutdownNow();}
}
