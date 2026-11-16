package com.example.wspoc.console

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.TimeoutException

/**
 * 모니터링 탭용 메트릭 수집(D28). 설정된 대상의 `/actuator/prometheus`를 콘솔 서버가 대신 긁는다:
 * 브라우저가 직접 부르면 대상마다 CORS가 필요하고, 화면이 임의 주소를 긁게 둘 수도 없기 때문이다.
 *
 * 콘솔은 값을 저장하지 않는다. 기록(차트)은 화면이 열려 있는 동안 브라우저가 갖고 있다.
 * 긁은 결과는 `refresh`의 절반 동안 재사용한다. 화면이 여러 개 열려 있어도 대상은 그보다 자주 긁히지 않는다.
 */
@Component
class MetricsScraper(
    props: ConsoleProperties,
) {
    private val monitor = props.monitor

    // 스크레이프 본문은 수백 KB가 될 수 있다(WebClient 기본 한도는 256KB).
    private val client =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES) }
            .build()

    private val cached: Mono<MetricsSnapshot> = Mono.defer(::scrapeAll).cache(monitor.refresh.dividedBy(2))

    suspend fun snapshot(): MetricsSnapshot = cached.awaitSingle()

    private fun scrapeAll(): Mono<MetricsSnapshot> =
        Flux
            .fromIterable(monitor.targets)
            .flatMapSequential(::scrape)
            .collectList()
            .map { MetricsSnapshot(System.currentTimeMillis(), monitor.refresh.toMillis(), it) }

    private fun scrape(target: ConsoleProperties.Target): Mono<TargetMetrics> {
        val base = target.url.toString().trimEnd('/')
        val started = System.nanoTime()

        fun result(
            error: String?,
            samples: List<MetricSample>,
        ) = TargetMetrics(target.name, base, error == null, error, (System.nanoTime() - started) / 1_000_000, samples)
        return client
            .get()
            .uri(URI.create(base + monitor.path))
            .accept(MediaType.TEXT_PLAIN)
            .retrieve()
            .bodyToMono<String>()
            .timeout(monitor.scrapeTimeout)
            .map { body -> result(null, PrometheusText.parse(body).mapNotNull(::kept)) }
            .onErrorResume { error -> Mono.just(result(describe(error), emptyList())) }
    }

    /** 화면이 쓰는 메트릭만 남기고, 대상 이름과 같은 뜻인 `application` label은 뺀다. */
    private fun kept(sample: MetricSample): MetricSample? =
        if (sample.name.startsWith("ws_") || sample.name in KEPT) sample.copy(labels = sample.labels - "application") else null

    private fun describe(error: Throwable): String =
        when (error) {
            is WebClientResponseException -> "HTTP ${error.statusCode.value()}"
            is TimeoutException -> "timeout"
            else -> generateSequence(error) { it.cause }.last().javaClass.simpleName
        }

    private companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** `ws_*` 말고 화면이 쓰는 내장 메트릭(JVM, 프로세스, Reactor Netty). */
        val KEPT =
            setOf(
                "process_cpu_usage",
                "system_cpu_count",
                "process_uptime_seconds",
                "process_files_open_files",
                "process_files_max_files",
                "jvm_memory_used_bytes",
                "jvm_memory_max_bytes",
                "jvm_threads_live_threads",
                "jvm_gc_pause_seconds_count",
                "jvm_gc_pause_seconds_sum",
                "reactor_netty_eventloop_pending_tasks",
                "reactor_netty_bytebuf_allocator_used_direct_memory",
            )
    }
}
