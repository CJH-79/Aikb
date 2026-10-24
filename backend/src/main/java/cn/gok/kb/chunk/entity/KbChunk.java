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

    /**
     * 向量检索用的 embedding，存 JSON 数组（如 [0.012,-0.34,...]）。
     *
     * <p>为什么用 MEDIUMTEXT 而不是单独的向量表或向量库：切片量在万级以内，
     * bge-small 这类 512 维模型一个向量约 6KB，一万切片也就 60MB，MySQL 装得下；
     * 而且向量和切片同生共死，放同一行反而省掉一次关联查询和一套一致性维护。
     * 数据量涨到十万级、或者要按向量做 ANN 索引，再换成真正的向量库。
     */
    private String embedding;

    /** 产出该向量的模型标识，换模型后据此识别哪些切片需要重算 */
    private String embedModel;
}
