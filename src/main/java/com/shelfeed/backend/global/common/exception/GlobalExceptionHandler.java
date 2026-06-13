package com.shelfeed.backend.global.common.exception;
// 예외를 ApiResponse형태로 바꿔서 에러응답 반환
import com.shelfeed.backend.global.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 비즈니스 예외 처리 (우리가 직접 던지는 예외)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage()));
    }

    // @Valid 검증 실패 처리 - errors 배열로 반환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidException(MethodArgumentNotValidException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> ApiResponse.FieldError.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .build())
                .toList();

        return ResponseEntity
                .status(400)
                .body(ApiResponse.error(400, "입력값을 확인해주세요.", fieldErrors));
    }

    // DB unique 제약 위반 (동시 요청 등)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return ResponseEntity
                .status(409)
                .body(ApiResponse.error(409, "이미 존재하는 데이터입니다."));
    }

    // 존재하지 않는 URL (404)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity
                .status(404)
                .body(ApiResponse.error(404, "요청한 리소스를 찾을 수 없습니다."));
    }

    // 허용되지 않는 HTTP Method (405)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity
                .status(405)
                .body(ApiResponse.error(405, "지원하지 않는 HTTP 메서드입니다."));
    }

    // 그 외 예상치 못한 예외 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("예상치 못한 서버 오류", e);
        return ResponseEntity
                .status(500)
                .body(ApiResponse.error(500, "서버 내부 오류가 발생했습니다."));
    }
}
