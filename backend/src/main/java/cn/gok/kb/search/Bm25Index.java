package cn.gok.kb.search;

import cn.gok.kb.chunk.mapper.KbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存版 BM25 索引。
 *
 * <p>为什么不上向量库：切片量在万级以内时，全量加载进内存只占几十 MB，
 * 一次检索耗时在毫秒级。引入 Milvus / ES 要多一套部署和运维，
 * 对一个单机知识库来说是负收益。数据量涨到十万级以上再换。
 *
 * <p>线程安全：索引整体替换（volatile 引用），重建期间老的索引继续对外服务，读不阻塞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Bm25Index {

    /** BM25 标准参数：k1 控制词频饱和速度，b 控制文档长度惩罚 */
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** tf 饱和项的理论上限 (k1 + 1)，用于把分数归一化到 0~1 */
    private static final double MAX_TF_WEIGHT = K1 + 1;
    /** 命中词的 idf 至少要有理论上限的这个比例，才算「有区分度」的命中 */
    private static final double MIN_REL_IDF = 0.25;

    private final KbChunkMapper chunkMapper;
    private final Tokenizer tokenizer;

    private volatile IndexData data = IndexData.empty();

    /**
     * 全量重建。文档增删后由 {@link SearchIndexManager} 统一调用。
     */
    public synchronized void rebuild() {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = chunkMapper.selectChunkWithDoc();

        List<ChunkDoc> docs = new ArrayList<>(rows.size());
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;

        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            List<String> tokens = tokenizer.tokenize(content);
            Map<String, Integer> tf = new HashMap<>(tokens.size() * 2);
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            // df 只统计「出现过」，同一切片内重复出现算一次
            for (String t : tf.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
            totalLen += tokens.size();
            docs.add(new ChunkDoc(
                    toLong(row.get("chunk_id")),
                    toLong(row.get("doc_id")),
                    (String) row.get("file_name"),
                    (String) row.get("file_type"),
                    toInt(row.get("page_no")),
                    toInt(row.get("seq_no")),
                    content,
                    tf,
                    tokens.size()
            ));
        }

        double avgdl = docs.isEmpty() ? 1.0 : (double) totalLen / docs.size();
        this.data = new IndexData(docs, df, avgdl);
        log.info("BM25 索引重建完成：{} 个切片，平均长度 {}，耗时 {} ms",
                docs.size(), Math.round(avgdl), System.currentTimeMillis() - start);
    }

    /**
     * @param query    用户问题
     * @param topK     返回条数
     * @param minScore 归一化分数下限，低于该值视为不相关
     */
    public List<ScoredChunk> search(String query, int topK, double minScore) {
        IndexData snapshot = this.data;
        if (snapshot.docs.isEmpty() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        // 同一 term 只算一次，避免用户重复输入导致分数虚高
        List<String> terms = queryTokens.stream().distinct().toList();
        int total = snapshot.docs.size();

        // 归一化分母：假设每个 term 都满权重命中，分数上限是多少
        // 注意 df == 0 的 term 必须跳过：语料库里根本没有这个词，它永远不可能被命中，
        // 若计入分母反而会因为「越罕见 idf 越高」把分数稀释掉（中文 bigram 里这类跨词组合很多）
        double maxPossible = 0;
        Map<String, Double> idfCache = new HashMap<>();
        for (String term : terms) {
            int df = snapshot.df.getOrDefault(term, 0);
            if (df == 0) {
                continue;
            }
            double idf = idf(df, total);
            idfCache.put(term, idf);
            maxPossible += idf * MAX_TF_WEIGHT;
        }
        if (maxPossible <= 0) {
            return Collections.emptyList();
        }

        List<ScoredChunk> hits = new ArrayList<>();
        // 区分度下限：命中词里至少要有一个是「不那么常见」的。
        // 否则会出现「公司股票代码是多少」这种语料外的问题，仅因为命中了「公司」两个字就硬凑出答案。
        double idfCeiling = idf(1, total);
        double minIdf = idfCeiling * MIN_REL_IDF;

        for (ChunkDoc doc : snapshot.docs) {
            double raw = 0;
            int matched = 0;
            double bestIdf = 0;
            for (String term : terms) {
                Double idf = idfCache.get(term);
                if (idf == null) {
                    continue;
                }
                Integer tf = doc.tf.get(term);
                if (tf == null || tf == 0) {
                    continue;
                }
                matched++;
                bestIdf = Math.max(bestIdf, idf);
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length / snapshot.avgdl));
                raw += idf * norm;
            }
            if (matched == 0 || bestIdf < minIdf) {
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("chunk={} len={} matched={} bestIdf={} raw={} maxPossible={}",
                        doc.chunkId, doc.length, matched, bestIdf, raw, maxPossible);
            }
            if (matched == 0) {
                continue;
            }
            double score = raw / maxPossible;
            if (score >= minScore) {
                hits.add(new ScoredChunk(doc.chunkId, doc.docId, doc.fileName, doc.fileType,
                        doc.pageNo, doc.seqNo, doc.content, score, matched));
            }
        }

        return hits.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(Math.max(1, topK))
                .toList();
    }

    /** 词出现在越少文档中，区分度越高 */
    private double idf(int df, int total) {
        return Math.log(1 + (total - df + 0.5) / (df + 0.5));
    }

    /**
     * 按 chunkId 反查切片元信息（正文、文件名、页码），不参与打分。
     *
     * <p>混合检索时会用到：向量那一路单独召回的切片可能压根不在 BM25 结果里，
     * 缺元信息就没法展示引用来源。与其再查一次库，不如从这里取——
     * 索引里本来就存着全量切片的正文。
     */
    public List<ScoredChunk> findByIds(Collection<Long> chunkIds) {
        IndexData snapshot = this.data;
        if (chunkIds == null || chunkIds.isEmpty() || snapshot.docs.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> want = new HashSet<>(chunkIds);
        List<ScoredChunk> out = new ArrayList<>(want.size());
        for (ChunkDoc doc : snapshot.docs) {
            if (want.contains(doc.chunkId)) {
                out.add(new ScoredChunk(doc.chunkId, doc.docId, doc.fileName, doc.fileType,
                        doc.pageNo, doc.seqNo, doc.content, 0, 0));
            }
        }
        return out;
    }

    public int size() {
        return data.docs.size();
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static int toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /** 索引中的一个切片 */
    private record ChunkDoc(Long chunkId, Long docId, String fileName, String fileType,
                            int pageNo, int seqNo, String content,
                            Map<String, Integer> tf, int length) {
    }

    /** 一次性替换的索引快照 */
    private record IndexData(List<ChunkDoc> docs, Map<String, Integer> df, double avgdl) {
        static IndexData empty() {
            return new IndexData(List.of(), Map.of(), 1.0);
        }
    }

    /** 检索结果 */
    public record ScoredChunk(Long chunkId, Long docId, String fileName, String fileType,
                              int pageNo, int seqNo, String content,
                              double score, int matchedTerms) {
    }
}
