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
            INSERT INTO kb_chunk (doc_id, page_no, seq_no, content, char_count) VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.docId}, #{it.pageNo}, #{it.seqNo}, #{it.content}, #{it.charCount})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<KbChunk> list);
}
