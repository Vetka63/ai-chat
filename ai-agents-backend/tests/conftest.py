from agent_core.models import Completion


class FakeLlm:
    def __init__(self, answer: str = "Тестовый ответ"):
        self.answer = answer
        self.calls = []

    async def complete(self, messages, *, model, temperature, max_tokens):
        self.calls.append(
            {
                "messages": messages,
                "model": model,
                "temperature": temperature,
                "max_tokens": max_tokens,
            }
        )
        return Completion(content=self.answer, model=model)

