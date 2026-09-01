from typing import Protocol

from app.domain.errors import InvalidLlmResponseError
from app.llm.deepseek import LlmResult


class OutputPolicy(Protocol):
    def apply(self, result: LlmResult) -> str: ...


class TextOutputPolicy:
    def apply(self, result: LlmResult) -> str:
        content = result.content.strip()
        if not content:
            raise InvalidLlmResponseError
        return content


class OutputPolicyRegistry:
    def __init__(self, policies: dict[str, OutputPolicy] | None = None):
        self._policies = policies or {"text": TextOutputPolicy()}

    def get(self, policy_type: str) -> OutputPolicy:
        try:
            return self._policies[policy_type]
        except KeyError as exc:
            raise RuntimeError(f"Unknown output policy: {policy_type}") from exc
