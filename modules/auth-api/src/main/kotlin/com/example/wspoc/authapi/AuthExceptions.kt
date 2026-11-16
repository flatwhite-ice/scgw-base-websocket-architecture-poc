package com.example.wspoc.authapi

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

// 서비스가 던지는 예외. 상태 코드는 `@ResponseStatus`로 정해 서비스가 HTTP 응답 타입에 기대지 않게 한다.

/** 요청 값이 발급 규칙에 맞지 않는다. 응답은 400이다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidTokenRequestException(
    message: String,
) : RuntimeException(message)

/** 티켓 발급에 제시된 access token이 유효하지 않다. 응답은 401이다. */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
class UnauthenticatedException(
    message: String? = null,
) : RuntimeException(message)
