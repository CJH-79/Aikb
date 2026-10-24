package cn.gok.kb.search.service;

import cn.gok.kb.config.KbProperties;
import cn.gok.kb.embedding.EmbeddingService;
import cn.gok.kb.search.Bm25Index;
import cn.gok.kb.search.VectorIndex;
import cn.gok.kb.search.dto.SearchHitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索层：BM25 与向量双路召回，再用 RRF 融合。
 *
 * <p>为什么要两路：两路错的地方不一样。
 * BM25 对专有名词、条款编号、精确措辞很强（问「N+1 补贴」能精确命中），
 * 但问法和原文用词不一致时就没辙；向量相反，能处理同义改写，却对数字和编号不敏感。
 * 只留一路，就等于把另一路的盲区当作系统的盲区。
 *
 * <p>融合为什么用 RRF（Reciprocal Rank Fusion）而不是加权求和：
 * BM25 分是「归一化后的对数概率」，余弦是「夹角余弦」，量纲完全不同，
 * 加权求和得先做分数对齐，而各批数据的分布不同，对齐系数没法固定。
 * RRF 只用排名不用分数，天然免疫量纲问题，实现就一行累加，是工程上的标准解法。
 *
 * <p>降级：向量未启用（没配 embedding 模型）时，行为跟单路 BM25 完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final Bm25Index bm25Index;
    private final VectorIndex vectorIndex;
    private final EmbeddingService embeddingService;
    private final KbProperties props;

    /**
     * 纯检索，不经过模型。用来对比「检索」和「生成」两步各自的效果，
     * 也是模型不可用时的降级通道。
     */
    public List<SearchHitVO> search(String query, Integer topK) {
        int k = topK == null || topK <= 0 ? props.getSearch().getTopK() : Math.min(topK, 20);
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int recall = k * Math.max(1, props.getSearch().getRecallFactor());

        // 第一路：BM25。召回阶段故意放宽条数，给融合留出空间
        List<Bm25Index.ScoredChunk> bm25Hits =
                bm25Index.search(query, recall, props.getSearch().getMinScore());

        // 第二路：向量。没配模型、索引为空、或向量化失败时，这一路为空
        List<VectorIndex.Hit> vectorHits = List.of();
        if (embeddingService.isEnabled() && vectorIndex.ready()) {
            float[] qv = embeddingService.embed(query);
            if (qv != null) {
                vectorHits = vectorIndex.search(qv, recall, props.getSearch().getVectorMinSimilarity());
            }
        }

        if (vectorHits.isEmpty()) {
            return bm25Hits.stream()
                    .limit(k)
                    .map(h -> toVO(h, h.score(), "bm25"))
                    .toList();
        }

        return fuse(bm25Hits, vectorHits, k);
    }

    /**
     * RRF 融合：score(d) = Σ w_i / (rrfK + rank_i(d))，rank 从 1 开始。
     * 最终分数再按最高分归一化到 0~1，只为了前端好展示。
     */
    private List<SearchHitVO> fuse(List<Bm25Index.ScoredChunk> bm25Hits,
                                   List<VectorIndex.Hit> vectorHits, int k) {
        int rrfK = Math.max(1, props.getSearch().getRrfK());
        double bm25Weight = props.getSearch().getBm25Weight();
        double vectorWeight = props.getSearch().getVectorWeight();

        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, String> recallTag = new HashMap<>();
        Map<Long, Double> bm25Score = new HashMap<>();

        for (int i = 0; i < bm25Hits.size(); i++) {
            Long id = bm25Hits.get(i).chunkId();
            fused.merge(id, bm25Weight / (rrfK + i + 1), Double::sum);
            recallTag.put(id, "bm25");
            bm25Score.put(id, bm25Hits.get(i).score());
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            VectorIndex.Hit h = vectorHits.get(i);
            fused.merge(h.chunkId(), vectorWeight / (rrfK + i + 1), Double::sum);
            recallTag.merge(h.chunkId(), "vector", (a, b) -> "bm25+vector");
        }

        // 向量单独召回、BM25 没给的切片，元信息要到索引里反查
        List<Long> missing = fused.keySet().stream()
                .filter(id -> !bm25Score.containsKey(id))
                .toList();
        Map<Long, Bm25Index.ScoredChunk> meta = new HashMap<>();
        for (Bm25Index.ScoredChunk c : bm25Hits) {
            meta.put(c.chunkId(), c);
        }
        for (Bm25Index.ScoredChunk c : bm25Index.findByIds(missing)) {
            meta.put(c.chunkId(), c);
        }

        double max = fused.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

        List<SearchHitVO> out = new ArrayList<>(fused.size());
        for (Map.Entry<Long, Double> e : fused.entrySet()) {
            Bm25Index.ScoredChunk c = meta.get(e.getKey());
            if (c == null) {
                continue;
            }
            out.add(toVO(c, e.getValue() / max, recallTag.get(e.getKey())));
        }

        if (log.isDebugEnabled()) {
            log.debug("混合检索融合：bm25 召回 {}，vector 召回 {}，融合后 {}",
                    bm25Hits.size(), vectorHits.size(), out.size());
        }

        return out.stream()
                .sorted(Comparator.comparingDouble(SearchHitVO::score).reversed())
                .limit(k)
                .toList();
    }

    private SearchHitVO toVO(Bm25Index.ScoredChunk c, double score, String recall) {
        return new SearchHitVO(c.chunkId(), c.docId(), c.fileName(), c.fileType(),
                c.pageNo(), c.seqNo(), c.content(), round(score), recall);
    }

    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
