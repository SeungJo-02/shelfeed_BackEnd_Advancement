package com.shelfeed.backend.global.common.exception;
//비즈니스 오류 발생했을 때 커스텀 오류 내뱉게 해주는 파일
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());//super : 부모클래스(RuntimeException) 실핼
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
