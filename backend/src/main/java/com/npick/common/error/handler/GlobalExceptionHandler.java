package com.npick.common.error.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.npick.common.error.BusinessException;
import com.npick.common.error.CommonErrorCode;
import com.npick.common.error.ErrorCode;
import com.npick.common.response.ApiResponse;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorTypeHttpStatusMapper statusMapper;

    public GlobalExceptionHandler(ErrorTypeHttpStatusMapper statusMapper) {
        this.statusMapper = statusMapper;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        if (exception.getSuppressed().length > 0) {
            log.warn(
                    "Business failure cleanup requires attention: code={}, cleanupFailures={}",
                    errorCode.code(),
                    exception.getSuppressed().length);
        }
        return ResponseEntity.status(statusMapper.map(errorCode.type()))
                .body(ApiResponse.failure(errorCode, requestPath(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.merge(
                        error.getField(),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (previous, current) -> previous + ", " + current));

        return failure(CommonErrorCode.VALIDATION_FAILED, request, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        if (exception.isForReturnValue()) {
            return failure(CommonErrorCode.INTERNAL_SERVER_ERROR, request, null);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (var result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors beanErrors) {
                beanErrors
                        .getFieldErrors()
                        .forEach(error -> addValidationError(errors, error.getField(), error.getDefaultMessage()));
                beanErrors
                        .getGlobalErrors()
                        .forEach(error ->
                                addValidationError(errors, beanErrors.getObjectName(), error.getDefaultMessage()));
            } else {
                var parameter = result.getMethodParameter();
                var header = parameter.getParameterAnnotation(RequestHeader.class);
                String name = header == null
                        ? parameter.getParameterName()
                        : (!header.name().isBlank() ? header.name() : header.value());
                if (name == null || name.isBlank()) name = "argument" + parameter.getParameterIndex();
                for (var error : result.getResolvableErrors()) {
                    addValidationError(errors, name, error.getDefaultMessage());
                }
            }
        }
        return failure(CommonErrorCode.VALIDATION_FAILED, request, errors);
    }

    private static void addValidationError(Map<String, String> errors, String field, String message) {
        errors.merge(
                field, message == null ? "Invalid value" : message, (previous, current) -> previous + ", " + current);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getConstraintViolations()
                .forEach(violation -> errors.put(violation.getPropertyPath().toString(), violation.getMessage()));

        return failure(CommonErrorCode.VALIDATION_FAILED, request, errors);
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        ServletRequestBindingException.class,
        TypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception, HttpServletRequest request) {
        log.debug("Invalid request: type={}", exception.getClass().getSimpleName());
        return failure(CommonErrorCode.BAD_REQUEST, request, null);
    }

    /** 매핑되지 않은 경로. 이 advice 가 {@code @Order(HIGHEST_PRECEDENCE)} 라 아래 포괄 핸들러가 먼저 잡아 500 을 내려보내므로, 404 를 따로 받아야 한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        log.debug("No handler for request", exception);
        return failure(CommonErrorCode.NOT_FOUND, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "Unexpected exception: type={}, location={}",
                exception.getClass().getSimpleName(),
                safeLocation(exception));
        return failure(CommonErrorCode.INTERNAL_SERVER_ERROR, request, null);
    }

    private <T> ResponseEntity<ApiResponse<T>> failure(ErrorCode errorCode, HttpServletRequest request, T data) {
        return ResponseEntity.status(statusMapper.map(errorCode.type()))
                .body(ApiResponse.failure(errorCode, requestPath(request), data));
    }

    private String requestPath(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static String safeLocation(Exception exception) {
        for (var frame : exception.getStackTrace()) {
            if (frame.getClassName().startsWith("com.npick.")) {
                // Preserve a diagnostic code location without exception messages or source/OS file paths.
                return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "external";
    }
}
