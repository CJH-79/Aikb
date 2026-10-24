package cn.gok.kb.search;

import cn.gok.kb.chunk.mapper.KbChunkMapper;
import cn.gok.kb.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内存版向量索引。
 *
 * <p>为什么不引向量库：切片全量向量的规模是「切片数 × 维度 × 4 字节」。
 * 一万切片、512 维，也就 20MB，全量暴力算点积在毫秒级。
 * 引入 Milvus / Qdrant 要额外部署一套服务、还要处理数据同步和一致性问题，
 * 对这个体量是纯负收益。真正需要 ANN 索引的时候是百万级，那时候再换。
 *
 * <p>和 {@link Bm25Index} 一样整体替换（volatile），重建期间老索引继续对外服务。
 * 向量在入库时已做 L2 归一化，所以这里相似度直接用点积，省掉开方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndex {

    private final KbChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    private volatile Snapshot data = Snapshot.empty();

    /**
     * 从库里加载已有向量。不在这里调 embedding 接口：
     * 重建索引是高频路径（每次上传/删除都触发），把它变成网络请求会让上传变慢且不稳。
     * 向量是上传时算好落库的，这里只负责读。
     */
    public synchronized void rebuild() {
        if (!embeddingService.isEnabled()) {
            this.data = Snapshot.empty();
            return;
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = chunkMapper.selectEmbeddings();
        List<Long> ids = new ArrayList<>(rows.size());
        List<float[]> vectors = new ArrayList<>(rows.size());
        int dim = -1;
        int skipped = 0;

        for (Map<String, Object> row : rows) {
            float[] v = EmbeddingService.fromJson((String) row.get("embedding"));
            if (v == null || v.length == 0) {
                skipped++;
                continue;
            }
            // 维度不一致说明向量来自不同模型，混在一起算没有意义，直接丢掉
            if (dim < 0) {
                dim = v.length;
            } else if (v.length != dim) {
                skipped++;
                continue;
            }
            ids.add(((Number) row.get("chunk_id")).longValue());
            vectors.add(v);
        }

        this.data = new Snapshot(ids.toArray(new Long[0]), vectors.toArray(new float[0][]), dim);
        log.info("向量索引重建完成：{} 个向量，维度 {}，跳过 {} 条，耗时 {} ms",
                ids.size(), dim, skipped, System.currentTimeMillis() - start);
    }

    /**
     * @param query 已归一化的查询向量
     * @return 按相似度倒序的结果，最多 topK 条
     */
    public List<Hit> search(float[] query, int topK, double minSimilarity) {
        Snapshot snap = this.data;
        if (snap.size() == 0 || query == null || query.length != snap.dim) {
            return Collections.emptyList();
        }

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < snap.ids.length; i++) {
            float[] v = snap.vectors[i];
            double dot = 0;
            for (int d = 0; d < query.length; d++) {
                dot += (double) query[d] * v[d];
            }
            if (dot >= minSimilarity) {
                hits.add(new Hit(snap.ids[i], dot));
            }
        }
        return hits.stream()
                .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                .limit(Math.max(1, topK))
                .toList();
    }

    public int size() {
        return data.size();
    }

    public int dim() {
        return data.dim;
    }

    public boolean ready() {
        return data.size() > 0;
    }

    public record Hit(Long chunkId, double similarity) {
    }

    /** 向量矩阵快照：ids 与 vectors 按位对齐 */
    private record Snapshot(Long[] ids, float[][] vectors, int dim) {
        static Snapshot empty() {
            return new Snapshot(new Long[0], new float[0][], -1);
        }

        int size() {
            return ids.length;
        }
    }
}
