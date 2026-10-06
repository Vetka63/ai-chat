package dev.aichallenge.local

import dev.aichallenge.local.chat.*
import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.runs.RunRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.util.UUID

class ChatTest {
    @TempDir lateinit var folder: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private fun config() = LabProperties().also { it.databasePath = folder.resolve("lab.sqlite").toString() }
    private fun repo() = ChatRepository(config(), mapper)
    private fun request(content: String) = SendMessageRequest(UUID.randomUUID().toString(), content)
    private val answer = Generation("Принято", null, "stop", 20, 4, 30.0, 1.0, 90.0)
    private class RecordingRuntime(val response: (Int) -> Generation) : LocalLlmRuntime {
        val calls = mutableListOf<LlmChatRequest>()
        override fun info() = RuntimeInfo(true, "test", listOf("qwen3:8b"), emptyList())
        override fun generate(request: GenerateRequest) = throw RuntimeFailure("not used")
        override fun chat(request: LlmChatRequest): Generation { calls.add(request); return response(calls.size) }
    }
    private fun service(repository: ChatRepository, runtime: LocalLlmRuntime, gate: GenerationGate = GenerationGate()) =
        ChatService(repository, runtime, ChatContextBuilder(), gate, config())

    @Test fun `second turn carries system user assistant user and survives repository restart`() {
        val repo = repo(); val runtime = RecordingRuntime { answer }; val service = service(repo, runtime)
        val chat = service.create(CreateChatRequest("qwen3:8b"))
        service.send(chat.id, request(" Меня зовут Сергей "))
        val result = service.send(chat.id, request("Как меня зовут?"))
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), runtime.calls[1].messages.map { it.role })
        assertEquals("Меня зовут Сергей", runtime.calls[1].messages[1].content)
        assertEquals(3, result.messages.last().contextMessageCount)
        assertEquals(4, result.messages.size)
        assertEquals(result, repo().find(chat.id))
        assertEquals("Меня зовут Сергей", result.title)
        assertFalse(runtime.calls[1].messages.any { it.content.contains("thinking") })
    }
    @Test fun `different chats never share context`() {
        val runtime = RecordingRuntime { answer }; val service = service(repo(), runtime)
        val first = service.create(CreateChatRequest("qwen3:8b", "A"))
        val second = service.create(CreateChatRequest("qwen3:8b", "B"))
        service.send(first.id, request("Секрет первого чата"))
        service.send(second.id, request("Второй чат"))
        assertEquals(2, runtime.calls[1].messages.size)
        assertFalse(runtime.calls[1].messages.any { it.content.contains("Секрет первого") })
    }
    @Test fun `same requestId replays stored result without model call or duplicate`() {
        val runtime = RecordingRuntime { answer }; val service = service(repo(), runtime)
        val chat = service.create(CreateChatRequest("qwen3:8b")); val request = request("hello")
        val first = service.send(chat.id, request)
        assertEquals(first, service.send(chat.id, request))
        assertEquals(1, runtime.calls.size)
        assertThrows(ChatConflict::class.java) { service.send(chat.id, request.copy(content = "different")) }
        assertThrows(ChatConflict::class.java) { service.send(chat.id, request.copy(temperature = 1.0)) }
    }
    @Test fun `failure keeps user and explicit retry uses same user record`() {
        val runtime = RecordingRuntime { if (it == 1) throw RuntimeFailure("offline") else answer }
        val service = service(repo(), runtime); val chat = service.create(CreateChatRequest("qwen3:8b")); val request = request("hello")
        val failed = service.send(chat.id, request)
        assertEquals(RunStatus.FAILED, failed.messages.single().status)
        assertEquals("hello", failed.messages.single().content)
        assertEquals(failed, service.send(chat.id, request))
        assertEquals(1, runtime.calls.size)
        val retried = service.retry(chat.id, request.requestId)
        assertEquals(2, retried.messages.size)
        assertEquals(failed.messages.single().id, retried.messages.first().id)
        assertEquals(RunStatus.COMPLETED, retried.messages.first().status)
        assertEquals(2, runtime.calls.last().messages.size)
        assertThrows(ChatConflict::class.java) { service.retry(chat.id, request.requestId) }
    }
    @Test fun `failed earlier turn stays visible but excluded from next prompt and cannot retry out of order`() {
        val runtime = RecordingRuntime { if (it == 1) throw RuntimeFailure("offline") else answer }
        val service = service(repo(), runtime); val chat = service.create(CreateChatRequest("qwen3:8b")); val first = request("Failed turn")
        service.send(chat.id, first); val result = service.send(chat.id, request("New turn"))
        assertEquals(3, result.messages.size)
        assertEquals(listOf(ChatContextBuilder.SYSTEM_PROMPT, "New turn"), runtime.calls.last().messages.map { it.content })
        assertThrows(ChatConflict::class.java) { service.retry(chat.id, first.requestId) }
    }
    @Test fun `unfinished user recovered after process restart without silent generation`() {
        val repo = repo(); val runtime = RecordingRuntime { answer }; val service = service(repo, runtime)
        val chat = service.create(CreateChatRequest("qwen3:8b")); val pending = repo.begin(chat.id, request("pending"))
        val restarted = service(repo(), runtime)
        val recovered = restarted.get(chat.id).messages.single()
        assertEquals(RunStatus.FAILED, recovered.status)
        assertEquals(pending.id, recovered.id)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(2, restarted.retry(chat.id, pending.requestId).messages.size)
    }
    @Test fun `delete cascades selected chat only and leaves lab runs intact`() {
        val repo = repo(); val runtime = RecordingRuntime { answer }; val service = service(repo, runtime)
        val lab = RunRepository(config(), mapper)
        lab.save(Run("lab", "2026-10-06T00:00:00Z", GenerateRequest("qwen3:8b", "q"), RunStatus.COMPLETED, answer))
        val first = service.create(CreateChatRequest("qwen3:8b")); val second = service.create(CreateChatRequest("qwen3:8b"))
        val request = request("hello"); service.send(first.id, request); service.delete(first.id)
        assertNull(repo.find(first.id)); assertNull(repo.user(first.id, request.requestId))
        assertNotNull(repo.find(second.id)); assertNotNull(lab.find("lab"))
        assertThrows(ChatMissing::class.java) { service.get(first.id) }
    }
    @Test fun `shared gate blocks concurrent generation deletion and lab calls before persisting duplicate users`() {
        val repo = repo(); val gate = GenerationGate(); val runtime = RecordingRuntime { answer }; val service = service(repo, runtime, gate)
        val chat = service.create(CreateChatRequest("qwen3:8b"))
        gate.exclusive {
            assertThrows(LabBusy::class.java) { service.send(chat.id, request("q")) }
            assertThrows(LabBusy::class.java) { service.delete(chat.id) }
            assertThrows(LabBusy::class.java) { GenerationService(runtime, RunRepository(config(), mapper), gate).generate(GenerateRequest("qwen3:8b", "q")) }
        }
        assertTrue(service.get(chat.id).messages.isEmpty())
        assertTrue(runtime.calls.isEmpty())
        assertEquals(2, service.send(chat.id, request("works after release")).messages.size)
    }
    @Test fun `truncated assistant content retained with honest status and unknown model rejected`() {
        val service = service(repo(), RecordingRuntime { answer.copy(doneReason = "length") })
        assertThrows(InvalidModel::class.java) { service.create(CreateChatRequest("cloud-model")) }
        val chat = service.create(CreateChatRequest("qwen3:8b"))
        assertEquals(RunStatus.TRUNCATED, service.send(chat.id, request("q")).messages.last().status)
    }
}
