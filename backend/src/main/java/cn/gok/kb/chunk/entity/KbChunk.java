package cn.gok.kb.chunk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 */
    private Long docId;

    /** 起始页码，检索结果靠它做引用溯源 */
    private Integer pageNo;

    /** 文档内顺序号 */
    private Integer seqNo;

    private String content;

    private Integer charCount;
}
