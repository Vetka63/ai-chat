class ChatError(Exception):
    status_code = 500
    public_message = "Internal server error"


class AgentNotFoundError(ChatError):
    status_code = 404

    def __init__(self, agent_id: str):
        self.public_message = f"Unknown chat profile: {agent_id}"
        super().__init__(self.public_message)


class InputPolicyError(ChatError):
    status_code = 400

    def __init__(self, message: str):
        self.public_message = message
        super().__init__(message)


class MissingApiKeyError(ChatError):
    status_code = 503
    public_message = "LLM_API_KEY is required when CHAT_MODE=llm"


class LlmUnavailableError(ChatError):
    status_code = 502
    public_message = "LLM provider is unavailable"


class InvalidLlmResponseError(ChatError):
    status_code = 502
    public_message = "LLM provider returned an invalid response"
