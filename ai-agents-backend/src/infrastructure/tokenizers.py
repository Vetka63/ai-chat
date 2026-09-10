"""Локальные токенизаторы. API usage остаётся источником фактического расхода."""
import math


class ByteEstimate:
    """Приблизительная оценка, когда официальный tokenizer недоступен."""
    method = "Приблизительно: UTF-8 / 3 + служебные токены; не tokenizer модели"

    def count_text(self, text: str) -> int:
        return math.ceil(len(text.encode("utf-8")) / 3)

    def count_messages(self, messages: list) -> int:
        return 3 + sum(4 + self.count_text(m.content) for m in messages)


class MistralEstimate:
    """Использует официальный tokenizer конкретной версии Ministral из локального файла."""
    method = "Ministral 3 3B: официальный tokenizer; локальная оценка"

    def __init__(self, path: str):
        from mistral_common.tokens.tokenizers.mistral import MistralTokenizer
        self.tokenizer = MistralTokenizer.from_file(path)

    def count_text(self, text: str) -> int:
        return len(self.tokenizer.instruct_tokenizer.tokenizer.encode(text, bos=False, eos=False))

    def count_messages(self, messages: list) -> int:
        from mistral_common.protocol.instruct.request import ChatCompletionRequest
        request = ChatCompletionRequest(messages=[{"role": m.role, "content": m.content} for m in messages])
        return len(self.tokenizer.encode_chat_completion(request).tokens)
