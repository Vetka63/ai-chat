"""Один сценарий для Sliding Window, Sticky Facts и двух независимых веток.

Без --execute ничего не создаёт и не вызывает LLM. С флагом выполняет около
26 вызовов: 6 dialogue для Sliding, 6 facts + 6 dialogue для Sticky, 4 вызова
подготовки Branching и по два dialogue в каждой ветке. Чаты остаются в UI для проверки.
"""

import argparse
import json
from decimal import Decimal
from pathlib import Path

import httpx


SCENARIO = [
    "Мы собираем ТЗ проекта Маяк. Цель — сервис записи к врачу. Ответь кратко: принято.",
    "Пользователи: пациент и администратор. Ответь кратко: принято.",
    "Бюджет 500 тысяч рублей. Ответь кратко: принято.",
    "Исправление: бюджет 700 тысяч рублей, старое число не использовать. Ответь кратко: принято.",
    "Технологии прототипа: Python, Vue и SQLite. Ответь кратко: принято.",
    "Срок — 30 ноября. Перечисли все согласованные требования, особенно цель и бюджет.",
]


def costs(runs):
    """Суммирует только известный API usage и не подменяет неизвестное нулевой ценой."""

    return {
        "known_tokens": sum(run["usage"]["total_tokens"] for run in runs if run["usage"]),
        "estimated_usd": str(sum(
            (Decimal(run["estimated_cost_usd"]) for run in runs if run["estimated_cost_usd"] is not None),
            Decimal(0),
        )),
        "unknown_calls": sum(run["usage"] is None for run in runs),
        "dialogue_calls": sum(run.get("purpose", "dialogue") == "dialogue" for run in runs),
        "facts_calls": sum(run.get("purpose") == "facts" for run in runs),
    }


def send(client, conversation_id, model, message, max_output_tokens):
    """Отправляет одну команду агенту и возвращает нормализованный HTTP-результат."""

    response = client.post("/api/v1/agents/dialogue/runs", json={
        "conversation_id": conversation_id,
        "model_id": model,
        "max_output_tokens": max_output_tokens,
        "message": message,
    })
    response.raise_for_status()
    return response.json()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8083")
    parser.add_argument("--model", default="deepseek-v4-flash")
    parser.add_argument("--window", type=int, default=4)
    parser.add_argument("--max-output-tokens", type=int, default=None)
    parser.add_argument("--output", default="docs/day10-results.json")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.window <= 100:
        parser.error("--window must be between 1 and 100")
    if args.max_output_tokens is not None and args.max_output_tokens < 1:
        parser.error("--max-output-tokens must be positive")
    print(f"Day 10 on {args.model}; about 26 LLM calls only with --execute.", flush=True)
    if not args.execute:
        return

    with httpx.Client(base_url=args.base_url, timeout=650) as client:
        catalog = client.get("/api/v1/models").raise_for_status().json()["models"]
        model = next(item for item in catalog if item["id"] == args.model)
        if not model["available"]:
            raise SystemExit("Provider key is not configured.")
        root = "/api/v1/agents/dialogue/conversations"
        report = {"model": args.model, "window": args.window, "scenario": SCENARIO, "strategies": {}}

        for mode in ("sliding_window", "sticky_facts"):
            conversation = client.post(root, json={
                "title": f"Day 10 · {mode}",
                "context_settings": {"mode": mode, "keep_last": args.window, "summarize_every": 10},
            }).raise_for_status().json()
            results = []
            for index, message in enumerate(SCENARIO, 1):
                result = send(client, conversation["id"], args.model, message, args.max_output_tokens)
                results.append({"step": index, "reply": result["reply"], "warnings": result["memory_warnings"]})
                print(f"{mode}: {index}/{len(SCENARIO)}", flush=True)
            detail = client.get(f"{root}/{conversation['id']}").raise_for_status().json()
            report["strategies"][mode] = {
                "conversation_id": conversation["id"], "results": results,
                "facts": detail["facts"], "runs": detail["runs"], "costs": costs(detail["runs"]),
            }
        branch_source = client.post(root, json={
            "title": "Day 10 · branching",
            "context_settings": {"mode": "branching", "keep_last": args.window, "summarize_every": 10},
        }).raise_for_status().json()
        for index, message in enumerate(SCENARIO[:4], 1):
            send(client, branch_source["id"], args.model, message, args.max_output_tokens)
            print(f"branching source: {index}/4", flush=True)
        checkpoint = client.post(f"{root}/{branch_source['id']}/checkpoints", json={
            "title": "Выбор архитектуры",
        }).raise_for_status().json()
        branches = client.post(f"/api/v1/agents/dialogue/checkpoints/{checkpoint['id']}/branches", json={
            "names": ["Монолит", "Микросервисы"],
        }).raise_for_status().json()
        decisions = ["Выбрали модульный монолит.", "Выбрали три микросервиса."]
        branch_results = []
        for branch, decision in zip(branches, decisions, strict=True):
            send(client, branch["id"], args.model, decision, args.max_output_tokens)
            answer = send(client, branch["id"], args.model, "Какую архитектуру мы выбрали?", args.max_output_tokens)
            detail = client.get(f"{root}/{branch['id']}").raise_for_status().json()
            branch_results.append({
                "conversation_id": branch["id"], "name": branch["branch_name"],
                "decision": decision, "reply": answer["reply"], "messages": detail["messages"],
                "runs": detail["runs"], "costs": costs(detail["runs"]),
            })
        report["branching"] = {"source_conversation_id": branch_source["id"], "checkpoint": checkpoint, "branches": branch_results}

        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Report: " + args.output, flush=True)


if __name__ == "__main__":
    main()
