package cn.gok.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目自有配置，对应 application.yml 里的 kb.* 。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    /** 切片参数 */
    private Chunk chunk = new Chunk();

    /** 检索参数 */
    private Search search = new Search();

    /** 上传文件落地目录 */
    private String uploadDir = "./data/uploads";

    /** 模型问答开关：没有 API Key 时置 false，检索功能照常可用 */
    private Ai ai = new Ai();

    @Data
    public static class Ai {
        private boolean enabled = false;
        /** 单次回答最多注入的片段数 */
        private int maxContextChunks = 5;
    }

    @Data
    public static class Chunk {
        /** 单块目标字数 */
        private int size = 400;
        /** 相邻块重叠字数，保证跨块语义不断裂 */
        private int overlap = 60;
    }

    @Data
    public static class Search {
        /** 返回条数 */
        private int topK = 5;
        /** 归一化分数下限，低于此值视为不相关（用于"查不到就直说"） */
        private double minScore = 0.15;
    }
}
