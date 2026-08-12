package com.teamer.teapot.ai.common.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页数据包装。
 */
@Data
public class PageData<T> implements Serializable {

    private long total;
    private List<T> list;

    public static <T> PageData<T> of(long total, List<T> list) {
        PageData<T> page = new PageData<>();
        page.setTotal(total);
        page.setList(list);
        return page;
    }
}
