package cn.gok.kb.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;

    /** 磁盘上的实际文件名（UUID），删除文档时据此清理 */
    private String storedName;

    private String fileType;

    private Long fileSize;

    private Integer pageCount;

    private Integer chunkCount;

    /** 1 已入库 / 0 处理中 / -1 失败 */
    private Integer status;

    private LocalDateTime createTime;
}
