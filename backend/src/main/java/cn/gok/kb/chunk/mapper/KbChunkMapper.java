package cn.gok.kb.chunk.mapper;

import cn.gok.kb.chunk.entity.KbChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 一次查出「切片 + 所属文档名」，避免检索时 N+1 查询。
     */
    @Select("""
            SELECT c.id           AS chunk_id,
                   c.doc_id       AS doc_id,
                   c.page_no      AS page_no,
                   c.seq_no       AS seq_no,
                   c.content      AS content,
                   c.char_count   AS char_count,
                   d.file_name    AS file_name,
                   d.file_type    AS file_type
            FROM kb_chunk c
                     JOIN kb_document d ON d.id = c.doc_id
            """)
    List<Map<String, Object>> selectChunkWithDoc();

    /**
     * 多行 INSERT，一条语句插一批切片。
     * 调用方负责分批（见 DocumentService#BATCH_SIZE），避免超过 max_allowed_packet。
     */
    @Insert("""
            <script>
            INSERT INTO kb_chunk (doc_id, page_no, seq_no, content, char_count, embedding, embed_model) VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.docId}, #{it.pageNo}, #{it.seqNo}, #{it.content}, #{it.charCount},
                 #{it.embedding,jdbcType=LONGVARCHAR}, #{it.embedModel,jdbcType=VARCHAR})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<KbChunk> list);

    /**
     * 只查向量检索需要的最小字段：切片 id + 向量。
     * 正文和文件名已经在 BM25 索引里了，向量索引按 chunkId 就能对上，不必重复加载。
     */
    @Select("""
            SELECT c.id        AS chunk_id,
                   c.embedding AS embedding
            FROM kb_chunk c
            WHERE c.embedding IS NOT NULL
            """)
    List<Map<String, Object>> selectEmbeddings();

    /** 统计有多少切片还没有向量，用于启动时判断要不要提示用户重算 */
    @Select("SELECT COUNT(*) FROM kb_chunk WHERE embedding IS NULL")
    long countMissingEmbedding();
}
