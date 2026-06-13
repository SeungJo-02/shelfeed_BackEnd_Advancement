package com.shelfeed.backend.global.common.response;
//모든 API 응답을 status/code/message/data 구조로 만들어 놓은 파일
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL) // null 필드는 JSON에서 제외
public class ApiResponse<T> {

    private final String status;
    private final int code;
    // 도메인 에러코드(예: "M001"). 비즈니스 예외 응답에만 채워지고, NON_NULL이라 성공/일반 응답엔 미포함.
    // 프론트가 메시지 문구가 아닌 이 코드로 분기하도록 노출한다.
    private final String errorCode;
    private final String message;
    private final T data;
    private final List<FieldError> errors;

    // 성공 (쓰기) - message + data 포함
    public static <T> ApiResponse<T> success(int code, String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(code)
                .message(message)
                .data(data)
                .build();
    }

    // 성공 (조회) - data만 포함
    public static <T> ApiResponse<T> success(int code, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(code)
                .data(data)
                .build();
    }

    // 성공 (data 없음) - message만 포함
    public static ApiResponse<Void> success(int code, String message) {
        return ApiResponse.<Void>builder()
                .status("SUCCESS")
                .code(code)
                .message(message)
                .build();
    }

    // 에러 (필드 검증 실패) - errors 포함
    public static ApiResponse<Void> error(int code, String message, List<FieldError> errors) {
        return ApiResponse.<Void>builder()
                .status("ERROR")
                .code(code)
                .message(message)
                .errors(errors)
                .build();
    }

    // 에러 (일반) - message만 포함
    public static ApiResponse<Void> error(int code, String message) {
        return ApiResponse.<Void>builder()
                .status("ERROR")
                .code(code)
                .message(message)
                .build();
    }

    // 에러 (비즈니스 예외) - 도메인 errorCode("M001" 등) 포함하여 프론트가 코드로 분기 가능하게 함
    public static ApiResponse<Void> error(int code, String errorCode, String message) {
        return ApiResponse.<Void>builder()
                .status("ERROR")
                .code(code)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    @Getter
    @Builder
    public static class FieldError {
        private final String field;
        private final String message;
    }
}
