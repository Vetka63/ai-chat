package dev.aichallenge.rag.taskmemory.services

import dev.aichallenge.rag.taskmemory.enums.MemoryLayer
import dev.aichallenge.rag.taskmemory.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Не доверяет source ID модели; проверяет patch целиком до изменения памяти. */
@Component
class MemoryPatchValidator(private val mapper: ObjectMapper) {
    /** Новые/удалённые записи должны цитировать текущий user turn; partial patch не применяется. */
    fun validate(raw: String, finish: String, input: DialogueContext, requireInventory: Boolean = false): Triple<String, TaskMemory, List<MemoryChange>> {
        require(finish == "stop" && raw.length <= 30000) { "preparation_truncated" }
        val root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw)
        // Старые сохранённые trace и unit-кейсы patch остаются читаемыми. Production preparer
        // всегда требует inventory; совместимость не отключает проверку новых LLM-ответов.
        val hasInventory = root.has("inventory")
        require(!requireInventory || hasInventory) { "memory_inventory_missing" }
        require(if (hasInventory) shape(root, "query", "inventory", "updates", "removals") else shape(root, "query", "updates", "removals")) { "preparation_shape" }
        val query = text(root, "query", 2000)
        require(root.path("updates").isArray && root.path("removals").isArray && root.path("updates").size() + root.path("removals").size() <= 20) { "memory_patch_size" }
        val changes = mutableListOf<MemoryChange>()
        for ((name, fields) in listOf("updates" to arrayOf("layer", "key", "value", "quote"), "removals" to arrayOf("layer", "key", "quote"))) {
            for (node in root.path(name)) {
                require(shape(node, *fields)) { "memory_patch_shape" }
                val layer = MemoryLayer.valueOf(text(node, "layer", 20).uppercase())
                val key = text(node, "key", 80)
                require(layer != MemoryLayer.GOAL || key == "goal") { "memory_goal_key" }
                val quote = text(node, "quote", 500, trim = false)
                require(input.question.contains(quote)) { "memory_quote_not_exact" }
                // Общая цель не заменяется промежуточным шагом. Протокол смены цели прозрачен в UI.
                val explicitGoalChange = listOf("Новая цель:", "Измени цель:", "Моя новая цель:").any { input.question.trimStart().startsWith(it, ignoreCase = true) }
                if (layer == MemoryLayer.GOAL && input.memory.facts.any { it.layer == MemoryLayer.GOAL } && !explicitGoalChange) continue
                changes.add(MemoryChange(layer, key, if (name == "updates") text(node, "value", 400) else null, quote))
            }
        }
        require(changes.distinctBy { it.layer to it.key }.size == changes.size) { "duplicate_memory_key" }
        if (hasInventory) validateInventory(root.path("inventory"), changes, input)
        val facts = input.memory.facts.toMutableList()
        changes.forEach { change ->
            if (change.value == null) require(facts.any { it.layer == change.layer && it.key == change.key }) { "unknown_memory_removal" }
            facts.removeAll { it.layer == change.layer && it.key == change.key }
            change.value?.let { facts.add(MemoryFact(change.layer, change.key, it, input.turnId, change.quote)) }
        }
        require(MemoryLayer.entries.all { layer -> facts.count { it.layer == layer } <= if (layer == MemoryLayer.GOAL) 1 else 12 }) { "memory_capacity" }
        require(changes.filter { it.value != null }.none { change -> facts.count { it.layer == change.layer && canonical(it.value) == canonical(change.value!!) } > 1 }) { "duplicate_memory_value" }
        return Triple(query, TaskMemory(facts), changes)
    }
    /** Каждый обнаруженный факт должен иметь проверяемый результат, а каждое изменение — основание.
     * Это структурная проверка полноты inventory → patch, не доказательство семантики самой LLM. */
    private fun validateInventory(inventory: JsonNode, changes: List<MemoryChange>, input: DialogueContext) {
        require(inventory.isArray && inventory.size() <= 20) { "memory_inventory_size" }
        val covered = mutableSetOf<Pair<MemoryLayer, String>>()
        val seen = mutableSetOf<Pair<MemoryLayer, String>>()
        for (item in inventory) {
            require(shape(item, "action", "layer", "key", "value", "quote")) { "memory_inventory_shape" }
            val layer = MemoryLayer.valueOf(text(item, "layer", 20).uppercase())
            val key = text(item, "key", 80)
            val id = layer to key
            require(seen.add(id)) { "duplicate_memory_inventory_key" }
            val quote = text(item, "quote", 500, trim = false)
            require(input.question.contains(quote)) { "memory_inventory_quote_not_exact" }
            when (text(item, "action", 10)) {
                "UPSERT" -> {
                    val value = text(item, "value", 400)
                    require(changes.any { it.layer == layer && it.key == key && it.value == value && it.quote == quote }) { "uncovered_memory_fact" }
                    covered.add(id)
                }
                "REMOVE" -> {
                    require(item.path("value").isNull) { "memory_removal_value" }
                    require(changes.any { it.layer == layer && it.key == key && it.value == null && it.quote == quote }) { "uncovered_memory_removal" }
                    covered.add(id)
                }
                "RETAIN" -> {
                    val value = text(item, "value", 400)
                    require(input.memory.facts.any { it.layer == layer && it.key == key && it.value == value }) { "memory_retain_not_existing" }
                    require(changes.none { it.layer == layer && it.key == key }) { "memory_retain_conflicts_with_patch" }
                }
                else -> throw IllegalArgumentException("memory_inventory_action")
            }
        }
        require(covered == changes.map { it.layer to it.key }.toSet()) { "memory_patch_without_inventory" }
    }
    private fun canonical(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun shape(node: JsonNode?, vararg fields: String) = node != null && node.isObject && node.size() == fields.size && fields.all { node.has(it) }
    private fun text(node: JsonNode, name: String, max: Int, trim: Boolean = true): String {
        require(node.path(name).isString) { "preparation_field" }
        val value = node.path(name).asText().let { if (trim) it.trim() else it }
        require(value.isNotBlank() && value.length <= max) { "preparation_field" }
        return value
    }
}
