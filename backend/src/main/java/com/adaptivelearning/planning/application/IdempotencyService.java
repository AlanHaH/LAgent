package com.adaptivelearning.planning.application;

import com.adaptivelearning.planning.domain.IdempotencyRecordEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.IdempotencyMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service @RequiredArgsConstructor
public class IdempotencyService {
    private final IdempotencyMapper mapper;
    private final HashingService hashing;

    public IdempotencyRecordEntity find(long userId,String key,String requestBody){
        String keyHash=hashing.sha256(key), requestHash=hashing.sha256(requestBody);
        IdempotencyRecordEntity r=mapper.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
                .eq(IdempotencyRecordEntity::getUserId,userId).eq(IdempotencyRecordEntity::getKeyHash,keyHash));
        if(r!=null&&!r.getRequestHash().equals(requestHash))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,"同一幂等键不能用于不同请求体");
        return r;
    }

    public void save(long userId,String key,String requestBody,String responseRef){
        IdempotencyRecordEntity r=new IdempotencyRecordEntity();r.setUserId(userId);r.setKeyHash(hashing.sha256(key));
        r.setRequestHash(hashing.sha256(requestBody));r.setResponseRef(responseRef);r.setStatus("COMPLETED");
        r.setCreatedAt(Instant.now());r.setExpiresAt(Instant.now().plus(Duration.ofDays(2)));mapper.insert(r);
    }
}

