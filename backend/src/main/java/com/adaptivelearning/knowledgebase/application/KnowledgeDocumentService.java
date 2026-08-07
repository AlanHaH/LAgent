package com.adaptivelearning.knowledgebase.application;

import com.adaptivelearning.knowledgebase.domain.*;
import com.adaptivelearning.knowledgebase.infrastructure.KnowledgeMappers.*;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.shared.web.RequestIdFilter;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;

@Service @RequiredArgsConstructor @Slf4j
public class KnowledgeDocumentService {
  private final SpaceMapper spaceMapper;private final CategoryMapper categoryMapper;private final StoredObjectMapper objectMapperDb;private final DocumentMapper documentMapper;private final DocumentVersionMapper versionMapper;private final ChunkMapper chunkMapper;private final DocumentJobMapper jobMapper;private final DocumentDeletionTokenMapper deletionMapper;
  private final FileSecurityScanner scanner;private final DocumentChunker chunker;private final TextVectorizer vectorizer;private final HashingService hashing;private final ObjectMapper json;private final AuditService audit;private final PythonAiServiceClient pythonAi;private final JdbcTemplate jdbc;private final PlatformTransactionManager transactionManager;private final SecureRandom random=new SecureRandom();
  @Autowired @Qualifier("documentProcessingExecutor") private Executor documentProcessingExecutor;
  @Value("${app.storage.root:./data/objects}")private String storageRoot;@Value("${app.rag.chunk-target-chars:600}")private int targetChars;@Value("${app.rag.chunk-max-chars:1600}")private int maxChars;
  public record DocumentDetail(KnowledgeDocumentEntity document,List<DocumentVersionEntity> versions,List<DocumentJobEntity> jobs){}
  public record DeleteToken(String token,Instant expiresAt){}
  public record CategoryView(String id,String parentId,String name,String path,Integer sortNo,Integer version){}
  public record ChunkView(Integer chunkNo,String text,Integer tokenCount,Integer pageFrom,Integer pageTo,Integer paragraphFrom,Integer paragraphTo,String titlePathJson){}

