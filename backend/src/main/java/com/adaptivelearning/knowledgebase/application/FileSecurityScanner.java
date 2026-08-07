package com.adaptivelearning.knowledgebase.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public interface FileSecurityScanner {
    record ScanResult(String mimeType,String status) { }
    ScanResult verify(Path path,String originalName,String declaredType) throws IOException;

    @Component
    class SignatureScanner implements FileSecurityScanner {
        private static final long MAX_UNCOMPRESSED_BYTES=400L*1024*1024;
        private final Tika tika=new Tika();

        @Value("${app.storage.clamav-host:}") private String clamavHost;
        @Value("${app.storage.clamav-port:3310}") private int clamavPort;
        @Value("${app.storage.antivirus-required:false}") private boolean antivirusRequired;

        @Override
        public ScanResult verify(Path path,String name,String declared) throws IOException {
            String safeName=name==null?"":name.toLowerCase(Locale.ROOT);
            String detected=tika.detect(path);
            if(safeName.endsWith(".pdf")) verifyPdf(path,detected);
            else if(safeName.endsWith(".docx")) verifyDocx(path);
            else if(safeName.endsWith(".md")||safeName.endsWith(".txt")) verifyText(path,detected);
            else throw unsupported();
            if(clamavHost==null||clamavHost.isBlank()){
                if(antivirusRequired)throw new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE,
                        "生产环境要求病毒扫描，但 ClamAV 尚未配置");
                return new ScanResult(detected,"STRUCTURE_VERIFIED");
            }
            scanWithClamAv(path);
            return new ScanResult(detected,"CLEAN");
        }

        private void verifyPdf(Path path,String detected) throws IOException {
            byte[] header=new byte[5];
            try(InputStream input=Files.newInputStream(path)){
                if(input.read(header)!=5||!"%PDF-".equals(new String(header,java.nio.charset.StandardCharsets.US_ASCII)))
                    throw unsupported();
            }
            if(!"application/pdf".equalsIgnoreCase(detected))throw unsupported();
        }

        private void verifyDocx(Path path) throws IOException {
            long total=0;boolean contentTypes=false,document=false;
            try(ZipFile zip=new ZipFile(path.toFile())){
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();
                    String value=entry.getName().replace('\\','/');
                    if(value.startsWith("/")||value.contains("../"))throw unsupported();
                    if("[Content_Types].xml".equals(value))contentTypes=true;
                    if("word/document.xml".equals(value))document=true;
                    long size=Math.max(0,entry.getSize());total+=size;
                    if(total>MAX_UNCOMPRESSED_BYTES||entry.getCompressedSize()>0&&size/entry.getCompressedSize()>100)
                        throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,"DOCX 解压后体积或压缩比异常");
                }
            }
            if(!contentTypes||!document)throw unsupported();
        }

        private void verifyText(Path path,String detected) throws IOException {
            if(!detected.startsWith("text/")&&!"application/octet-stream".equals(detected))throw unsupported();
            try(InputStream input=Files.newInputStream(path)){
                byte[] sample=input.readNBytes(8192);
                for(byte value:sample)if(value==0)throw unsupported();
            }
        }

        private void scanWithClamAv(Path path) throws IOException {
            try(Socket socket=new Socket()){
                socket.connect(new InetSocketAddress(clamavHost,clamavPort),5000);
                socket.setSoTimeout(120000);
                DataOutputStream output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                output.write("zINSTREAM\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                try(InputStream input=Files.newInputStream(path)){
                    byte[] buffer=new byte[8192];
                    for(int read;(read=input.read(buffer))>0;){output.writeInt(read);output.write(buffer,0,read);}
                }
                output.writeInt(0);output.flush();
                String response=new String(socket.getInputStream().readNBytes(4096),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if(response.contains("FOUND"))throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,"文件未通过病毒扫描");
                if(!response.contains("OK"))throw new IOException("ClamAV scan failed");
            }
        }

        private BusinessException unsupported(){
            return new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,"仅支持结构合法的 PDF、DOCX、Markdown 和 TXT 文件");
        }
    }
}
