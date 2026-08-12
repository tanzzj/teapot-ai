package com.teamer.teapot.ai.common.exception;

import lombok.Getter;

/**
 * 业务异常（SPEC §4：common 模块）。message 面向前端可直接展示。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(-1, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
