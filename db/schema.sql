-- AI 知识库助手 · 建表脚本
-- 执行：mysql -uroot -p < db/schema.sql

CREATE DATABASE IF NOT EXISTS kb_assistant
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE kb_assistant;

DROP TABLE IF EXISTS kb_chunk;
DROP TABLE IF EXISTS kb_document;

CREATE TABLE kb_document
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_name   VARCHAR(255) NOT NULL COMMENT '原始文件名',
    stored_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '磁盘上的实际文件名（UUID），删除时据此清理',
    file_type   VARCHAR(16)  NOT NULL COMMENT '格式：pdf / md / txt',
    file_size   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    page_count  INT          NOT NULL DEFAULT 0 COMMENT '页数（md/txt 记为 1）',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '切片数',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 已入库 / 0 处理中 / -1 失败',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档';

CREATE TABLE kb_chunk
(
    id         BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id     BIGINT  NOT NULL COMMENT '所属文档',
    page_no    INT     NOT NULL DEFAULT 1 COMMENT '起始页码，用于引用溯源',
    seq_no     INT     NOT NULL DEFAULT 0 COMMENT '文档内顺序号',
    content    TEXT    NOT NULL COMMENT '切片正文',
    char_count INT     NOT NULL DEFAULT 0 COMMENT '字符数',
    PRIMARY KEY (id),
    KEY idx_doc_id (doc_id),
    KEY idx_doc_seq (doc_id, seq_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文档切片';
