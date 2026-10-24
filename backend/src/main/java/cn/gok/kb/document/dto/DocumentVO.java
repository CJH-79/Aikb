package cn.gok.kb.document.dto;

import java.time.LocalDateTime;

public record DocumentVO(Long id, String fileName, String fileType, Long fileSize,
                         Integer pageCount, Integer chunkCount,
                         Integer status, String statusText,
                         LocalDateTime createTime) {

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "已入库";
            case 0 -> "处理中";
            case -1 -> "失败";
            default -> "未知";
        };
    }
}
