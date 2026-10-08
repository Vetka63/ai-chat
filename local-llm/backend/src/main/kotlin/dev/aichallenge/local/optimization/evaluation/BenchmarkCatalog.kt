package dev.aichallenge.local.optimization.evaluation

import dev.aichallenge.local.optimization.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Версионированные синтетические обращения: настройка и контрольный набор разделены заранее. */
@Component
class BenchmarkCatalog(mapper: ObjectMapper) {
    val cases: List<BenchmarkCase> = mapper.readValue(javaClass.getResource("/optimization/cases.json")!!.readText(), Array<BenchmarkCase>::class.java).map { case ->
        case.copy(input = Regex("\\[NOISE_(\\d+)]").replace(case.input) { marker ->
            (1..marker.groupValues[1].toInt()).joinToString("\n") { "trace event=$it request=demo operation=heartbeat status=ok duration_ms=12" }
        })
    }
    fun find(id: String): BenchmarkCase = cases.singleOrNull { it.id == id } ?: throw OptimizationFailure("Неизвестный тестовый случай: $id")
}
