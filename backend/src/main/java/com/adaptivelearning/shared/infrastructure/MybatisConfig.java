package com.adaptivelearning.shared.infrastructure;

import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
public class MybatisConfig {
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    MetaObjectHandler auditMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                Instant now = Instant.now();
                long userId = SecurityUtils.currentUserIdOrSystem();
                strictInsertFill(metaObject, "createdAt", Instant.class, now);
                strictInsertFill(metaObject, "updatedAt", Instant.class, now);
                strictInsertFill(metaObject, "createdBy", Long.class, userId);
                strictInsertFill(metaObject, "updatedBy", Long.class, userId);
                strictInsertFill(metaObject, "version", Integer.class, 0);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // 不能用 strictUpdateFill：它只在实体字段为 null 时填充，而乐观锁更新
                // 加载的实体带着旧值，导致 updatedAt/updatedBy 永远停留在插入时刻。
                setFieldValByName("updatedAt", Instant.now(), metaObject);
                setFieldValByName("updatedBy", SecurityUtils.currentUserIdOrSystem(), metaObject);
            }
        };
    }
}

