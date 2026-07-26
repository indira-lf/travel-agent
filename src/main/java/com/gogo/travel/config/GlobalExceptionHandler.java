package com.gogo.travel.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局统一异常处理器。
 *
 * <p>拦截所有 Controller 层抛出的异常，返回统一格式的 JSON 响应，防止堆栈信息泄露给前端。</p>
 *
 * <p>响应格式：{@code {"code": <httpStatus>, "message": "<用户友好提示>"}}</p>
 *
 * <p>注意：SSE 流式接口（返回 {@code SseEmitter}）在连接建立后的异常不经过此处理器，
 * 而是通过 {@code SseEmitter.completeWithError()} 机制处理，由各自的 subscribe 错误回调负责。</p>
 *
 * @author Hollis
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ===================== 401 未登录 =====================

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> handleNotLogin(NotLoginException e) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
    }

    // ===================== 403 无权限 =====================

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Map<String, Object>> handleNotPermission(NotPermissionException e) {
        return buildResponse(HttpStatus.FORBIDDEN, "无权限访问该资源");
    }

    // ===================== 400 请求参数异常 =====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        logger.warn("[GlobalExceptionHandler] 请求体解析失败: {}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "请求体格式错误，请检查 JSON 格式");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, "缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, "参数类型错误: " + e.getName());
    }

    // ===================== 404 资源不存在 =====================

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return buildResponse(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    // ===================== 405 方法不支持 =====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法: " + e.getMethod());
    }

    // ===================== 500 兜底处理 =====================

    /**
     * 兜底异常处理：捕获所有未被上方特定 Handler 匹配的异常。
     *
     * <p>仅记录完整堆栈到日志，对前端只返回通用错误提示，避免敏感信息泄露。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        logger.error("[GlobalExceptionHandler] 未预期的服务端异常", e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试");
    }

    // ===================== 私有方法 =====================

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
