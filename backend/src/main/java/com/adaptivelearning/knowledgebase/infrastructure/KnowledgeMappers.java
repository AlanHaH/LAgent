package com.adaptivelearning.knowledgebase.infrastructure;
import com.adaptivelearning.knowledgebase.domain.*;import com.baomidou.mybatisplus.core.mapper.BaseMapper;import org.apache.ibatis.annotations.Mapper;
public final class KnowledgeMappers {private KnowledgeMappers(){}
 @Mapper public interface SpaceMapper extends BaseMapper<KnowledgeSpaceEntity>{}
 @Mapper public interface StoredObjectMapper extends BaseMapper<StoredObjectEntity>{}
 @Mapper public interface DocumentMapper extends BaseMapper<KnowledgeDocumentEntity>{}
 @Mapper public interface DocumentVersionMapper extends BaseMapper<DocumentVersionEntity>{}
 @Mapper public interface ChunkMapper extends BaseMapper<KnowledgeChunkEntity>{}
 @Mapper public interface DocumentJobMapper extends BaseMapper<DocumentJobEntity>{}
 @Mapper public interface DocumentDeletionTokenMapper extends BaseMapper<DocumentDeletionTokenEntity>{}
 @Mapper public interface QaSessionMapper extends BaseMapper<QaSessionEntity>{}
 @Mapper public interface QaMessageMapper extends BaseMapper<QaMessageEntity>{}
 @Mapper public interface QaCitationMapper extends BaseMapper<QaCitationEntity>{}
 @Mapper public interface QaFeedbackMapper extends BaseMapper<QaFeedbackEntity>{}
}
