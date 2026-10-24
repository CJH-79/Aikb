package cn.gok.kb.search.dto;

/**
 * 检索命中的一个片段，带完整溯源信息。
 *
 * @param score 展示用相关度（0~1）。单路 BM25 时就是归一化后的 BM25 分；
 *              走了混合检索时是 RRF 融合分再归一化，两种情况下都只能横向比较，
 *              不要跨模式对比绝对数值。
 * @param recall 这条结果是被哪一路召回的：bm25 / vector / bm25+vector。
 *               调检索效果时这个字段最有用——能立刻看出「向量到底有没有帮上忙」。
 */
public record SearchHitVO(Long chunkId, Long docId, String fileName, String fileType,
                          int pageNo, int seqNo, String content, double score, String recall) {

    public boolean fromVector() {
        return recall != null && recall.contains("vector");
    }

    public boolean fromBm25() {
        return recall != null && recall.contains("bm25");
    }
}
