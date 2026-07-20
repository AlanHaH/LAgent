package com.adaptivelearning.support.domain;import com.adaptivelearning.shared.domain.BaseEntity;import com.baomidou.mybatisplus.annotation.TableName;import lombok.Getter;import lombok.Setter;
@Getter @Setter @TableName("notification_preference") public class NotificationPreferenceEntity extends BaseEntity {private Long userId;private String preferenceJson;}
