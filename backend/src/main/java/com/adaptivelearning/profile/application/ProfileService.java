package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.domain.*;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.*;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final UserProfileMapper profileMapper;
    private final ProfileDirectionMapper directionMapper;
    private final LearningPreferenceMapper preferenceMapper;
    private final AvailabilityRuleMapper ruleMapper;
    private final AvailabilityExceptionMapper exceptionMapper;
    private final SelfAssessmentMapper selfAssessmentMapper;
    private final ProfileVersionMapper versionMapper;
    private final ProfileGenerationJobMapper jobMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public record DirectionInput(Long directionId, String customDirection, String currentStage, boolean primary) {}
    public record ProfileInput(String timezone, int weekStart, int planPeriodDays, String backgroundText,
                               List<DirectionInput> directions, Integer version) {}
    public record DirectionView(Long directionId, String name, String customDirection, String sourceType,
                                String currentStage, boolean primary) {}
    public record ProfileView(String timezone, int weekStart, int planPeriodDays, String backgroundText,
                              String status, int currentVersionNo, int version, List<DirectionView> directions,
                              PreferenceView preference) {}
    public record PreferenceInput(List<String> contentModes, String guidanceStyle, String taskGranularity,
                                  int focusMinutes, BigDecimal capacityRatio, int difficultyMin,
                                  int difficultyMax, Map<String, Boolean> reminders, Integer version) {}
    public record PreferenceView(List<String> contentModes, String guidanceStyle, String taskGranularity,
                                 int focusMinutes, BigDecimal capacityRatio, int difficultyMin,
                                 int difficultyMax, Map<String, Boolean> reminders, int version) {}

    public ProfileView get() {
        UserProfileEntity profile = findProfile();
        if (profile == null) return null;
        List<ProfileDirectionEntity> dirs = directionMapper.selectList(new LambdaQueryWrapper<ProfileDirectionEntity>()
                .eq(ProfileDirectionEntity::getProfileId, profile.getId()));
        List<DirectionView> views = dirs.stream().map(d -> new DirectionView(d.getDirectionId(),
                d.getDirectionId() == null ? null : directionName(d.getDirectionId()), d.getCustomDirection(),
                d.getSourceType(), d.getCurrentStage(), Boolean.TRUE.equals(d.getIsPrimary()))).toList();
        return new ProfileView(profile.getTimezone(), profile.getWeekStart(), profile.getPlanPeriodDays(),
                profile.getBackgroundText(), profile.getProfileStatus(), profile.getCurrentVersionNo(),
                profile.getVersion(), views, preferenceView());
    }

    @Transactional
    public ProfileView save(ProfileInput input) {
        validateProfile(input);
        long userId = SecurityUtils.currentUserId();
        UserProfileEntity profile = findProfile();
        boolean create = profile == null;
        if (create) {
            profile = new UserProfileEntity();
            profile.setUserId(userId);
            profile.setProfileStatus("DRAFT");
            profile.setCurrentVersionNo(0);
        } else if (input.version() == null || !input.version().equals(profile.getVersion())) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "画像已更新，请刷新后重试");
        }
        profile.setTimezone(input.timezone());
        profile.setWeekStart(input.weekStart());
        profile.setPlanPeriodDays(input.planPeriodDays());
        profile.setBackgroundText(input.backgroundText());
        if (create) profileMapper.insert(profile); else if (profileMapper.updateById(profile) != 1) conflict();
        directionMapper.delete(new LambdaQueryWrapper<ProfileDirectionEntity>()
                .eq(ProfileDirectionEntity::getProfileId, profile.getId()));
        for (DirectionInput item : input.directions()) {
            ProfileDirectionEntity entity = new ProfileDirectionEntity();
            entity.setProfileId(profile.getId());
            entity.setDirectionId(item.directionId());
            entity.setCustomDirection(item.customDirection());
            entity.setSourceType(item.directionId() == null ? "CUSTOM" : "CATALOG");
            entity.setCurrentStage(item.currentStage());
            entity.setIsPrimary(item.primary());
            entity.setStatus("ACTIVE");
            directionMapper.insert(entity);
        }
        audit.record(create ? "PROFILE_CREATE" : "PROFILE_UPDATE", "USER_PROFILE", profile.getId().toString(),
                null, "timezone=" + profile.getTimezone() + ",directions=" + input.directions().size(), "SUCCESS");
        return get();
    }

    @Transactional
    public PreferenceView savePreference(PreferenceInput input) {
        Set<String> modes = Set.of("TEXT", "VIDEO", "AUDIO", "PRACTICE", "PROJECT");
        if (input.contentModes() == null || input.contentModes().isEmpty() || !modes.containsAll(input.contentModes())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "至少选择一种有效内容形式");
        }
        if (input.focusMinutes() < 10 || input.focusMinutes() > 180 || input.capacityRatio().compareTo(new BigDecimal("0.60")) < 0
                || input.capacityRatio().compareTo(new BigDecimal("0.95")) > 0
                || input.difficultyMin() < 1 || input.difficultyMax() > 5 || input.difficultyMin() > input.difficultyMax()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "学习偏好参数超出允许范围");
        }
        long userId = SecurityUtils.currentUserId();
        LearningPreferenceEntity entity = preferenceMapper.selectOne(new LambdaQueryWrapper<LearningPreferenceEntity>()
                .eq(LearningPreferenceEntity::getUserId, userId));
        boolean create = entity == null;
        if (create) { entity = new LearningPreferenceEntity(); entity.setUserId(userId); }
        else if (input.version() == null || !input.version().equals(entity.getVersion())) conflict();
        entity.setContentModesJson(json(input.contentModes()));
        entity.setGuidanceStyle(input.guidanceStyle());
        entity.setTaskGranularity(input.taskGranularity());
        entity.setFocusMinutes(input.focusMinutes());
        entity.setCapacityRatio(input.capacityRatio());
        entity.setDifficultyMin(input.difficultyMin());
        entity.setDifficultyMax(input.difficultyMax());
        entity.setReminderJson(json(input.reminders() == null ? Map.of() : input.reminders()));
        if (create) preferenceMapper.insert(entity); else if (preferenceMapper.updateById(entity) != 1) conflict();
        return preferenceView();
    }

    @Transactional
    public List<AvailabilityPolicy.NormalizedSlot> saveAvailability(List<AvailabilityPolicy.Slot> input) {
        if (findProfile() == null) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先创建画像草稿");
        List<AvailabilityPolicy.NormalizedSlot> slots = AvailabilityPolicy.normalizeAndValidate(input);
        long userId = SecurityUtils.currentUserId();
        ruleMapper.delete(new LambdaQueryWrapper<AvailabilityRuleEntity>().eq(AvailabilityRuleEntity::getUserId, userId));
        for (var slot : slots) {
            AvailabilityRuleEntity entity = new AvailabilityRuleEntity();
            entity.setUserId(userId); entity.setWeekday(slot.weekday()); entity.setStartTime(slot.start());
            entity.setEndTime(slot.end()); entity.setAvailableMinutes(slot.minutes()); entity.setEnergyLevel(slot.energyLevel());
            ruleMapper.insert(entity);
        }
        return slots;
    }

    public Map<String, Object> availability() {
        long userId = SecurityUtils.currentUserId();
        List<AvailabilityRuleEntity> rules = ruleMapper.selectList(new LambdaQueryWrapper<AvailabilityRuleEntity>()
                .eq(AvailabilityRuleEntity::getUserId, userId).orderByAsc(AvailabilityRuleEntity::getWeekday,
                        AvailabilityRuleEntity::getStartTime));
        List<AvailabilityExceptionEntity> exceptions = exceptionMapper.selectList(new LambdaQueryWrapper<AvailabilityExceptionEntity>()
                .eq(AvailabilityExceptionEntity::getUserId, userId).ge(AvailabilityExceptionEntity::getLocalDate, LocalDate.now())
                .le(AvailabilityExceptionEntity::getLocalDate, LocalDate.now().plusDays(30)));
        Map<LocalDate, Integer> exceptionByDate = new HashMap<>();
        exceptions.forEach(e -> exceptionByDate.put(e.getLocalDate(), e.getAvailableMinutes()));
        List<Map<String, Object>> preview = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDate date = LocalDate.now().plusDays(i);
            int minutes = exceptionByDate.getOrDefault(date, rules.stream()
                    .filter(r -> r.getWeekday() == date.getDayOfWeek().getValue())
                    .mapToInt(AvailabilityRuleEntity::getAvailableMinutes).sum());
            preview.add(Map.of("date", date, "availableMinutes", minutes,
                    "source", exceptionByDate.containsKey(date) ? "EXCEPTION" : "WEEKLY_RULE"));
        }
        return Map.of("rules", rules, "exceptions", exceptions, "preview", preview);
    }

    public void saveException(LocalDate date, int minutes, String reason) {
        if (minutes < 0 || minutes > 960 || date.isBefore(LocalDate.now().minusDays(1)) || date.isAfter(LocalDate.now().plusYears(1))) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "日期例外超出允许范围");
        }
        long userId = SecurityUtils.currentUserId();
        AvailabilityExceptionEntity entity = exceptionMapper.selectOne(new LambdaQueryWrapper<AvailabilityExceptionEntity>()
                .eq(AvailabilityExceptionEntity::getUserId, userId).eq(AvailabilityExceptionEntity::getLocalDate, date));
        if (entity == null) { entity = new AvailabilityExceptionEntity(); entity.setUserId(userId); entity.setLocalDate(date); }
        entity.setAvailableMinutes(minutes); entity.setReason(reason);
        if (entity.getId() == null) exceptionMapper.insert(entity); else exceptionMapper.updateById(entity);
    }

    public SelfAssessmentEntity addSelfAssessment(long knowledgePointId, int level, LocalDate lastStudiedAt, String note) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE id=? AND status='ACTIVE'", Integer.class, knowledgePointId);
        if (count == null || count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识点不存在或不可用");
        if (level < 0 || level > 5) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自评等级必须为 0～5");
        SelfAssessmentEntity entity = new SelfAssessmentEntity();
        entity.setUserId(SecurityUtils.currentUserId()); entity.setKnowledgePointId(knowledgePointId); entity.setLevel(level);
        entity.setLastStudiedAt(lastStudiedAt); entity.setNote(note); entity.setAssessedAt(Instant.now());
        selfAssessmentMapper.insert(entity);
        return entity;
    }

    @Transactional
    public ProfileGenerationJobEntity generate() {
        UserProfileEntity profile = findProfile();
        if (profile == null) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像基本信息尚未填写");
        ProfileGenerationJobEntity job = new ProfileGenerationJobEntity();
        job.setPublicId(UUID.randomUUID().toString()); job.setUserId(SecurityUtils.currentUserId());
        job.setStatus("RUNNING"); job.setCreatedAt(Instant.now()); jobMapper.insert(job);
        List<SelfAssessmentEntity> assessments = selfAssessmentMapper.selectList(new LambdaQueryWrapper<SelfAssessmentEntity>()
                .eq(SelfAssessmentEntity::getUserId, profile.getUserId()));
        BigDecimal confidence = assessments.isEmpty() ? new BigDecimal("0.10") : new BigDecimal("0.20");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("timezone", profile.getTimezone()); snapshot.put("generatedAt", Instant.now());
        snapshot.put("source", Map.of("profileVersion", profile.getVersion(), "selfAssessmentCount", assessments.size()));
        snapshot.put("confidence", confidence); snapshot.put("recommendedDifficulty", assessments.isEmpty() ? 1 : 2);
        snapshot.put("dailyRecommendedTasks", 2);
        snapshot.put("riskNotices", assessments.isEmpty() ? List.of("尚无诊断或自评证据，当前画像为低置信度") : List.of("当前仅含自评证据，建议完成诊断"));
        ProfileVersionEntity version = new ProfileVersionEntity();
        version.setProfileId(profile.getId()); version.setVersionNo(profile.getCurrentVersionNo() + 1);
        version.setSnapshotJson(json(snapshot)); version.setConfidence(confidence); version.setTriggerType("USER_REQUEST");
        version.setTriggerEventId(job.getPublicId()); version.setCreatedAt(Instant.now()); version.setCreatedBy(profile.getUserId());
        versionMapper.insert(version);
        profile.setCurrentVersionNo(version.getVersionNo()); profile.setProfileStatus("GENERATED"); profileMapper.updateById(profile);
        job.setProfileVersionId(version.getId()); job.setStatus("SUCCEEDED"); job.setFinishedAt(Instant.now()); jobMapper.updateById(job);
        return job;
    }

    public ProfileGenerationJobEntity getJob(String publicId) {
        ProfileGenerationJobEntity job = jobMapper.selectOne(new LambdaQueryWrapper<ProfileGenerationJobEntity>()
                .eq(ProfileGenerationJobEntity::getPublicId, publicId)
                .eq(ProfileGenerationJobEntity::getUserId, SecurityUtils.currentUserId()));
        if (job == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "画像生成作业不存在");
        return job;
    }

    public List<ProfileVersionEntity> versions() {
        UserProfileEntity profile = findProfile();
        if (profile == null) return List.of();
        return versionMapper.selectList(new LambdaQueryWrapper<ProfileVersionEntity>()
                .eq(ProfileVersionEntity::getProfileId, profile.getId()).orderByDesc(ProfileVersionEntity::getVersionNo));
    }

    private UserProfileEntity findProfile() {
        return profileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, SecurityUtils.currentUserId()));
    }

    private PreferenceView preferenceView() {
        LearningPreferenceEntity p = preferenceMapper.selectOne(new LambdaQueryWrapper<LearningPreferenceEntity>()
                .eq(LearningPreferenceEntity::getUserId, SecurityUtils.currentUserId()));
        if (p == null) return null;
        try {
            return new PreferenceView(objectMapper.readValue(p.getContentModesJson(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                    p.getGuidanceStyle(), p.getTaskGranularity(), p.getFocusMinutes(), p.getCapacityRatio(),
                    p.getDifficultyMin(), p.getDifficultyMax(), objectMapper.readValue(p.getReminderJson(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class)), p.getVersion());
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void validateProfile(ProfileInput input) {
        try { ZoneId.of(input.timezone()); } catch (Exception e) { throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "无效的 IANA 时区"); }
        if (input.weekStart() < 1 || input.weekStart() > 7 || input.planPeriodDays() < 1 || input.planPeriodDays() > 365
                || input.backgroundText() != null && input.backgroundText().length() > 2000
                || input.directions() == null || input.directions().isEmpty()
                || input.directions().stream().filter(DirectionInput::primary).count() != 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像字段不完整或主方向数量不为 1");
        }
        for (DirectionInput d : input.directions()) {
            if (d.directionId() == null && (d.customDirection() == null || d.customDirection().isBlank())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自定义方向名称不能为空");
            }
            if (d.directionId() != null) directionName(d.directionId());
        }
    }

    private String directionName(long id) {
        List<String> names = jdbc.query("SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE'",
                (rs, row) -> rs.getString(1), id);
        if (names.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "学习方向不存在或已停用");
        return names.get(0);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void conflict() { throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资源版本冲突，请刷新后重试"); }
}

