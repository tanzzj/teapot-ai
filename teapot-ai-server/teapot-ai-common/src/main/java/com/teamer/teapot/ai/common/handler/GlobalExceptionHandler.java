package com.teamer.teapot.ai.common.handler;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（SPEC §4：common 模块）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败"
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return Result.fail(message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        if (isClientAbort(e)) {
            // 客户端主动断开（切换会话/关页/网络抖动），非服务端故障，降噪处理
            log.debug("客户端提前断开连接：{}", e.toString());
            return null;
        }
        log.error("未预期异常", e);
        return Result.fail("系统异常，请稍后重试");
    }

    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("ClientAbortException")
                    || t instanceof java.io.EOFException) {
                return true;
            }
        }
        return false;
    }
}
