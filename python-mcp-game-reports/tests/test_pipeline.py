"""Четыре MCP-tools и точная передача результатов между ними."""

from uuid import uuid4

import httpx
import pytest
from mcp import Client

from game_reports.server import create_server


def catalog_response(request):
    query = request.url.params["query"]
    if query == "ошибка":
        return httpx.Response(503)
    games = ([{"title": "Звёздные тропы", "description": "Экспедиция в космос."}]
             if query == "космос" else [])
    return httpx.Response(200, json={"query": query, "games": games})


@pytest.mark.asyncio
async def test_registered_tools_pass_exact_results_and_save_once(tmp_path):
    async with httpx.AsyncClient(base_url="http://mock.test",
                                 transport=httpx.MockTransport(catalog_response)) as http:
        async with Client(create_server("http://mock.test", tmp_path, http)) as client:
            tools = (await client.list_tools()).tools
            assert {tool.name for tool in tools} == {
                "search_games", "summarize_games", "compose_report", "save_report",
            }
            assert "query" in next(tool for tool in tools if tool.name == "search_games").input_schema["properties"]
            assert "search_result" in next(tool for tool in tools if tool.name == "summarize_games").input_schema["properties"]
            assert "summary_result" in next(tool for tool in tools if tool.name == "save_report").input_schema["properties"]
            assert "summaries" in next(tool for tool in tools if tool.name == "compose_report").input_schema["properties"]

            search = await client.call_tool("search_games", {"query": "космос"})
            assert not search.is_error
            summary = await client.call_tool("summarize_games",
                                             {"search_result": search.structured_content})
            assert not summary.is_error
            operation_id = str(uuid4())
            save_args = {"summary_result": summary.structured_content, "operation_id": operation_id}
            saved = await client.call_tool("save_report", save_args)
            repeated = await client.call_tool("save_report", save_args)

    result = saved.structured_content
    assert not saved.is_error
    assert result == repeated.structured_content
    assert search.structured_content["games"][0]["title"] == "Звёздные тропы"
    assert summary.structured_content["game_count"] == 1
    assert result["status"] == "saved"
    assert result["game_count"] == 1
    assert result["report_markdown"] == summary.structured_content["markdown"]
    assert (tmp_path / result["file_name"]).read_text(encoding="utf-8") == result["report_markdown"]
    assert len(list(tmp_path.glob("*.md"))) == 1


@pytest.mark.asyncio
async def test_compose_report_uses_only_selected_summaries(tmp_path):
    async with Client(create_server("http://mock.test", tmp_path)) as client:
        result = await client.call_tool("compose_report", {
            "title": "Космос и сад",
            "summaries": [
                {"id": "space", "title": "Космос", "markdown": "Звёздные тропы"},
                {"id": "garden", "title": "Сад", "markdown": "Сад ветров"},
            ],
        })
    assert not result.is_error
    draft = result.structured_content
    assert draft["source_summary_ids"] == ["space", "garden"]
    assert "Звёздные тропы" in draft["markdown"]
    assert "Сад ветров" in draft["markdown"]
    assert "Городской вираж" not in draft["markdown"]


@pytest.mark.asyncio
async def test_empty_search_can_be_summarized_without_false_games(tmp_path):
    async with httpx.AsyncClient(base_url="http://mock.test",
                                 transport=httpx.MockTransport(catalog_response)) as http:
        async with Client(create_server("http://mock.test", tmp_path, http)) as client:
            search = await client.call_tool("search_games", {"query": "сады"})
            summary = await client.call_tool("summarize_games",
                                             {"search_result": search.structured_content})
    assert summary.structured_content["game_count"] == 0
    assert "не найдено" in summary.structured_content["markdown"]
    assert not list(tmp_path.glob("*.md"))


@pytest.mark.asyncio
async def test_search_normalizes_topic_and_failure_does_not_create_file(tmp_path):
    seen = []

    def respond(request):
        seen.append(request.url.params["query"])
        return catalog_response(request)

    async with httpx.AsyncClient(base_url="http://mock.test",
                                 transport=httpx.MockTransport(respond)) as http:
        async with Client(create_server("http://mock.test", tmp_path, http)) as client:
            search = await client.call_tool("search_games", {"query": "игры про космос"})
            failed = await client.call_tool("search_games", {"query": "ошибка"})
    assert seen == ["космос", "ошибка"]
    assert search.structured_content["query"] == "космос"
    assert failed.is_error
    assert not list(tmp_path.glob("*.md"))


@pytest.mark.asyncio
async def test_save_rejects_path_instead_of_uuid(tmp_path):
    async with Client(create_server("http://mock.test", tmp_path)) as client:
        result = await client.call_tool("save_report", {
            "summary_result": {"query": "космос", "game_count": 0, "markdown": "# Отчёт"},
            "operation_id": "../../outside",
        })
    assert result.is_error
    assert not list(tmp_path.glob("*.md"))
