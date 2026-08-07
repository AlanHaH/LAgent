package com.adaptivelearning.evaluation.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("assessment")
public class AssessmentEntity extends BaseEntity {
    private String publicId;
    private Long ownerUserId;
    private String type;
    private String title;
    private String status;
    private Integer durationMinutes;
    private Integer maxAttempts;
    private BigDecimal totalScore;
    private BigDecimal passScore;
    private String scopeJson;
    /** 当前用户最近一次作答（非持久化，由列表接口填充，供前端区分 开始/继续/查看结果） */
    @TableField(exist = false)
    private String lastAttemptPublicId;
    @TableField(exist = false)
    private String lastAttemptStatus;
}
