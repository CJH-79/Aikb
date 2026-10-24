package cn.gok.kb.chat.service;

import cn.gok.kb.chat.dto.ChatVO;
import cn.gok.kb.search.dto.SearchHitVO;
import cn.gok.kb.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SearchService searchService;
    private final AiAnswerService aiAnswerService;

    public ChatVO ask(String question, Integer topK) {
        long start = System.currentTimeMillis();

        // 第一步：检索。检索不到就不调模型，从源头上避免模型凭空编造
        List<SearchHitVO> hits = searchService.search(question, topK);
        if (hits.isEmpty()) {
            return new ChatVO(
                    "知识库里没有检索到与该问题相关的内容。可以换个说法再问，或者先上传相关资料。",
                    "empty", List.of(), cost(start),
                    "检索无命中，未调用模型");
        }

        List<ChatVO.Citation> citations = toCitations(hits);

        // 第二步：生成。模型不可用或调用失败时，退回展示检索原文
        if (aiAnswerService.isEnabled()) {
            String answer = aiAnswerService.answer(question, hits);
            if (answer != null) {
                return new ChatVO(answer, "llm", citations, cost(start), null);
            }
        }

        return new ChatVO(buildRetrievalAnswer(hits), "retrieval", citations, cost(start),
                "当前为纯检索模式：未配置模型或模型调用失败，以下是资料原文摘录");
    }

    private List<ChatVO.Citation> toCitations(List<SearchHitVO> hits) {
        List<ChatVO.Citation> list = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHitVO h = hits.get(i);
            list.add(new ChatVO.Citation(i + 1, h.fileName(), h.fileType(), h.pageNo(),
                    brief(h.content()), h.score()));
        }
        return list;
    }

    /** 没有模型时的兜底文案：把最相关的几条原文按编号列出来 */
    private String buildRetrievalAnswer(List<SearchHitVO> hits) {
        StringBuilder sb = new StringBuilder("在知识库中找到以下相关内容：\n\n");
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            SearchHitVO h = hits.get(i);
            sb.append('[').append(i + 1).append("] ");
            sb.append(brief(h.content())).append('\n');
            sb.append("    ——《").append(h.fileName()).append("》第 ").append(h.pageNo()).append(" 页\n\n");
        }
        return sb.toString().trim();
    }

    private String brief(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private long cost(long start) {
        return System.currentTimeMillis() - start;
    }
}
