package com.adaptivelearning.knowledgebase.application;
import com.adaptivelearning.shared.exception.*;import org.apache.tika.Tika;import org.springframework.stereotype.Component;import java.io.*;import java.nio.file.Path;import java.util.*;
public interface FileSecurityScanner {String verify(Path path,String originalName,String declaredType)throws IOException;
 @Component class SignatureScanner implements FileSecurityScanner {private static final Set<String>ALLOWED=Set.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain","text/markdown","application/zip");private final Tika tika=new Tika();
  public String verify(Path path,String name,String declared)throws IOException{String detected=tika.detect(path);String lower=name.toLowerCase(Locale.ROOT);boolean ext=lower.endsWith(".pdf")||lower.endsWith(".docx")||lower.endsWith(".md")||lower.endsWith(".txt");if(!ext||(!ALLOWED.contains(detected)&&!detected.startsWith("text/")))throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,"仅支持 PDF、DOCX、Markdown 和 TXT 文件");if(detected.contains("executable")||detected.contains("x-msdownload"))throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,"文件签名与扩展名不符");return detected;}
 }
}
