package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.domain.*;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.*;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private static final Set<String> DOCUMENT_CONTENT_MODES = Set.of("TEXT", "PRACTICE");
    private static final List<String> DEFAULT_CONTENT_MODES = List.of("TEXT", "PRACTICE");

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
    private final HashingService hashing;
    private final DeterministicProfileAnalysisPolicy analysisPolicy;
    private final PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("aiBackgroundExecutor")
    private Executor aiBackgroundExecutor;

    public record DirectionInput(Long directionId, String customDirection, String currentStage, boolean primary) {}
    public record ProfileInput(String timezone, int weekStart, Integer planPeriodDays,
                               LocalDate planStartDate, LocalDate planEndDate, String backgroundText,
                               List<DirectionInput> directions, Integer version) {}
    public record DirectionView(@JsonSerialize(using = ToStringSerializer.class) Long directionId,
                                String name, String customDirection, String sourceType,
                                boolean knowledgeBaseDirection, String currentStage, boolean primary) {}
    public record ProfileView(String timezone, int weekStart, int planPeriodDays,
                              LocalDate planStartDate, LocalDate planEndDate, String backgroundText,
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
                d.getSourceType(), d.getDirectionId() != null, d.getCurrentStage(),
                Boolean.TRUE.equals(d.getIsPrimary()))).toList();
        return new ProfileView(profile.getTimezone(), profile.getWeekStart(), profile.getPlanPeriodDays(),
                profile.getPlanStartDate(), profile.getPlanEndDate(), profile.getBackgroundText(),
                profile.getProfileStatus(), profile.getCurrentVersionNo(),
                profile.getVersion(), views, preferenceView());
    }

    @Transactional
    public ProfileView save(ProfileInput input) {
        long userId = SecurityUtils.currentUserId();
        UserProfileEntity profile = findProfile();
        DateRange planDates = validateProfile(input, profile);
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
        profile.setPlanStartDate(planDates.start());
        profile.setPlanEndDate(planDates.end());
        profile.setPlanPeriodDays(planDates.days());
        profile.setBackgroundText(input.backgroundText());
        profile.setProfileStatus("DRAFT");
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
        long knowledgeBaseDirections = input.directions().stream()
                .filter(item -> item.directionId() != null).count();
        audit.record(create ? "PROFILE_CREATE" : "PROFILE_UPDATE", "USER_PROFILE", profile.getId().toString(),
                null, "timezone=" + profile.getTimezone() + ",directions=" + input.directions().size()
                        + ",knowledgeBaseDirections=" + knowledgeBaseDirections, "SUCCESS");
        invalidateActiveGenerationJobs(userId);
        return get();
    }

    @Transactional
    public PreferenceView savePreference(PreferenceInput input) {
        List<String> contentModes = validateDocumentContentModes(input.contentModes());
        if (!Set.of("SOCRATIC", "DIRECT").contains(input.guidanceStyle())
                || !Set.of("SMALL", "MEDIUM", "LARGE").contains(input.taskGranularity())
                || input.focusMinutes() < 10 || input.focusMinutes() > 180 || input.capacityRatio().compareTo(new BigDecimal("0.60")) < 0
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
        entity.setContentModesJson(json(contentModes));
        entity.setGuidanceStyle(input.guidanceStyle());
        entity.setTaskGranularity(input.taskGranularity());
        entity.setFocusMinutes(input.focusMinutes());
        entity.setCapacityRatio(input.capacityRatio());
        entity.setDifficultyMin(input.difficultyMin());
        entity.setDifficultyMax(input.difficultyMax());
        entity.setReminderJson(json(input.reminders() == null ? Map.of() : input.reminders()));
        if (create) preferenceMapper.insert(entity); else if (preferenceMapper.updateById(entity) != 1) conflict();
        markProfileDraft(userId);
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
        markProfileDraft(userId);
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

    @Transactional
    public void saveException(LocalDate date, int minutes, String reason) {
        if (minutes < 0 || minutes > 960 || date.isBefore(LocalDate.now().minusDays(1)) || date.isAfter(LocalDate.now().plusYears(1))) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "日期例外超出允许范围");
        }
        long userId = SecurityUtils.currentUserId();
        if (findProfile(userId) == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先创建画像草稿");
        }
        AvailabilityExceptionEntity entity = exceptionMapper.selectOne(new LambdaQueryWrapper<AvailabilityExceptionEntity>()
                .eq(AvailabilityExceptionEntity::getUserId, userId).eq(AvailabilityExceptionEntity::getLocalDate, date));
        if (entity == null) { entity = new AvailabilityExceptionEntity(); entity.setUserId(userId); entity.setLocalDate(date); }
        entity.setAvailableMinutes(minutes); entity.setReason(reason);
        if (entity.getId() == null) exceptionMapper.insert(entity); else exceptionMapper.updateById(entity);
        markProfileDraft(userId);
    }

    @Transactional
    public SelfAssessmentEntity addSelfAssessment(long knowledgePointId, int level, LocalDate lastStudiedAt, String note) {
        long userId = SecurityUtils.currentUserId();
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM knowledge_point knowledge
                JOIN learning_direction direction
                  ON direction.id=knowledge.direction_id
                 AND direction.status='ACTIVE' AND direction.deleted_at IS NULL
                JOIN user_profile_direction profile_direction
                  ON profile_direction.direction_id=knowledge.direction_id
                 AND profile_direction.status='ACTIVE' AND profile_direction.deleted_at IS NULL
                JOIN user_profile profile
                  ON profile.id=profile_direction.profile_id
                 AND profile.user_id=? AND profile.deleted_at IS NULL
                WHERE knowledge.id=? AND knowledge.status='ACTIVE' AND knowledge.deleted_at IS NULL
                """, Integer.class, userId, knowledgePointId);
        if (count == null || count == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识点不存在或不可用");
        if (level < 0 || level > 5) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自评等级必须为 0～5");
        SelfAssessmentEntity entity = new SelfAssessmentEntity();
        entity.setUserId(userId); entity.setKnowledgePointId(knowledgePointId); entity.setLevel(level);
        entity.setLastStudiedAt(lastStudiedAt); entity.setNote(note); entity.setAssessedAt(Instant.now());
        selfAssessmentMapper.insert(entity);
        markProfileDraft(userId);
        return entity;
    }

    public List<SelfAssessmentEntity> selfAssessments() {
        return selfAssessmentMapper.selectList(new LambdaQueryWrapper<SelfAssessmentEntity>()
                .eq(SelfAssessmentEntity::getUserId, SecurityUtils.currentUserId())
                .orderByDesc(SelfAssessmentEntity::getAssessedAt));
    }

    @Transactional
    public ProfileGenerationJobEntity generate() {
        return generate("USER_REQUEST");
    }

    @Transactional
    public ProfileGenerationJobEntity generate(String triggerType) {
        if (!Set.of("USER_REQUEST", "INTERVIEW_CONFIRM", "MANUAL_SAVE").contains(triggerType)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像版本触发类型不合法");
        }
        long userId = SecurityUtils.currentUserId();
        UserProfileEntity profile = profileMapper.selectByUserIdForUpdate(userId);
        if (profile == null) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像基本信息尚未填写");
        ProfileGenerationContext inputs = loadGenerationInputs(userId, profile);
        validateGenerationInputs(inputs);
        ProfileGenerationJobEntity active = jobMapper.selectList(new LambdaQueryWrapper<ProfileGenerationJobEntity>()
                .eq(ProfileGenerationJobEntity::getUserId, userId)
                .in(ProfileGenerationJobEntity::getStatus, "QUEUED", "RUNNING")
                .orderByAsc(ProfileGenerationJobEntity::getCreatedAt)).stream().findFirst().orElse(null);
        if (active != null) return active;
        ProfileGenerationJobEntity job = new ProfileGenerationJobEntity();
        job.setPublicId(UUID.randomUUID().toString()); job.setUserId(userId);
        job.setStatus("QUEUED"); job.setCreatedAt(Instant.now()); jobMapper.insert(job);
        String expectedContext = inputs.signature();
        Runnable submit = () -> queueProfileGeneration(job.getId(), userId, triggerType, expectedContext);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else submit.run();
        return job;
    }

    private void queueProfileGeneration(long jobId, long userId, String triggerType, String expectedContext) {
        try {
            aiBackgroundExecutor.execute(() -> runProfileGeneration(jobId, userId, triggerType, expectedContext));
        } catch (RuntimeException rejected) {
            failProfileGeneration(jobId, "SERVICE_TEMPORARILY_UNAVAILABLE");
        }
    }

    private void runProfileGeneration(long jobId, long userId, String triggerType, String expectedContext) {
        try {
        Boolean started = new TransactionTemplate(transactionManager).execute(status -> {
            ProfileGenerationJobEntity current = jobMapper.selectByIdForUpdate(jobId);
            if (current == null || !"QUEUED".equals(current.getStatus())) return false;
            current.setStatus("RUNNING");
            jobMapper.updateById(current);
            return true;
        });
        if (!Boolean.TRUE.equals(started)) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UserProfileEntity current = profileMapper.selectByUserIdForUpdate(userId);
            ProfileGenerationJobEntity job = jobMapper.selectByIdForUpdate(jobId);
            if (current == null || job == null || !"RUNNING".equals(job.getStatus())) return;
            ProfileGenerationContext inputs = loadGenerationInputs(userId, current);
            if (!expectedContext.equals(inputs.signature())) {
                failLockedJob(job, "PROFILE_CONTEXT_STALE");
                return;
            }
            validateGenerationInputs(inputs);
            DeterministicProfileAnalysisPolicy.Analysis analysis =
                    analysisPolicy.analyze(inputs.assessments().size());
            Map<String, Object> snapshot = profileSnapshot(inputs, analysis);
            ProfileVersionEntity version = new ProfileVersionEntity();
            version.setProfileId(current.getId()); version.setVersionNo(current.getCurrentVersionNo() + 1);
            version.setSnapshotJson(json(snapshot)); version.setConfidence(analysis.confidence()); version.setTriggerType(triggerType);
            version.setTriggerEventId(job.getPublicId()); version.setCreatedAt(Instant.now()); version.setCreatedBy(userId);
            versionMapper.insert(version);
            current.setCurrentVersionNo(version.getVersionNo()); current.setProfileStatus("GENERATED");
            if (profileMapper.updateById(current) != 1) conflict();
            job.setProfileVersionId(version.getId()); job.setStatus("SUCCEEDED"); job.setFinishedAt(Instant.now());
            job.setErrorCode(null); jobMapper.updateById(job);
        });
        } catch (Exception error) {
            failProfileGeneration(jobId, "PROFILE_GENERATION_FAILED");
        }
    }

    private void failProfileGeneration(long jobId, String code) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProfileGenerationJobEntity failed = jobMapper.selectByIdForUpdate(jobId);
            if (failed == null || !Set.of("QUEUED", "RUNNING").contains(failed.getStatus())) return;
            failLockedJob(failed, code);
        });
    }

    private void failLockedJob(ProfileGenerationJobEntity job, String code) {
        job.setStatus("FAILED");
        job.setErrorCode(code);
        job.setFinishedAt(Instant.now());
        jobMapper.updateById(job);
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
        return findProfile(SecurityUtils.currentUserId());
    }

    private UserProfileEntity findProfile(long userId) {
        return profileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, userId));
    }

    private void markProfileDraft(long userId) {
        profileMapper.update(null, new LambdaUpdateWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, userId)
                .set(UserProfileEntity::getProfileStatus, "DRAFT"));
        invalidateActiveGenerationJobs(userId);
    }

    private void invalidateActiveGenerationJobs(long userId) {
        jobMapper.update(null, new LambdaUpdateWrapper<ProfileGenerationJobEntity>()
                .eq(ProfileGenerationJobEntity::getUserId, userId)
                .in(ProfileGenerationJobEntity::getStatus, "QUEUED", "RUNNING")
                .set(ProfileGenerationJobEntity::getStatus, "FAILED")
                .set(ProfileGenerationJobEntity::getErrorCode, "PROFILE_CONTEXT_STALE")
                .set(ProfileGenerationJobEntity::getFinishedAt, Instant.now()));
    }

    private ProfileGenerationContext loadGenerationInputs(long userId, UserProfileEntity profile) {
        List<ProfileDirectionEntity> directions = directionMapper.selectList(
                new LambdaQueryWrapper<ProfileDirectionEntity>()
                        .eq(ProfileDirectionEntity::getProfileId, profile.getId())
                        .eq(ProfileDirectionEntity::getStatus, "ACTIVE")
                        .orderByAsc(ProfileDirectionEntity::getId));
        LearningPreferenceEntity preferenceEntity = preferenceMapper.selectOne(
                new LambdaQueryWrapper<LearningPreferenceEntity>()
                        .eq(LearningPreferenceEntity::getUserId, userId));
        List<AvailabilityRuleEntity> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<AvailabilityRuleEntity>()
                        .eq(AvailabilityRuleEntity::getUserId, userId)
                        .orderByAsc(AvailabilityRuleEntity::getWeekday, AvailabilityRuleEntity::getStartTime,
                                AvailabilityRuleEntity::getId));
        List<AvailabilityExceptionEntity> exceptions = exceptionMapper.selectList(
                new LambdaQueryWrapper<AvailabilityExceptionEntity>()
                        .eq(AvailabilityExceptionEntity::getUserId, userId)
                        .orderByAsc(AvailabilityExceptionEntity::getLocalDate, AvailabilityExceptionEntity::getId));
        List<SelfAssessmentEntity> assessments = selfAssessmentMapper.selectList(
                new LambdaQueryWrapper<SelfAssessmentEntity>()
                        .eq(SelfAssessmentEntity::getUserId, userId)
                        .orderByAsc(SelfAssessmentEntity::getAssessedAt, SelfAssessmentEntity::getId));
        PreferenceView preference = preferenceEntity == null ? null : preferenceView(userId);
        String signature = hashing.sha256(json(generationContext(profile, directions, preferenceEntity,
                rules, exceptions, assessments)));
        return new ProfileGenerationContext(profile, directions, preference, rules, exceptions, assessments, signature);
    }

    private Map<String, Object> generationContext(UserProfileEntity profile,
                                                  List<ProfileDirectionEntity> directions,
                                                  LearningPreferenceEntity preference,
                                                  List<AvailabilityRuleEntity> rules,
                                                  List<AvailabilityExceptionEntity> exceptions,
                                                  List<SelfAssessmentEntity> assessments) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("profile", Arrays.asList(profile.getId(), profile.getVersion(), profile.getCurrentVersionNo(),
                profile.getProfileStatus(),
                profile.getTimezone(), profile.getWeekStart(), profile.getPlanStartDate(), profile.getPlanEndDate(),
                profile.getPlanPeriodDays(), Objects.toString(profile.getBackgroundText(), "")));
        context.put("directions", directions.stream().map(item -> List.of(
                item.getId(), Objects.requireNonNullElse(item.getVersion(), 0),
                Objects.toString(item.getDirectionId(), ""), Objects.toString(item.getCustomDirection(), ""),
                item.getSourceType(), item.getCurrentStage(), Boolean.TRUE.equals(item.getIsPrimary()), item.getStatus()
        )).toList());
        context.put("preference", preference == null ? List.of() : List.of(
                preference.getId(), Objects.requireNonNullElse(preference.getVersion(), 0),
                preference.getContentModesJson(), preference.getGuidanceStyle(), preference.getTaskGranularity(),
                preference.getFocusMinutes(), preference.getCapacityRatio(), preference.getDifficultyMin(),
                preference.getDifficultyMax(), preference.getReminderJson()));
        context.put("availabilityRules", rules.stream().map(item -> List.of(
                item.getId(), Objects.requireNonNullElse(item.getVersion(), 0), item.getWeekday(),
                item.getStartTime(), item.getEndTime(), item.getAvailableMinutes(), item.getEnergyLevel()
        )).toList());
        context.put("availabilityExceptions", exceptions.stream().map(item -> List.of(
                item.getId(), Objects.requireNonNullElse(item.getVersion(), 0), item.getLocalDate(),
                item.getAvailableMinutes(), Objects.toString(item.getReason(), "")
        )).toList());
        context.put("selfAssessments", assessments.stream().map(item -> List.of(
                item.getId(), Objects.requireNonNullElse(item.getVersion(), 0), item.getKnowledgePointId(),
                item.getLevel(), item.getAssessedAt(), Objects.toString(item.getLastStudiedAt(), ""),
                Objects.toString(item.getNote(), "")
        )).toList());
        return context;
    }

    private void validateGenerationInputs(ProfileGenerationContext inputs) {
        UserProfileEntity profile = inputs.profile();
        if (profile.getTimezone() == null || profile.getPlanStartDate() == null || profile.getPlanEndDate() == null
                || profile.getPlanEndDate().isBefore(profile.getPlanStartDate())
                || ChronoUnit.DAYS.between(profile.getPlanStartDate(), profile.getPlanEndDate()) + 1 > 365) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像日期或时区尚未满足生成条件");
        }
        try { ZoneId.of(profile.getTimezone()); }
        catch (DateTimeException error) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像时区不合法");
        }
        if (inputs.directions().isEmpty() || inputs.directions().stream().noneMatch(ProfileDirectionEntity::getIsPrimary)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像学习方向尚未满足生成条件");
        }
        for (ProfileDirectionEntity direction : inputs.directions()) {
            if (direction.getDirectionId() == null && (direction.getCustomDirection() == null
                    || direction.getCustomDirection().isBlank())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自定义学习方向不能为空");
            }
            if (direction.getDirectionId() != null) directionName(direction.getDirectionId());
        }
        if (inputs.preference() == null || inputs.rules().isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "学习偏好或每周可用时间尚未满足生成条件");
        }
    }

    private Map<String, Object> profileSnapshot(ProfileGenerationContext inputs,
                                                DeterministicProfileAnalysisPolicy.Analysis analysis) {
        UserProfileEntity profile = inputs.profile();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("timezone", profile.getTimezone());
        snapshot.put("generatedAt", Instant.now());
        snapshot.put("planStartDate", profile.getPlanStartDate());
        snapshot.put("planEndDate", profile.getPlanEndDate());
        snapshot.put("backgroundText", profile.getBackgroundText());
        snapshot.put("directions", inputs.directions().stream().map(direction -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("directionId", direction.getDirectionId() == null ? null : String.valueOf(direction.getDirectionId()));
            item.put("name", direction.getDirectionId() == null ? direction.getCustomDirection()
                    : directionName(direction.getDirectionId()));
            item.put("sourceType", direction.getSourceType());
            item.put("knowledgeBaseDirection", direction.getDirectionId() != null);
            item.put("currentStage", direction.getCurrentStage());
            item.put("primary", Boolean.TRUE.equals(direction.getIsPrimary()));
            return item;
        }).toList());
        snapshot.put("preference", inputs.preference());
        snapshot.put("availabilityRules", inputs.rules().stream().map(rule -> Map.of(
                "weekday", rule.getWeekday(), "start", rule.getStartTime(), "end", rule.getEndTime(),
                "availableMinutes", rule.getAvailableMinutes(), "energyLevel", rule.getEnergyLevel())).toList());
        snapshot.put("availabilityExceptions", inputs.exceptions().stream().map(exception -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", exception.getLocalDate());
            item.put("availableMinutes", exception.getAvailableMinutes());
            item.put("reason", exception.getReason());
            return item;
        }).toList());
        int weeklyAvailableMinutes = inputs.rules().stream()
                .mapToInt(AvailabilityRuleEntity::getAvailableMinutes).sum();
        snapshot.put("weeklyAvailableMinutes", weeklyAvailableMinutes);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("profileVersion", profile.getVersion());
        source.put("selfAssessmentCount", inputs.assessments().size());
        source.put("latestSelfAssessmentAt", inputs.assessments().isEmpty() ? null
                : inputs.assessments().get(inputs.assessments().size() - 1).getAssessedAt());
        snapshot.put("source", source);
        snapshot.put("confidence", analysis.confidence());
        snapshot.put("recommendedDifficulty", analysis.recommendedDifficulty());
        snapshot.put("dailyRecommendedTasks", analysis.dailyRecommendedTasks());
        snapshot.put("riskNotices", analysis.riskNotices());
        return snapshot;
    }

    private record ProfileGenerationContext(UserProfileEntity profile,
                                            List<ProfileDirectionEntity> directions,
                                            PreferenceView preference,
                                            List<AvailabilityRuleEntity> rules,
                                            List<AvailabilityExceptionEntity> exceptions,
                                            List<SelfAssessmentEntity> assessments,
                                            String signature) { }

    private PreferenceView preferenceView() {
        return preferenceView(SecurityUtils.currentUserId());
    }

    private PreferenceView preferenceView(long userId) {
        LearningPreferenceEntity p = preferenceMapper.selectOne(new LambdaQueryWrapper<LearningPreferenceEntity>()
                .eq(LearningPreferenceEntity::getUserId, userId));
        if (p == null) return null;
        try {
            List<String> contentModes = normalizeStoredContentModes(readJson(p.getContentModesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            return new PreferenceView(contentModes,
                    p.getGuidanceStyle(), p.getTaskGranularity(), p.getFocusMinutes(), p.getCapacityRatio(),
                    p.getDifficultyMin(), p.getDifficultyMax(), readJson(p.getReminderJson(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class)), p.getVersion());
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private List<String> validateDocumentContentModes(List<String> contentModes) {
        if (contentModes == null || contentModes.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "至少选择一种文档型学习方式");
        }
        if (!DOCUMENT_CONTENT_MODES.containsAll(contentModes)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "当前只支持文字资料和练习两类可量化学习方式");
        }
        return List.copyOf(new LinkedHashSet<>(contentModes));
    }

    private List<String> normalizeStoredContentModes(List<String> contentModes) {
        if (contentModes == null) return DEFAULT_CONTENT_MODES;
        List<String> normalized = contentModes.stream()
                .filter(DOCUMENT_CONTENT_MODES::contains)
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_CONTENT_MODES : normalized;
    }

    private <T> T readJson(String value, JavaType type) throws JsonProcessingException {
        var tree = objectMapper.readTree(value);
        return tree.isTextual() ? objectMapper.readValue(tree.asText(), type) : objectMapper.convertValue(tree, type);
    }

    private DateRange validateProfile(ProfileInput input, UserProfileEntity existing) {
        try { ZoneId.of(input.timezone()); } catch (Exception e) { throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "无效的 IANA 时区"); }
        if (input.weekStart() < 1 || input.weekStart() > 7
                || input.backgroundText() != null && input.backgroundText().length() > 2000
                || input.directions() == null || input.directions().isEmpty()
                || input.directions().stream().filter(DirectionInput::primary).count() != 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "画像字段不完整或主方向数量不为 1");
        }
        for (DirectionInput d : input.directions()) {
            if (!Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(d.currentStage())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "当前阶段不在允许范围内");
            }
            if (d.directionId() == null && (d.customDirection() == null || d.customDirection().isBlank())) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自定义方向名称不能为空");
            }
            if (d.customDirection() != null && d.customDirection().length() > 120) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自定义方向名称不能超过 120 字");
            }
            if (d.directionId() != null) directionName(d.directionId());
        }
        LocalDate start = input.planStartDate();
        LocalDate end = input.planEndDate();
        if ((start == null) != (end == null)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "计划开始日期和结束日期必须同时填写");
        }
        if (start == null) {
            if (input.planPeriodDays() == null || input.planPeriodDays() < 1 || input.planPeriodDays() > 365) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "计划周期必须为 1～365 天");
            }
            if (existing != null && existing.getPlanStartDate() != null && existing.getPlanEndDate() != null
                    && Objects.equals(existing.getPlanPeriodDays(), input.planPeriodDays())) {
                start = existing.getPlanStartDate();
                end = existing.getPlanEndDate();
            } else {
                start = LocalDate.now(ZoneId.of(input.timezone()));
                end = start.plusDays(input.planPeriodDays() - 1L);
            }
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 365) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "计划起止日期必须组成 1～365 天的周期");
        }
        if (input.planPeriodDays() != null && input.planStartDate() != null && input.planPeriodDays() != days) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "计划周期天数与起止日期不一致");
        }
        return new DateRange(start, end, Math.toIntExact(days));
    }

    private record DateRange(LocalDate start, LocalDate end, int days) {}

    private String directionName(long id) {
        List<String> names = jdbc.query("SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL",
                (rs, row) -> rs.getString(1), id);
        if (names.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "学习方向不存在或已停用");
        return names.get(0);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void conflict() { throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资源版本冲突，请刷新后重试"); }
}
