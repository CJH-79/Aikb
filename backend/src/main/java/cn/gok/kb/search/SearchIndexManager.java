package cn.gok.kb.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 索引重建的统一入口。
 *
 * <p>存在的意义是让业务代码只依赖一个「重建索引」的动作，
 * 而不是分别记得去调 BM25 和向量两处——漏掉一处就会出现
 * 「检索到了但排序不对」这种很难查的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexManager {

    private final Bm25Index bm25Index;
    private final VectorIndex vectorIndex;

    /**
     * 启动预热。故意吞掉异常：数据库没建表时不能让应用起不来，前端仍要能打开页面。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            rebuildAll();
        } catch (Exception e) {
            log.warn("索引预热失败（数据库未初始化？）：{}", e.getMessage());
        }
    }

    public void rebuildAll() {
        bm25Index.rebuild();
        vectorIndex.rebuild();
    }

    /** BM25 的切片数就是全库切片数，两者同源 */
    public int size() {
        return bm25Index.size();
    }
}
