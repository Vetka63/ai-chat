from pathlib import Path


def test_http_layer_does_not_depend_on_deepseek_or_agent_details():
    text = (Path("src/application/main.py")).read_text(encoding="utf-8")
    assert "DeepSeekClient" not in text
    assert "DialogueAgent" not in text
    assert "system_prompt" not in text


def test_agent_has_no_fastapi_or_httpx_dependency():
    text = Path("src/agents/dialogue/agent.py").read_text(encoding="utf-8")
    assert "fastapi" not in text
    assert "httpx" not in text

