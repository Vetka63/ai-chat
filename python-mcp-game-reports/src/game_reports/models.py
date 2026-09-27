"""Строгие контракты между поиском, обработкой и сохранением."""

from typing import Literal

from pydantic import BaseModel, Field


class Game(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)


class SearchResult(BaseModel):
    query: str
    games: list[Game]


class SummaryResult(BaseModel):
    query: str
    game_count: int
    markdown: str


class SummarySource(BaseModel):
    id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    markdown: str = Field(min_length=1)


class ReportDraft(BaseModel):
    title: str = Field(min_length=1)
    markdown: str = Field(min_length=1)
    source_summary_ids: list[str] = Field(min_length=1)


class ReportResult(BaseModel):
    status: Literal["saved"] = "saved"
    report_id: str
    file_name: str
    query: str
    game_count: int
    report_markdown: str
