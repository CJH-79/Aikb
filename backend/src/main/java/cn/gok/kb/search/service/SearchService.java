package cn.gok.kb.search.service;

import cn.gok.kb.config.KbProperties;
import cn.gok.kb.search.Bm25Index;
import cn.gok.kb.search.dto.SearchHitVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final Bm25Index index;
    private final KbProperties props;

    /**
     * 纯检索，不经过模型。用来对比「检索」和「生成」两步各自的效果，
     * 也是模型不可用时的降级通道。
     */
    public List<SearchHitVO> search(String query, Integer topK) {
        int k = topK == null || topK <= 0 ? props.getSearch().getTopK() : Math.min(topK, 20);
        return index.search(query, k, props.getSearch().getMinScore()).stream()
                .map(h -> new SearchHitVO(h.chunkId(), h.docId(), h.fileName(), h.fileType(),
                        h.pageNo(), h.seqNo(), h.content(), round(h.score())))
                .toList();
    }

    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
