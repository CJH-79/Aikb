package cn.gok.kb.search.dto;

/**
 * 检索命中的一个片段，带完整溯源信息。
 */
public record SearchHitVO(Long chunkId, Long docId, String fileName, String fileType,
                          int pageNo, int seqNo, String content, double score) {
}
