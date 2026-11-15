package com.example.wspoc.console

/** Prometheus 노출 형식의 한 줄: `name{label="value",...} 12.5`. */
data class MetricSample(
    val name: String,
    val labels: Map<String, String>,
    val value: Double,
)

/**
 * Prometheus text exposition 형식 파서. 모니터링 탭이 쓰는 만큼만 읽는다:
 * 주석(`# HELP`, `# TYPE`)과 형식이 어긋난 줄은 건너뛰고, 값 뒤의 timestamp는 버린다.
 * `NaN`과 `±Inf` 값은 JSON으로 내보낼 수 없어 버린다(버킷 경계 `le="+Inf"`는 label이므로 남는다).
 */
object PrometheusText {
    fun parse(text: String): List<MetricSample> = text.lineSequence().mapNotNull(::parseLine).toList()

    fun parseLine(line: String): MetricSample? {
        if (line.isBlank() || line.startsWith("#")) return null
        val brace = line.indexOf('{')
        val space = line.indexOf(' ')
        val name: String
        val labels: Map<String, String>
        val rest: String
        if (brace >= 0 && (space < 0 || brace < space)) {
            name = line.substring(0, brace)
            val parsed = parseLabels(line, brace + 1) ?: return null
            labels = parsed.first
            rest = line.substring(parsed.second)
        } else {
            if (space < 0) return null
            name = line.substring(0, space)
            labels = emptyMap()
            rest = line.substring(space)
        }
        val value = rest.trim().substringBefore(' ').toDoubleOrNull() ?: return null
        return if (name.isEmpty() || !value.isFinite()) null else MetricSample(name, labels, value)
    }

    /** [start]부터 닫는 `}`까지 읽는다. 결과는 label과 `}` 다음 위치다. 형식이 어긋나면 null. */
    private fun parseLabels(
        line: String,
        start: Int,
    ): Pair<Map<String, String>, Int>? {
        val labels = LinkedHashMap<String, String>()
        var i = start
        while (i < line.length) {
            while (i < line.length && (line[i] == ',' || line[i] == ' ')) i++
            if (i >= line.length) return null
            if (line[i] == '}') return labels to i + 1
            val eq = line.indexOf('=', i)
            if (eq < 0 || eq + 1 >= line.length || line[eq + 1] != '"') return null
            val key = line.substring(i, eq).trim()
            val value = StringBuilder()
            i = eq + 2
            while (i < line.length && line[i] != '"') {
                if (line[i] == '\\' && i + 1 < line.length) {
                    // label 값의 escape: \\, \", \n
                    value.append(if (line[i + 1] == 'n') '\n' else line[i + 1])
                    i += 2
                } else {
                    value.append(line[i++])
                }
            }
            if (i >= line.length) return null
            labels[key] = value.toString()
            i++
        }
        return null
    }
}
