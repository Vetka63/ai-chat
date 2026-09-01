from app.domain.agents import AgentProfile
from app.domain.chat import HistoryMessage
from app.domain.errors import InputPolicyError


class InputPolicy:
    def build_messages(
        self,
        profile: AgentProfile,
        message: str,
        history: list[HistoryMessage],
    ) -> list[dict[str, str]]:
        policy = profile.input_policy
        normalized_message = message.strip()
        if not normalized_message:
            raise InputPolicyError("Message must not be blank")
        if len(normalized_message) > policy.max_message_length:
            raise InputPolicyError(
                f"Message must not exceed {policy.max_message_length} characters"
            )
        if len(history) > policy.max_history_messages:
            raise InputPolicyError(
                f"History must not exceed {policy.max_history_messages} messages"
            )

        messages: list[dict[str, str]] = [
            {"role": "system", "content": profile.system_prompt.strip()}
        ]
        for item in history:
            if item.role not in policy.allowed_roles:
                raise InputPolicyError(f"History role is not allowed: {item.role}")
            content = item.content.strip()
            if not content:
                raise InputPolicyError("History messages must not be blank")
            if len(content) > policy.max_message_length:
                raise InputPolicyError(
                    f"History message must not exceed {policy.max_message_length} characters"
                )
            messages.append({"role": item.role, "content": content})

        messages.append({"role": "user", "content": normalized_message})
        return messages
