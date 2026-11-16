package com.example.wspoc.console

/** 대상 하나를 긁은 결과. 실패하면 [up]이 false이고 [error]에 이유가 있다. */
data class TargetMetrics(
    val name: String,
    val url: String,
    val up: Boolean,
    val error: String?,
    val scrapeMillis: Long,
    val samples: List<MetricSample>,
)

/** 한 시점의 모든 대상. [timestamp]는 긁은 시각(epoch millis)이고, 화면은 이 값의 차이로 초당 변화량을 구한다. */
data class MetricsSnapshot(
    val timestamp: Long,
    val refreshMillis: Long,
    val targets: List<TargetMetrics>,
) {
    /**
     * 응답 JSON을 직접 쓴다. Jackson 데이터 바인딩에 맡기면 jackson-module-kotlin이 처음 보는 컬렉션 클래스마다
     * (ArrayList, EmptyList, LinkedHashMap, …) kotlin-reflect로 메타데이터를 읽는데, 이 jar 읽기가 event loop에서
     * 일어난다(BlockHound가 잡았다). 표본이 수백 개라 바인딩을 거치지 않는 편이 가볍기도 하다.
     */
    fun toJson(): String =
        buildString(targets.sumOf { it.samples.size } * 96 + 256) {
            append("{\"timestamp\":")
                .append(timestamp)
                .append(",\"refreshMillis\":")
                .append(refreshMillis)
                .append(",\"targets\":[")
            targets.forEachIndexed { t, target ->
                if (t > 0) append(',')
                append("{\"name\":").quoted(target.name).append(",\"url\":").quoted(target.url)
                append(",\"up\":").append(target.up).append(",\"error\":")
                if (target.error == null) append("null") else quoted(target.error)
                append(",\"scrapeMillis\":").append(target.scrapeMillis).append(",\"samples\":[")
                target.samples.forEachIndexed { s, sample ->
                    if (s > 0) append(',')
                    append("{\"name\":").quoted(sample.name).append(",\"labels\":{")
                    var first = true
                    for ((key, value) in sample.labels) {
                        if (!first) append(',')
                        first = false
                        quoted(key).append(':').quoted(value)
                    }
                    // 값은 파서가 유한한 수만 남겼다(NaN, Inf는 JSON에 쓸 수 없다).
                    append("},\"value\":").append(sample.value).append('}')
                }
                append("]}")
            }
            append("]}")
        }

    private fun StringBuilder.quoted(text: String): StringBuilder {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        return append('"')
    }
}
