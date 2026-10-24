package cn.gok.kb.chat.dto;

import java.util.List;

/**
 * 一次问答的完整结果。
 *
 * @param mode           llm=模型生成 / retrieval=未配模型或模型失败，只返回检索原文 / empty=没检索到
 * @param citations      引用来源，前端据此展示「出自哪个文件第几页」
 * @param elapsedMs      检索 + 生成总耗时
 * @param tip            给前端的提示语，比如未配置模型的说明
 * @param conversationId 本轮所属会话 id。首轮由后端生成，前端存下来在后续请求里带上，
 *                       多轮记忆就靠它串起来
 */
public record ChatVO(String answer, String mode, List<Citation> citations, long elapsedMs,
                     String tip, String conversationId) {

    /**
     * @param recall 这条引用是被哪一路召回：bm25 / vector / bm25+vector
     */
    public record Citation(int no, String fileName, String fileType, int pageNo,
                           String snippet, double score, String recall) {
    }
}
