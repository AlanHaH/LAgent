package com.adaptivelearning.shared.ai;

import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelRunService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HashingService hashing;

    public long recordSuccess(long userId, String model, String question, List<Long> chunkIds,
                              AiModelClient.Completion completion) {
        long id = insert(userId, model, question, chunkIds, "KNOWLEDGE_QA", "KNOWLEDGE_QA_V1",
                "SUCCESS", completion.content(),
                completion.inputTokens(), completion.outputTokens(), completion.latencyMs(), null);
        recordRetrievalTool(id, question, chunkIds, "SUCCESS", completion.latencyMs(), null);
        return id;
    }

    public long recordFailure(long userId, String model, String question, List<Long> chunkIds,
                              long latencyMs, String errorCode) {
        long id = insert(userId, model, question, chunkIds, "KNOWLEDGE_QA", "KNOWLEDGE_QA_V1", "FAILED", null,
                null, null, latencyMs, errorCode);
        recordRetrievalTool(id, question, chunkIds, "FAILED", latencyMs, errorCode);
        return id;
    }

    public long recordProfileInterviewSuccess(long userId, String model, String message,
                                              AiModelClient.Completion completion) {
        return insert(userId, model, message, List.of(), "PROFILE_INTERVIEW", "PROFILE_INTERVIEW_V1",
                "SUCCESS", completion.content(), completion.inputTokens(), completion.outputTokens(),
                completion.latencyMs(), null);
    }

    public long recordProfileInterviewFailure(long userId, String model, String message,
                                              long latencyMs, String errorCode) {
        return insert(userId, model, message, List.of(), "PROFILE_INTERVIEW", "PROFILE_INTERVIEW_V1",
                "FAILED", null, null, null, latencyMs, errorCode);
    }

    private long insert(long userId, String model, String question, List<Long> chunkIds,
                        String purpose, String promptVersion, String status,
                        String output, Integer tokenIn, Integer tokenOut, long latencyMs, String errorCode) {
        long id = IdWorker.getId();
        Map<String, Object> inputRef = Map.of(
                "questionHash", hashing.sha256(question),
                "evidenceChunkIds", chunkIds,
                "model", model == null ? "" : model);
        jdbc.update("""
                INSERT INTO model_run(id,public_id,user_id,purpose,model_config_id,prompt_version,status,
                    input_ref_json,output_hash,token_in,token_out,latency_ms,error_code,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, UUID.randomUUID().toString(), userId, purpose, null,
                promptVersion, status, toJson(inputRef), output == null ? null : hashing.sha256(output),
                tokenIn, tokenOut, latencyMs, errorCode, Instant.now());
        return id;
    }

    private void recordRetrievalTool(long modelRunId, String question, List<Long> chunkIds,
                                     String status, long durationMs, String errorCode) {
        String callId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_tool_call
                (id,model_run_id,tool_call_id,tool_name,args_hash,args_summary_json,
                 result_status,duration_ms,error_code,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), modelRunId, callId, "KNOWLEDGE_RETRIEVAL",
                hashing.sha256(question), toJson(Map.of(
                        "queryHash", hashing.sha256(question),
                        "evidenceChunkIds", chunkIds,
                        "resultCount", chunkIds.size())),
                status, Math.max(0, durationMs), errorCode, Instant.now());
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
