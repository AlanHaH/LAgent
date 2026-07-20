package com.adaptivelearning.knowledgebase.domain;
import com.adaptivelearning.shared.domain.BaseEntity;import com.baomidou.mybatisplus.annotation.TableName;import lombok.Getter;import lombok.Setter;
@Getter @Setter @TableName("stored_object") public class StoredObjectEntity extends BaseEntity {private Long ownerUserId;private String objectKey;private String originalFileName;private String mimeType;private Long fileSize;private String fileHash;private String scanStatus;}
