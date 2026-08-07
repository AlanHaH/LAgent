package com.adaptivelearning.knowledgebase.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("resource_category")
public class ResourceCategoryEntity extends BaseEntity {
    private String publicId;
    private Long spaceId;
    private Long parentId;
    private String name;
    private String path;
    private Integer sortNo;
}