  public List<KnowledgeSpaceEntity> spaces(){long user=SecurityUtils.currentUserId();return spaceMapper.selectList(new LambdaQueryWrapper<KnowledgeSpaceEntity>().and(q->q.eq(KnowledgeSpaceEntity::getUserId,user).or().eq(KnowledgeSpaceEntity::getVisibility,"PUBLIC")).eq(KnowledgeSpaceEntity::getStatus,"ACTIVE").orderByAsc(KnowledgeSpaceEntity::getName));}
  public KnowledgeSpaceEntity space(String id){return accessibleSpace(id);}
  public KnowledgeSpaceEntity createSpace(String name,Long directionId){if(name==null||name.isBlank()||name.length()>120)bad("知识空间名称不合法");KnowledgeSpaceEntity s=new KnowledgeSpaceEntity();s.setPublicId(UUID.randomUUID().toString());s.setUserId(SecurityUtils.currentUserId());s.setName(name.trim());s.setVisibility("PRIVATE");s.setStatus("ACTIVE");s.setDirectionId(directionId);spaceMapper.insert(s);return s;}
  public KnowledgeSpaceEntity updateSpace(String id,String name,Long directionId,Integer version){KnowledgeSpaceEntity s=ownedSpace(id);if(version==null||!version.equals(s.getVersion()))conflict();if(name!=null&&!name.isBlank())s.setName(name.trim());s.setDirectionId(directionId);if(spaceMapper.updateById(s)!=1)conflict();return ownedSpace(id);}
  public void deleteSpace(String id){
    KnowledgeSpaceEntity space=ownedSpace(id);long userId=SecurityUtils.currentUserId();
    String requestId=RequestIdFilter.currentRequestId(),clientIp=audit.currentClientIp();
    List<KnowledgeDocumentEntity> docs=new TransactionTemplate(transactionManager).execute(status->{
      space.setStatus("DELETING");spaceMapper.updateById(space);
      List<KnowledgeDocumentEntity> values=documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getSpaceId,space.getId()));
      for(KnowledgeDocumentEntity document:values){document.setStatus("DELETING");documentMapper.updateById(document);}
      return values;
    });
    try{documentProcessingExecutor.execute(()->purgeSpace(space,docs==null?List.of():docs,userId,requestId,clientIp));}
    catch(RuntimeException rejected){markSpaceDeletionFailed(space,docs==null?List.of():docs,userId,requestId,clientIp,"SERVICE_TEMPORARILY_UNAVAILABLE");}
  }
  public List<KnowledgeDocumentEntity> documents(String spaceId){KnowledgeSpaceEntity s=accessibleSpace(spaceId);List<KnowledgeDocumentEntity> result=documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getSpaceId,s.getId()).eq(!"PUBLIC".equals(s.getVisibility()),KnowledgeDocumentEntity::getOwnerUserId,SecurityUtils.currentUserId()).orderByDesc(KnowledgeDocumentEntity::getCreatedAt));result.forEach(this::attachCategory);return result;}

  public List<CategoryView> categories(String spaceId){KnowledgeSpaceEntity s=accessibleSpace(spaceId);return categoryMapper.selectList(new LambdaQueryWrapper<ResourceCategoryEntity>().eq(ResourceCategoryEntity::getSpaceId,s.getId()).orderByAsc(ResourceCategoryEntity::getPath,ResourceCategoryEntity::getSortNo)).stream().map(this::categoryView).toList();}
  @Transactional public CategoryView createCategory(String spaceId,String parentId,String name,Integer sortNo){KnowledgeSpaceEntity s=ownedSpace(spaceId);if(name==null||name.isBlank()||name.trim().length()>120)bad("分类名称不能为空且不能超过 120 字");ResourceCategoryEntity parent=parentId==null||parentId.isBlank()?null:ownedCategory(parentId,s.getId());requireUniqueCategory(s.getId(),parent==null?null:parent.getId(),name.trim(),null);ResourceCategoryEntity c=new ResourceCategoryEntity();c.setPublicId(UUID.randomUUID().toString());c.setSpaceId(s.getId());c.setParentId(parent==null?null:parent.getId());c.setName(name.trim());c.setPath((parent==null?"":parent.getPath())+"/"+name.trim());c.setSortNo(sortNo==null?0:sortNo);categoryMapper.insert(c);return categoryView(c);}
  @Transactional public CategoryView updateCategory(String spaceId,String categoryId,String name,Integer sortNo,Integer version){KnowledgeSpaceEntity s=ownedSpace(spaceId);ResourceCategoryEntity c=ownedCategory(categoryId,s.getId());if(version==null||!version.equals(c.getVersion()))conflict();String oldPath=c.getPath();if(name!=null&&!name.isBlank()){if(name.trim().length()>120)bad("分类名称不能超过 120 字");requireUniqueCategory(s.getId(),c.getParentId(),name.trim(),c.getId());String parentPath="";if(c.getParentId()!=null){ResourceCategoryEntity p=categoryMapper.selectById(c.getParentId());parentPath=p==null?"":p.getPath();}c.setName(name.trim());c.setPath(parentPath+"/"+name.trim());}if(sortNo!=null)c.setSortNo(sortNo);if(categoryMapper.updateById(c)!=1)conflict();if(!Objects.equals(oldPath,c.getPath()))jdbc.update("UPDATE resource_category SET path=CONCAT(?,SUBSTRING(path,?)) WHERE space_id=? AND path LIKE CONCAT(?,'/%') AND deleted_at IS NULL",c.getPath(),oldPath.length()+1,s.getId(),oldPath);return categoryView(ownedCategory(categoryId,s.getId()));}
  @Transactional public void deleteCategory(String spaceId,String categoryId){KnowledgeSpaceEntity s=ownedSpace(spaceId);ResourceCategoryEntity c=ownedCategory(categoryId,s.getId());Long childCount=categoryMapper.selectCount(new LambdaQueryWrapper<ResourceCategoryEntity>().eq(ResourceCategoryEntity::getParentId,c.getId()));Long documentCount=documentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getCategoryId,c.getId()));if(childCount>0||documentCount>0)bad("分类中仍有子分类或文档，不能删除");categoryMapper.deleteById(c.getId());}
  public KnowledgeDocumentEntity assignCategory(String documentId,String categoryId,Integer version){KnowledgeDocumentEntity d=ownedDocument(documentId);if(version==null||!version.equals(d.getVersion()))conflict();if(categoryId==null||categoryId.isBlank())d.setCategoryId(null);else d.setCategoryId(ownedCategory(categoryId,d.getSpaceId()).getId());if(documentMapper.updateById(d)!=1)conflict();return ownedDocument(documentId);}

  public KnowledgeDocumentEntity upload(String spaceId,MultipartFile file){KnowledgeSpaceEntity s=ownedSpace(spaceId);return store(s,null,file);}
  public KnowledgeDocumentEntity replace(String documentId,MultipartFile file){KnowledgeDocumentEntity d=ownedDocument(documentId);KnowledgeSpaceEntity s=ownedSpaceById(d.getSpaceId());return store(s,d,file);}

  private KnowledgeDocumentEntity store(KnowledgeSpaceEntity space,KnowledgeDocumentEntity existing,MultipartFile file){if(file==null||file.isEmpty())bad("上传文件不能为空");if(file.getSize()>200L*1024*1024)throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,"单文件不能超过 200 MB");
    Path root=Path.of(storageRoot).toAbsolutePath().normalize();String objectKey=SecurityUtils.currentUserId()+"/"+UUID.randomUUID();Path target=root.resolve(objectKey).normalize();if(!target.startsWith(root))bad("非法对象路径");
    try{Files.createDirectories(target.getParent());try(InputStream in=file.getInputStream()){Files.copy(in,target,StandardCopyOption.REPLACE_EXISTING);}FileSecurityScanner.ScanResult scan=scanner.verify(target,file.getOriginalFilename(),file.getContentType());String hash=fileHash(target);
      UploadWork work=new TransactionTemplate(transactionManager).execute(status->{
      StoredObjectEntity stored=new StoredObjectEntity();stored.setOwnerUserId(SecurityUtils.currentUserId());stored.setObjectKey(objectKey);stored.setOriginalFileName(cleanName(file.getOriginalFilename()));stored.setMimeType(scan.mimeType());stored.setFileSize(file.getSize());stored.setFileHash(hash);stored.setScanStatus(scan.status());objectMapperDb.insert(stored);
      KnowledgeDocumentEntity doc=existing;if(doc==null){doc=new KnowledgeDocumentEntity();doc.setPublicId(UUID.randomUUID().toString());doc.setSpaceId(space.getId());doc.setOwnerUserId(SecurityUtils.currentUserId());doc.setDisplayName(cleanName(file.getOriginalFilename()));doc.setStatus("UPLOADED");doc.setActiveVersionNo(1);doc.setVisibility(space.getVisibility());documentMapper.insert(doc);}else{doc.setActiveVersionNo(doc.getActiveVersionNo()+1);doc.setStatus("UPLOADED");documentMapper.updateById(doc);}
      DocumentVersionEntity version=new DocumentVersionEntity();version.setDocumentId(doc.getId());version.setVersionNo(doc.getActiveVersionNo());version.setStoredObjectId(stored.getId());version.setParserVersion("tika-3.0");version.setChunkConfigJson(toJson(Map.of("targetChars",targetChars,"maxChars",maxChars,"overlapMax",100)));version.setEmbeddingModel("LOCAL_HASHED_128");version.setEmbeddingDimension(128);version.setStatus("UPLOADED");version.setFileHash(hash);version.setCreatedAt(Instant.now());versionMapper.insert(version);
      DocumentJobEntity job=new DocumentJobEntity();job.setPublicId(UUID.randomUUID().toString());job.setDocumentVersionId(version.getId());job.setJobType("FULL_PIPELINE");job.setStatus("RUNNING");job.setIdempotencyKey("doc-"+version.getId()+"-full");job.setAttempts(1);job.setCreatedAt(Instant.now());job.setUpdatedAt(Instant.now());jobMapper.insert(job);
      return new UploadWork(doc,version,stored,job);
      });
      if(work==null)throw new IllegalStateException("upload transaction returned null");
      try{documentProcessingExecutor.execute(()->process(work.document(),work.version(),work.stored(),target,work.job()));}
      catch(RuntimeException rejected){failParsing(work.document(),work.version(),work.job(),"SERVICE_TEMPORARILY_UNAVAILABLE","文档处理队列已满，请稍后重试");}
      audit.record("DOCUMENT_UPLOAD","KNOWLEDGE_DOCUMENT",work.document().getPublicId(),null,"file="+work.stored().getOriginalFileName()+",hash="+hash,"SUCCESS");return ownedDocument(work.document().getPublicId());
    }catch(BusinessException e){try{Files.deleteIfExists(target);}catch(IOException ignored){}throw e;}catch(Exception e){try{Files.deleteIfExists(target);}catch(IOException ignored){}throw new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE,"文件保存或解析失败",Map.of("reason",e.getClass().getSimpleName()));}}

  private void process(KnowledgeDocumentEntity doc,DocumentVersionEntity version,StoredObjectEntity stored,
                       Path path,DocumentJobEntity job){
    try{
      doc.setStatus("SECURITY_CHECKING");documentMapper.updateById(doc);
      doc.setStatus("PARSING");documentMapper.updateById(doc);
      version.setStatus("PARSING");versionMapper.updateById(version);

      String text=new Tika().parseToString(path.toFile());
      PythonAiServiceClient.OcrResult ocr=null;
      if(text==null||text.trim().length()<10){
        if(!"application/pdf".equalsIgnoreCase(stored.getMimeType())){
          failParsing(doc,version,job,"DOCUMENT_NO_EXTRACTABLE_TEXT","文档没有可提取文本");
          return;
        }
        if(!pythonAi.isConfigured()){
          failParsing(doc,version,job,"SERVICE_TEMPORARILY_UNAVAILABLE",
                  "扫描 PDF 需要 OCR，请先启动 Python AI 服务");
          return;
        }
        try{
          doc.setStatus("OCR_PROCESSING");documentMapper.updateById(doc);
          version.setStatus("OCR_PROCESSING");versionMapper.updateById(version);
          ocr=pythonAi.ocrPdf(path);
          text=ocr.pages().stream()
                  .map(page->"--- 第 "+page.pageNo()+" 页 ---\n"+page.text())
                  .collect(java.util.stream.Collectors.joining("\n\n"));
          version.setParserVersion("tika-3.0+"+ocr.engine());
        }catch(AiModelException error){
          failParsing(doc,version,job,error.getCode().name(),ocrFailureMessage(error.getCode()));
          return;
        }
      }

      version.setTextHash(hashing.sha256(text));version.setStatus("CHUNKING");versionMapper.updateById(version);
      doc.setStatus("CHUNKING");documentMapper.updateById(doc);
      List<ChunkDraft>chunks=ocr==null?plainChunks(text):ocrChunks(ocr);
      if(chunks.isEmpty())throw new IllegalStateException("no chunks");

      chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
              .eq(KnowledgeChunkEntity::getDocumentVersionId,version.getId()));
      List<KnowledgeChunkEntity>indexedChunks=new ArrayList<>();int no=1;
      for(ChunkDraft draft:chunks){
        String value=draft.text();
        KnowledgeChunkEntity c=new KnowledgeChunkEntity();
        c.setDocumentVersionId(version.getId());c.setChunkNo(no++);c.setText(value);
        c.setTextHash(hashing.sha256(value));c.setTokenCount(Math.max(1,value.length()/2));
        c.setTitlePathJson("[]");c.setParagraphFrom(c.getChunkNo());c.setParagraphTo(c.getChunkNo());
        c.setPageFrom(draft.pageFrom());c.setPageTo(draft.pageTo());
        c.setVectorJson(toJson(vectorizer.vector(value)));c.setCreatedAt(Instant.now());
        chunkMapper.insert(c);indexedChunks.add(c);
      }
      if(pythonAi.isConfigured()){
        PythonAiServiceClient.IndexResult result=pythonAi.index(new PythonAiServiceClient.IndexRequest(
                job.getPublicId(),doc.getOwnerUserId(),doc.getSpaceId(),doc.getId(),version.getId(),
                doc.getVisibility(),indexedChunks.stream().map(c->new PythonAiServiceClient.IndexChunk(
                        c.getId(),c.getChunkNo(),c.getText(),c.getTextHash(),List.of(),c.getPageFrom(),
                        c.getPageTo(),"zh-CN")).toList()));
        version.setEmbeddingModel(result.embeddingModel());version.setEmbeddingDimension(result.embeddingDimension());
      }
      version.setStatus("INDEXED");versionMapper.updateById(version);
      doc.setStatus("INDEXED");documentMapper.updateById(doc);
      job.setStatus("SUCCEEDED");job.setErrorCode(null);job.setErrorMessage(null);
      job.setUpdatedAt(Instant.now());jobMapper.updateById(job);
    }catch(Exception e){
      version.setStatus("INDEX_FAILED");versionMapper.updateById(version);
      doc.setStatus("INDEX_FAILED");documentMapper.updateById(doc);
      job.setStatus("FAILED");job.setErrorCode("DOCUMENT_PROCESSING_FAILED");
      job.setErrorMessage(e.getClass().getSimpleName());job.setUpdatedAt(Instant.now());jobMapper.updateById(job);
      log.warn("Document processing failed for version {}: {}",version.getId(),e.getClass().getSimpleName());
    }
  }

  private List<ChunkDraft> plainChunks(String text){
    return chunker.chunk(text,targetChars,maxChars).stream()
            .map(value->new ChunkDraft(value,null,null)).toList();
  }

  private List<ChunkDraft> ocrChunks(PythonAiServiceClient.OcrResult result){
    List<ChunkDraft> chunks=new ArrayList<>();
    for(PythonAiServiceClient.OcrPage page:result.pages()){
      for(String value:chunker.chunk(page.text(),targetChars,maxChars)){
        chunks.add(new ChunkDraft(value,page.pageNo(),page.pageNo()));
      }
    }
    return chunks;
  }

  private void failParsing(KnowledgeDocumentEntity doc,DocumentVersionEntity version,DocumentJobEntity job,
                           String code,String message){
    version.setStatus("PARSE_FAILED");versionMapper.updateById(version);
    doc.setStatus("PARSE_FAILED");documentMapper.updateById(doc);
    job.setStatus("FAILED");job.setErrorCode(code);job.setErrorMessage(message);
    job.setUpdatedAt(Instant.now());jobMapper.updateById(job);
  }

  private String ocrFailureMessage(ErrorCode code){
    return switch(code){
      case DOCUMENT_NO_EXTRACTABLE_TEXT->"OCR 未识别到足够的可用文字，请检查扫描清晰度";
      case DOCUMENT_OCR_LIMIT_EXCEEDED->"扫描 PDF 超过 OCR 限制：最大 50 MB、100 页";
      case SERVICE_TEMPORARILY_UNAVAILABLE->"OCR 服务未安装或 Python AI 未启动";
      default->"扫描 PDF OCR 处理失败，请检查文件后重试";
    };
  }

  private record ChunkDraft(String text,Integer pageFrom,Integer pageTo){}
  private record UploadWork(KnowledgeDocumentEntity document,DocumentVersionEntity version,StoredObjectEntity stored,DocumentJobEntity job){}

  public DocumentDetail detail(String id){KnowledgeDocumentEntity d=ownedOrPublicDocument(id);List<DocumentVersionEntity>vs=versionMapper.selectList(new LambdaQueryWrapper<DocumentVersionEntity>().eq(DocumentVersionEntity::getDocumentId,d.getId()).orderByDesc(DocumentVersionEntity::getVersionNo));List<Long>ids=vs.stream().map(DocumentVersionEntity::getId).toList();List<DocumentJobEntity>jobs=ids.isEmpty()?List.of():jobMapper.selectList(new LambdaQueryWrapper<DocumentJobEntity>().in(DocumentJobEntity::getDocumentVersionId,ids).orderByDesc(DocumentJobEntity::getCreatedAt));return new DocumentDetail(d,vs,jobs);}
  public List<ChunkView> documentContent(String id){KnowledgeDocumentEntity d=ownedOrPublicDocument(id);DocumentVersionEntity v=versionMapper.selectOne(new LambdaQueryWrapper<DocumentVersionEntity>().eq(DocumentVersionEntity::getDocumentId,d.getId()).eq(DocumentVersionEntity::getVersionNo,d.getActiveVersionNo()));if(v==null)return List.of();return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkEntity>().eq(KnowledgeChunkEntity::getDocumentVersionId,v.getId()).orderByAsc(KnowledgeChunkEntity::getChunkNo)).stream().map(c->new ChunkView(c.getChunkNo(),c.getText(),c.getTokenCount(),c.getPageFrom(),c.getPageTo(),c.getParagraphFrom(),c.getParagraphTo(),c.getTitlePathJson())).toList();}
  public KnowledgeDocumentEntity retry(String id){KnowledgeDocumentEntity d=ownedDocument(id);DocumentVersionEntity v=versionMapper.selectOne(new LambdaQueryWrapper<DocumentVersionEntity>().eq(DocumentVersionEntity::getDocumentId,d.getId()).eq(DocumentVersionEntity::getVersionNo,d.getActiveVersionNo()));StoredObjectEntity o=objectMapperDb.selectById(v.getStoredObjectId());DocumentJobEntity job=new DocumentJobEntity();job.setPublicId(UUID.randomUUID().toString());job.setDocumentVersionId(v.getId());job.setJobType("RETRY");job.setStatus("RUNNING");job.setIdempotencyKey("doc-"+v.getId()+"-retry-"+UUID.randomUUID());job.setAttempts(1);job.setCreatedAt(Instant.now());job.setUpdatedAt(Instant.now());jobMapper.insert(job);Path path=Path.of(storageRoot).toAbsolutePath().normalize().resolve(o.getObjectKey());try{documentProcessingExecutor.execute(()->process(d,v,o,path,job));}catch(RuntimeException rejected){failParsing(d,v,job,"SERVICE_TEMPORARILY_UNAVAILABLE","文档处理队列已满，请稍后重试");}return ownedDocument(id);}

  public DeleteToken deletionRequest(String id){KnowledgeDocumentEntity d=ownedDocument(id);byte[]bytes=new byte[40];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);DocumentDeletionTokenEntity t=new DocumentDeletionTokenEntity();t.setDocumentId(d.getId());t.setUserId(SecurityUtils.currentUserId());t.setTokenHash(hashing.sha256(raw));t.setStatus("PENDING");t.setExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));t.setCreatedAt(Instant.now());deletionMapper.insert(t);return new DeleteToken(raw,t.getExpiresAt());}
  public void delete(String id,String rawToken){
    KnowledgeDocumentEntity document=ownedDocument(id);long userId=SecurityUtils.currentUserId();
    String requestId=RequestIdFilter.currentRequestId(),clientIp=audit.currentClientIp();
    new TransactionTemplate(transactionManager).executeWithoutResult(status->{
      DocumentDeletionTokenEntity token=deletionMapper.selectOne(new LambdaQueryWrapper<DocumentDeletionTokenEntity>()
              .eq(DocumentDeletionTokenEntity::getDocumentId,document.getId())
              .eq(DocumentDeletionTokenEntity::getUserId,userId)
              .eq(DocumentDeletionTokenEntity::getTokenHash,hashing.sha256(rawToken==null?"":rawToken))
              .eq(DocumentDeletionTokenEntity::getStatus,"PENDING"));
      if(token==null||token.getExpiresAt().isBefore(Instant.now()))throw new BusinessException(
              ErrorCode.PLAN_CONFIRMATION_REQUIRED,"删除确认令牌无效或已过期");
      document.setStatus("DELETING");documentMapper.updateById(document);
      token.setStatus("USED");deletionMapper.updateById(token);
    });
    try{documentProcessingExecutor.execute(()->purgeDocument(document,userId,requestId,clientIp));}
    catch(RuntimeException rejected){markDocumentDeletionFailed(document,userId,requestId,clientIp,"SERVICE_TEMPORARILY_UNAVAILABLE");}
  }

  private void purgeSpace(KnowledgeSpaceEntity space,List<KnowledgeDocumentEntity> documents,long userId,String requestId,String clientIp){
    try{
      for(KnowledgeDocumentEntity document:documents)purgeDocumentResources(document);
      space.setStatus("DELETED");spaceMapper.updateById(space);
      audit.recordAs(userId,requestId,clientIp,"SPACE_DELETE","KNOWLEDGE_SPACE",space.getPublicId(),"ACTIVE","DELETED","SUCCESS");
    }catch(Exception error){markSpaceDeletionFailed(space,documents,userId,requestId,clientIp,error.getClass().getSimpleName());}
  }

  private void purgeDocument(KnowledgeDocumentEntity document,long userId,String requestId,String clientIp){
    try{
      purgeDocumentResources(document);
      audit.recordAs(userId,requestId,clientIp,"DOCUMENT_DELETE","KNOWLEDGE_DOCUMENT",document.getPublicId(),"INDEXED","PURGED","SUCCESS");
    }catch(Exception error){markDocumentDeletionFailed(document,userId,requestId,clientIp,error.getClass().getSimpleName());}
  }

  private void purgeDocumentResources(KnowledgeDocumentEntity document)throws IOException{
    Path root=Path.of(storageRoot).toAbsolutePath().normalize();
    List<DocumentVersionEntity> versions=versionMapper.selectList(new LambdaQueryWrapper<DocumentVersionEntity>()
            .eq(DocumentVersionEntity::getDocumentId,document.getId()));
    for(DocumentVersionEntity version:versions){
      if(pythonAi.isConfigured())pythonAi.deleteIndex(document.getOwnerUserId(),version.getId());
      chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
              .eq(KnowledgeChunkEntity::getDocumentVersionId,version.getId()));
      StoredObjectEntity object=objectMapperDb.selectById(version.getStoredObjectId());
      if(object!=null){Path path=root.resolve(object.getObjectKey()).normalize();if(!path.startsWith(root))throw new IOException("unsafe object path");Files.deleteIfExists(path);}
    }
    document.setStatus("PURGED");documentMapper.updateById(document);
  }

  private void markDocumentDeletionFailed(KnowledgeDocumentEntity document,long userId,String requestId,String clientIp,String code){
    document.setStatus("DELETE_FAILED");documentMapper.updateById(document);
    audit.recordAs(userId,requestId,clientIp,"DOCUMENT_DELETE","KNOWLEDGE_DOCUMENT",document.getPublicId(),"DELETING",code,"FAILED");
  }

  private void markSpaceDeletionFailed(KnowledgeSpaceEntity space,List<KnowledgeDocumentEntity> documents,long userId,String requestId,String clientIp,String code){
    for(KnowledgeDocumentEntity document:documents){if(!"PURGED".equals(document.getStatus())){document.setStatus("DELETE_FAILED");documentMapper.updateById(document);}}
    space.setStatus("DELETE_FAILED");spaceMapper.updateById(space);
    audit.recordAs(userId,requestId,clientIp,"SPACE_DELETE","KNOWLEDGE_SPACE",space.getPublicId(),"DELETING",code,"FAILED");
  }

  public KnowledgeSpaceEntity accessibleSpace(String id){KnowledgeSpaceEntity s=spaceMapper.selectOne(new LambdaQueryWrapper<KnowledgeSpaceEntity>().eq(KnowledgeSpaceEntity::getPublicId,id).and(q->q.eq(KnowledgeSpaceEntity::getUserId,SecurityUtils.currentUserId()).or().eq(KnowledgeSpaceEntity::getVisibility,"PUBLIC")));if(s==null)notFound();return s;}
  public KnowledgeSpaceEntity ownedSpace(String id){KnowledgeSpaceEntity s=spaceMapper.selectOne(new LambdaQueryWrapper<KnowledgeSpaceEntity>().eq(KnowledgeSpaceEntity::getPublicId,id).eq(KnowledgeSpaceEntity::getUserId,SecurityUtils.currentUserId()));if(s==null)notFound();return s;}
  private KnowledgeSpaceEntity ownedSpaceById(long id){KnowledgeSpaceEntity s=spaceMapper.selectOne(new LambdaQueryWrapper<KnowledgeSpaceEntity>().eq(KnowledgeSpaceEntity::getId,id).eq(KnowledgeSpaceEntity::getUserId,SecurityUtils.currentUserId()));if(s==null)notFound();return s;}
  private ResourceCategoryEntity ownedCategory(String id,long spaceId){ResourceCategoryEntity c=categoryMapper.selectOne(new LambdaQueryWrapper<ResourceCategoryEntity>().eq(ResourceCategoryEntity::getPublicId,id).eq(ResourceCategoryEntity::getSpaceId,spaceId));if(c==null)notFound();return c;}
  private void requireUniqueCategory(long spaceId,Long parentId,String name,Long exceptId){LambdaQueryWrapper<ResourceCategoryEntity> q=new LambdaQueryWrapper<ResourceCategoryEntity>().eq(ResourceCategoryEntity::getSpaceId,spaceId).eq(ResourceCategoryEntity::getName,name).ne(exceptId!=null,ResourceCategoryEntity::getId,exceptId);if(parentId==null)q.isNull(ResourceCategoryEntity::getParentId);else q.eq(ResourceCategoryEntity::getParentId,parentId);if(categoryMapper.selectCount(q)>0)bad("同级分类名称已存在");}
  private CategoryView categoryView(ResourceCategoryEntity c){String parentId=null;if(c.getParentId()!=null){ResourceCategoryEntity p=categoryMapper.selectById(c.getParentId());if(p!=null)parentId=p.getPublicId();}return new CategoryView(c.getPublicId(),parentId,c.getName(),c.getPath(),c.getSortNo(),c.getVersion());}
  public KnowledgeDocumentEntity ownedDocument(String id){KnowledgeDocumentEntity d=documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getPublicId,id).eq(KnowledgeDocumentEntity::getOwnerUserId,SecurityUtils.currentUserId()));if(d==null)notFound();attachCategory(d);return d;}
  private void attachCategory(KnowledgeDocumentEntity d){if(d.getCategoryId()==null)return;ResourceCategoryEntity c=categoryMapper.selectById(d.getCategoryId());if(c!=null){d.setCategoryPublicId(c.getPublicId());d.setCategoryName(c.getName());}}
  private KnowledgeDocumentEntity ownedOrPublicDocument(String id){KnowledgeDocumentEntity d=documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getPublicId,id).and(q->q.eq(KnowledgeDocumentEntity::getOwnerUserId,SecurityUtils.currentUserId()).or().eq(KnowledgeDocumentEntity::getVisibility,"PUBLIC")));if(d==null)notFound();return d;}
  private String fileHash(Path path)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[]b=new byte[8192];for(int n;(n=in.read(b))>0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
  private String cleanName(String n){String v=n==null?"document":n.replace("\\","_").replace("/","_").replaceAll("[\\p{Cntrl}]","").trim();return v.substring(0,Math.min(255,v.length()));}
  private String toJson(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
  private void bad(String m){throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,m);}private void conflict(){throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT,"资源版本冲突，请刷新后重试");}private void notFound(){throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"资源不存在");}
}
