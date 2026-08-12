package com.teamer.teapot.ai.common.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包装（SPEC §4：common 模块）。
 * code=0 成功；非 0 为业务错误码，message 面向前端可直接展示。
 */
@Data
public class Result<T> implements Serializable {

    public static final int CODE_OK = 0;
    public static final int CODE_FAIL = -1;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(CODE_OK);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(String message) {
        return fail(CODE_FAIL, message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
