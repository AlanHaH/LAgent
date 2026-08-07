package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.application.ProfileInterviewModels.*;
import com.adaptivelearning.profile.domain.*;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.*;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ProfileInterviewService {
    private static final Set<String> DOCUMENT_CONTENT_MODES = Set.of("TEXT", "PRACTICE");
    private static final List<String> DEFAULT_CONTENT_MODES = List.of("TEXT", "PRACTICE");

    private final ProfileInterviewSessionMapper sessionMapper;
    private final ProfileInterviewMessageMapper messageMapper;
    private final UserProfileMapper profileMapper;
    private final ProfileDirectionMapper directionMapper;
    private final LearningPreferenceMapper preferenceMapper;
    private final AvailabilityRuleMapper availabilityMapper;
    private final ProfileInterviewAssistant assistant;
    private final ProfileService profileService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final PlatformTransactionManager transactionManager;

    public record MessageView(String id, String role, String content, String source, Instant createdAt) {}
    public record SessionView(String id, String status, Draft draft, List<String> missingFields,
                              int completenessPercent, boolean readyToConfirm, String assistantMode,
                              int version, List<MessageView> messages) {}
    public record ConfirmationView(String sessionId, String status, ProfileService.ProfileView profile,
                                   ProfileGenerationJobEntity generationJob) {}
    public record ManualSaveView(ProfileService.ProfileView profile,
                                 List<AvailabilityPolicy.NormalizedSlot> availability,
                                 ProfileGenerationJobEntity generationJob,
                                 SessionView interview) {}

    @Transactional
    public SessionView start(boolean restart) {
        long userId = SecurityUtils.currentUserId();
        ProfileInterviewSessionEntity active = activeSession(userId);
        if (active != null && !restart) return view(active);
        if (active != null) {
            active.setStatus("ABANDONED");
            if (sessionMapper.updateById(active) != 1) conflict();
        }

        Draft draft = seedDraft(userId);
        List<String> missing = missingFields(draft);
        ProfileInterviewSessionEntity session = new ProfileInterviewSessionEntity();
        session.setPublicId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setStatus("ACTIVE");
        session.setDraftJson(toJson(draft));
        session.setMissingFieldsJson(toJson(missing));
        session.setCompletenessPercent(completeness(draft));
        session.setAssistantMode("GUIDED");
        sessionMapper.insert(session);

        String intro = ready(draft)
                ? "我已读取你现有的学习画像。你可以直接告诉我想修改的内容，或检查草稿后确认保存。"
                : "你好，我会通过几轮简短对话帮你整理学习画像。先说说你最想学习什么，以及目前大概是什么基础？";
        insertMessage(session, 1, "ASSISTANT", intro, "SYSTEM");
        audit.record("PROFILE_INTERVIEW_START", "PROFILE_INTERVIEW", session.getPublicId(), null,
                "completeness=" + session.getCompletenessPercent(), "SUCCESS");
        return view(session);
    }

    public SessionView active() {
        ProfileInterviewSessionEntity active = activeSession(SecurityUtils.currentUserId());
        return active == null ? null : view(active);
    }

    public SessionView get(String publicId) {
        return view(requireSession(publicId, false));
    }

    public SessionView addMessage(String publicId, String content, int version) {
        long userId = SecurityUtils.currentUserId();
        ProfileInterviewSessionEntity before = requireSession(publicId, true);
        requireVersion(before, version);
        List<ProfileInterviewMessageEntity> previous = messages(before.getId());
        List<Transcript> transcript = previous.stream().map(m -> new Transcript(m.getRole(), m.getContent())).toList();
        Draft current = readDraft(before.getDraftJson());
        AssistantTurn turn = assistant.respond(userId, publicId, current, transcript, content, directionOptions());
        List<String> missing = missingFields(turn.draft());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProfileInterviewSessionEntity session = requireSession(publicId, true);
            requireVersion(session, version);
            session.setDraftJson(toJson(turn.draft()));
            session.setMissingFieldsJson(toJson(missing));
            session.setCompletenessPercent(completeness(turn.draft()));
            session.setAssistantMode(turn.mode());
            // 乐观锁先于消息写入；并发的第二个请求会在这里失败，不会留下半条会话记录。
            if (sessionMapper.updateById(session) != 1) conflict();
            int next = nextSequence(session.getId());
            insertMessage(session, next, "USER", content.trim(), "USER");
            insertMessage(session, next + 1, "ASSISTANT", turn.assistantMessage(), turn.mode());
            audit.record("PROFILE_INTERVIEW_TURN", "PROFILE_INTERVIEW", publicId, null,
                    "mode=" + turn.mode() + ",completeness=" + session.getCompletenessPercent(), "SUCCESS");
        });
        return get(publicId);
    }

    /**
     * Async/SSE variant. The authenticated user and request metadata are captured on the request thread,
     * so no thread-local security or servlet state is consulted while the model stream is running.
     */
    public SessionView addMessageStreaming(long userId, String requestId, String clientIp,
                                           String publicId, String content, int version,
                                           Consumer<String> assistantDelta,
                                           Consumer<String> assistantReplacement) {
        ProfileInterviewSessionEntity before = requireSession(publicId, true, userId);
        requireVersion(before, version);
        List<ProfileInterviewMessageEntity> previous = messages(before.getId(), userId);
        List<Transcript> transcript = previous.stream().map(m -> new Transcript(m.getRole(), m.getContent())).toList();
        Draft current = readDraft(before.getDraftJson());
        AssistantTurn turn = assistant.respondStreaming(userId, publicId, current, transcript, content,
                directionOptions(), new ProfileInterviewAssistant.StreamOutput() {
                    @Override public void delta(String text) { assistantDelta.accept(text); }
                    @Override public void replace(String text) { assistantReplacement.accept(text); }
                });
        List<String> missing = missingFields(turn.draft());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProfileInterviewSessionEntity session = requireSession(publicId, true, userId);
            requireVersion(session, version);
            session.setDraftJson(toJson(turn.draft()));
            session.setMissingFieldsJson(toJson(missing));
            session.setCompletenessPercent(completeness(turn.draft()));
            session.setAssistantMode(turn.mode());
            if (sessionMapper.updateById(session) != 1) conflict();
            int next = nextSequence(session.getId());
            insertMessage(session, next, "USER", content.trim(), "USER");
            insertMessage(session, next + 1, "ASSISTANT", turn.assistantMessage(), turn.mode());
            audit.recordAs(userId, requestId, clientIp, "PROFILE_INTERVIEW_TURN", "PROFILE_INTERVIEW",
                    publicId, null, "mode=" + turn.mode() + ",completeness="
                            + session.getCompletenessPercent(), "SUCCESS");
        });
        return view(requireSession(publicId, false, userId), userId);
    }

    @Transactional
    public ConfirmationView confirm(String publicId, int version) {
        ProfileInterviewSessionEntity session = requireSession(publicId, true);
        requireVersion(session, version);
        Draft draft = readDraft(session.getDraftJson());
        List<String> missing = missingFields(draft);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "画像草稿信息还不完整", Map.of("missingFields", missing));
        }
        int days = Math.toIntExact(ChronoUnit.DAYS.between(draft.planStartDate(), draft.planEndDate()) + 1);
        ProfileService.ProfileView existing = profileService.get();
        ProfileService.DirectionInput direction = new ProfileService.DirectionInput(draft.directionId(),
                draft.directionId() == null ? draft.customDirection() : null, draft.currentStage(), true);
        ProfileService.ProfileView saved = profileService.save(new ProfileService.ProfileInput(
                draft.timezone(), draft.weekStart(), days, draft.planStartDate(), draft.planEndDate(),
                draft.backgroundText(), List.of(direction), existing == null ? null : existing.version()));

        PreferenceDraft pref = draft.preference();
        Integer preferenceVersion = saved.preference() == null ? null : saved.preference().version();
        profileService.savePreference(new ProfileService.PreferenceInput(pref.contentModes(), pref.guidanceStyle(),
                pref.taskGranularity(), pref.focusMinutes(), pref.capacityRatio(), pref.difficultyMin(),
                pref.difficultyMax(), pref.reminders(), preferenceVersion));
        profileService.saveAvailability(draft.availability().stream().map(s -> new AvailabilityPolicy.Slot(
                s.weekday(), s.start(), s.end(), s.energyLevel())).toList());
        ProfileGenerationJobEntity generationJob = profileService.generate("INTERVIEW_CONFIRM");

        session.setStatus("CONFIRMED");
        session.setConfirmedAt(Instant.now());
        session.setCompletenessPercent(100);
        session.setMissingFieldsJson("[]");
        if (sessionMapper.updateById(session) != 1) conflict();
        audit.record("PROFILE_INTERVIEW_CONFIRM", "PROFILE_INTERVIEW", publicId, null,
                "profileVersion=" + saved.version() + ",periodDays=" + days, "SUCCESS");
        return new ConfirmationView(publicId, "CONFIRMED", profileService.get(), generationJob);
    }

    @Transactional
    public ManualSaveView saveManual(String sessionId, int sessionVersion,
                                     ProfileService.ProfileInput profile,
                                     ProfileService.PreferenceInput preference,
                                     List<AvailabilityPolicy.Slot> availability) {
        ProfileInterviewSessionEntity current = requireSession(sessionId, false);
        if ("ACTIVE".equals(current.getStatus())) requireVersion(current, sessionVersion);

        profileService.save(profile);
        profileService.savePreference(preference);
        List<AvailabilityPolicy.NormalizedSlot> normalized = profileService.saveAvailability(availability);
        ProfileGenerationJobEntity job = profileService.generate("MANUAL_SAVE");

        long userId = SecurityUtils.currentUserId();
        Draft synchronizedDraft = seedDraft(userId);
        ProfileInterviewSessionEntity synchronizedSession;
        if ("ACTIVE".equals(current.getStatus())) {
            synchronizedSession = current;
        } else {
            synchronizedSession = new ProfileInterviewSessionEntity();
            synchronizedSession.setPublicId(UUID.randomUUID().toString());
            synchronizedSession.setUserId(userId);
        }
        synchronizedSession.setStatus("CONFIRMED");
        synchronizedSession.setConfirmedAt(Instant.now());
        synchronizedSession.setDraftJson(toJson(synchronizedDraft));
        synchronizedSession.setMissingFieldsJson("[]");
        synchronizedSession.setCompletenessPercent(100);
        synchronizedSession.setAssistantMode("MANUAL");
        if (synchronizedSession.getId() == null) {
            sessionMapper.insert(synchronizedSession);
        } else if (sessionMapper.updateById(synchronizedSession) != 1) {
            conflict();
        }
        int next = nextSequence(synchronizedSession.getId());
        insertMessage(synchronizedSession, next, "ASSISTANT",
                "高级手动编辑已经校验并保存，右侧草稿已同步为本次正式画像。若还要调整，可以重新开始访谈。",
                "MANUAL");
        ProfileService.ProfileView finalProfile = profileService.get();
        audit.record("PROFILE_MANUAL_SAVE", "PROFILE_INTERVIEW", synchronizedSession.getPublicId(), null,
                "profileVersion=" + finalProfile.currentVersionNo()
                        + ",periodDays=" + finalProfile.planPeriodDays(), "SUCCESS");
        return new ManualSaveView(finalProfile, normalized, job, view(synchronizedSession));
    }

    private Draft seedDraft(long userId) {
        UserProfileEntity profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, userId));
        LearningPreferenceEntity preference = preferenceMapper.selectOne(new LambdaQueryWrapper<LearningPreferenceEntity>()
                .eq(LearningPreferenceEntity::getUserId, userId));
        List<AvailabilityRuleEntity> availability = availabilityMapper.selectList(new LambdaQueryWrapper<AvailabilityRuleEntity>()
                .eq(AvailabilityRuleEntity::getUserId, userId).orderByAsc(AvailabilityRuleEntity::getWeekday,
                        AvailabilityRuleEntity::getStartTime));
        PreferenceDraft pref = preference == null ? defaultPreference() : readPreference(preference);
        if (profile == null) {
            String timezone = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class, userId);
            return new Draft(timezone == null ? "Asia/Shanghai" : timezone, 1, null, null,
                    null, null, null, null, null, pref, List.of(), Map.of());
        }
        ProfileDirectionEntity direction = directionMapper.selectList(new LambdaQueryWrapper<ProfileDirectionEntity>()
                .eq(ProfileDirectionEntity::getProfileId, profile.getId()).orderByDesc(ProfileDirectionEntity::getIsPrimary))
                .stream().findFirst().orElse(null);
        String name = direction == null ? null : direction.getDirectionId() == null
                ? direction.getCustomDirection() : directionName(direction.getDirectionId());
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("现有信息", "来自上次已保存的画像，可在本次确认前修改");
        return new Draft(profile.getTimezone(), profile.getWeekStart(), profile.getPlanStartDate(),
                profile.getPlanEndDate(), direction == null ? null : direction.getDirectionId(), name,
                direction == null ? null : direction.getCustomDirection(),
                direction == null ? null : direction.getCurrentStage(), profile.getBackgroundText(), pref,
                availability.stream().map(a -> new SlotDraft(a.getWeekday(), a.getStartTime(), a.getEndTime(),
                        a.getEnergyLevel())).toList(), Map.copyOf(evidence));
    }

    private PreferenceDraft defaultPreference() {
        return new PreferenceDraft(List.of("TEXT", "PRACTICE"), "SOCRATIC", "MEDIUM", 45,
                new BigDecimal("0.85"), 1, 4, Map.of("TASK_DUE", true, "TASK_OVERDUE", true));
    }

    @SuppressWarnings("unchecked")
    private PreferenceDraft readPreference(LearningPreferenceEntity p) {
        try {
            var modesType = json.getTypeFactory().constructCollectionType(List.class, String.class);
            var reminderType = json.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class);
            var modesTree = json.readTree(p.getContentModesJson());
            var reminderTree = json.readTree(p.getReminderJson());
            List<String> modes = modesTree.isTextual() ? json.readValue(modesTree.asText(), modesType)
                    : json.convertValue(modesTree, modesType);
            modes = normalizeContentModes(modes);
            Map<String, Boolean> reminders = reminderTree.isTextual() ? json.readValue(reminderTree.asText(), reminderType)
                    : json.convertValue(reminderTree, reminderType);
            return new PreferenceDraft(modes, p.getGuidanceStyle(), p.getTaskGranularity(), p.getFocusMinutes(),
                    p.getCapacityRatio(), p.getDifficultyMin(), p.getDifficultyMax(), reminders);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("已保存的学习偏好无法解析", e);
        }
    }

    private List<String> normalizeContentModes(List<String> modes) {
        if (modes == null) return DEFAULT_CONTENT_MODES;
        List<String> normalized = modes.stream()
                .filter(DOCUMENT_CONTENT_MODES::contains)
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_CONTENT_MODES : normalized;
    }

    private SessionView view(ProfileInterviewSessionEntity session) {
        return view(session, SecurityUtils.currentUserId());
    }

    private SessionView view(ProfileInterviewSessionEntity session, long userId) {
        Draft draft = readDraft(session.getDraftJson());
        List<String> missing = readMissing(session.getMissingFieldsJson());
        List<MessageView> messageViews = messages(session.getId(), userId).stream().map(m -> new MessageView(
                m.getPublicId(), m.getRole(), m.getContent(), m.getSource(), m.getCreatedAt())).toList();
        return new SessionView(session.getPublicId(), session.getStatus(), draft, missing,
                session.getCompletenessPercent(), missing.isEmpty() && "ACTIVE".equals(session.getStatus()),
                session.getAssistantMode(), session.getVersion(), messageViews);
    }

    private List<String> missingFields(Draft d) {
        List<String> missing = new ArrayList<>();
        if (d.directionId() == null && (d.customDirection() == null || d.customDirection().isBlank())) missing.add("学习方向");
        if (d.currentStage() == null) missing.add("当前阶段");
        if (d.planStartDate() == null || d.planEndDate() == null) missing.add("计划起止日期");
        else {
            long days = ChronoUnit.DAYS.between(d.planStartDate(), d.planEndDate()) + 1;
            if (days < 1 || days > 365) missing.add("有效的 1～365 天计划周期");
        }
        if (d.availability() == null || d.availability().isEmpty()) missing.add("每周可用时间");
        return List.copyOf(missing);
    }

    private int completeness(Draft d) {
        int score = 20; // 时区、周起始日和安全默认偏好
        if (d.directionId() != null || d.customDirection() != null && !d.customDirection().isBlank()) score += 20;
        if (d.currentStage() != null) score += 15;
        if (d.planStartDate() != null && d.planEndDate() != null) {
            long days = ChronoUnit.DAYS.between(d.planStartDate(), d.planEndDate()) + 1;
            if (days >= 1 && days <= 365) score += 25;
        }
        if (d.availability() != null && !d.availability().isEmpty()) score += 20;
        return Math.min(100, score);
    }

    private boolean ready(Draft d) { return missingFields(d).isEmpty(); }

    private ProfileInterviewSessionEntity activeSession(long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ProfileInterviewSessionEntity>()
                .eq(ProfileInterviewSessionEntity::getUserId, userId)
                .eq(ProfileInterviewSessionEntity::getStatus, "ACTIVE")
                .orderByDesc(ProfileInterviewSessionEntity::getUpdatedAt)).stream().findFirst().orElse(null);
    }

    private ProfileInterviewSessionEntity requireSession(String publicId, boolean activeRequired) {
        return requireSession(publicId, activeRequired, SecurityUtils.currentUserId());
    }

    private ProfileInterviewSessionEntity requireSession(String publicId, boolean activeRequired, long userId) {
        ProfileInterviewSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<ProfileInterviewSessionEntity>()
                .eq(ProfileInterviewSessionEntity::getPublicId, publicId)
                .eq(ProfileInterviewSessionEntity::getUserId, userId));
        if (session == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "画像访谈不存在");
        if (activeRequired && !"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "该访谈已经结束，请开始新的访谈");
        }
        return session;
    }

    private List<ProfileInterviewMessageEntity> messages(long sessionId) {
        return messages(sessionId, SecurityUtils.currentUserId());
    }

    private List<ProfileInterviewMessageEntity> messages(long sessionId, long userId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ProfileInterviewMessageEntity>()
                .eq(ProfileInterviewMessageEntity::getSessionId, sessionId)
                .eq(ProfileInterviewMessageEntity::getUserId, userId)
                .orderByAsc(ProfileInterviewMessageEntity::getSequenceNo));
    }

    private void insertMessage(ProfileInterviewSessionEntity session, int sequence, String role,
                               String content, String source) {
        ProfileInterviewMessageEntity message = new ProfileInterviewMessageEntity();
        message.setPublicId(UUID.randomUUID().toString());
        message.setSessionId(session.getId());
        message.setUserId(session.getUserId());
        message.setSequenceNo(sequence);
        message.setRole(role);
        message.setContent(content.substring(0, Math.min(4000, content.length())));
        message.setSource(source);
        messageMapper.insert(message);
    }

    private int nextSequence(long sessionId) {
        Integer maximum = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM profile_interview_message WHERE session_id=? AND deleted_at IS NULL",
                Integer.class, sessionId);
        return maximum == null ? 1 : maximum + 1;
    }

    private List<DirectionOption> directionOptions() {
        return jdbc.query("SELECT id,code,name FROM learning_direction WHERE status='ACTIVE' AND deleted_at IS NULL ORDER BY sort_no,id",
                (rs, row) -> new DirectionOption(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
    }

    private String directionName(long id) {
        List<String> names = jdbc.query("SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE'",
                (rs, row) -> rs.getString(1), id);
        return names.isEmpty() ? null : names.get(0);
    }

    private Draft readDraft(String value) {
        try {
            var tree = json.readTree(value);
            return tree.isTextual() ? json.readValue(tree.asText(), Draft.class) : json.treeToValue(tree, Draft.class);
        }
        catch (JsonProcessingException e) { throw new IllegalStateException("画像访谈草稿无法解析", e); }
    }

    private List<String> readMissing(String value) {
        try {
            var type = json.getTypeFactory().constructCollectionType(List.class, String.class);
            var tree = json.readTree(value);
            return tree.isTextual() ? json.readValue(tree.asText(), type) : json.convertValue(tree, type);
        }
        catch (JsonProcessingException e) { throw new IllegalStateException("画像访谈缺失项无法解析", e); }
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void requireVersion(ProfileInterviewSessionEntity session, int version) {
        if (!Objects.equals(session.getVersion(), version)) conflict();
    }

    private void conflict() { throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "访谈草稿已更新，请刷新后重试"); }
}
