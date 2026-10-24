package cn.gok.kb.common;

import java.util.List;

public record PageResult<T>(long total, int page, int size, List<T> records) {

    public static <T> PageResult<T> of(long total, int page, int size, List<T> records) {
        return new PageResult<>(total, page, size, records);
    }
}
