-- 增量迁移：给已存在的 kb_assistant 库补上向量检索需要的两列
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < db/migration_v2_embedding.sql
-- 说明：新库直接用 schema.sql 即可，本脚本只用于老库升级，可重复执行（先判断列是否存在）。

USE kb_assistant;

SET @c1 := (SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'kb_assistant'
              AND TABLE_NAME = 'kb_chunk'
              AND COLUMN_NAME = 'embedding');
SET @sql1 := IF(@c1 = 0,
                'ALTER TABLE kb_chunk ADD COLUMN embedding MEDIUMTEXT NULL COMMENT ''向量检索用的 embedding，JSON 数组'' AFTER char_count',
                'SELECT ''embedding 列已存在，跳过''');
PREPARE s1 FROM @sql1;
EXECUTE s1;
DEALLOCATE PREPARE s1;

SET @c2 := (SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = 'kb_assistant'
              AND TABLE_NAME = 'kb_chunk'
              AND COLUMN_NAME = 'embed_model');
SET @sql2 := IF(@c2 = 0,
                'ALTER TABLE kb_chunk ADD COLUMN embed_model VARCHAR(64) NULL COMMENT ''产出该向量的模型标识'' AFTER embedding',
                'SELECT ''embed_model 列已存在，跳过''');
PREPARE s2 FROM @sql2;
EXECUTE s2;
DEALLOCATE PREPARE s2;
