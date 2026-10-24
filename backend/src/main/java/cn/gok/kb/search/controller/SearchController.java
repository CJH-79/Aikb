package cn.gok.kb.search.controller;

import cn.gok.kb.common.Result;
import cn.gok.kb.search.dto.SearchHitVO;
import cn.gok.kb.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 只检索不生成。调试切片和召回效果时用这个接口单独看。
     */
    @GetMapping
    public Result<List<SearchHitVO>> search(@RequestParam String q,
                                            @RequestParam(required = false) Integer topK) {
        return Result.ok(searchService.search(q, topK));
    }
}
