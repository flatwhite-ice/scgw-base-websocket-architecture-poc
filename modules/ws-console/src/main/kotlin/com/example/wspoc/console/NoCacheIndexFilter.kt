package com.example.wspoc.console

import org.springframework.http.CacheControl
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 시작 화면(`/`)도 브라우저가 매번 서버에 확인하게 한다.
 * `spring.web.resources.cache.cachecontrol.no-cache`는 정적 파일 핸들러(`/app.js` 등)에만 적용되고,
 * `/`는 welcome page 라우터가 따로 서빙해서 `Cache-Control`이 붙지 않는다. 그대로 두면 브라우저가
 * 예전 index.html을 캐시에서 써서, 고친 화면이 보이지 않을 수 있다(D26).
 */
@Component
class NoCacheIndexFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        if ((request.method == HttpMethod.GET || request.method == HttpMethod.HEAD) && request.path.value() == "/") {
            exchange.response.headers.setCacheControl(CacheControl.noCache())
        }
        return chain.filter(exchange)
    }
}
