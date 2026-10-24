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
        /**
         * 是否启用向量检索。
         * 关掉时整套系统退化为「BM25 单路召回 + 模型生成」，功能照常可用。
         */
        private boolean embeddingEnabled = false;
        /** 单次请求批量 embedding 的切片数，太大容易被服务端限流 */
        private int embeddingBatchSize = 16;
        /** 多轮对话保留的消息条数（user + assistant 各算一条） */
        private int memoryMaxMessages = 10;
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
        /**
         * 混合检索时每路召回的候选数倍数。
         * 两路各取 topK * recallFactor 条再融合，比各自只取 topK 效果好得多：
         * BM25 单独看不靠前、但语义确实相关的切片，才有机会被向量那一路带进最终结果。
         */
        private int recallFactor = 4;
        /** 向量余弦相似度下限。bge 系列中文模型里 0.5 以上基本是强相关，0.35 是弱相关 */
        private double vectorMinSimilarity = 0.35;
        /** RRF 融合的平滑常数，业界通用取值 60 */
        private int rrfK = 60;
        /**
         * 两路召回在 RRF 里的权重。
         *
         * <p>RRF 默认是等权的，等于假设两路召回质量相当。实际上不一定：
         * 关键词检索对专有名词、编号非常准，向量在模型较弱或语料特殊时会引入噪声。
         * 一旦发现「无关切片被向量那一路顶到第一」，就把 vectorWeight 调低（比如 0.3），
         * 让向量只起补充作用，而不是主导排序。
         */
        private double bm25Weight = 1.0;
        private double vectorWeight = 1.0;
    }
}
