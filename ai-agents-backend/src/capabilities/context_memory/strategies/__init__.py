"""Стратегии формирования LLM-контекста, выбираемые через реестр."""

from .branching import BranchingStrategy
from .full import FullHistoryStrategy
from .sliding_window import SlidingWindowStrategy
from .sticky_facts import StickyFactsStrategy
from .summary import SummaryStrategy

__all__ = [
    "BranchingStrategy",
    "FullHistoryStrategy",
    "SlidingWindowStrategy",
    "StickyFactsStrategy",
    "SummaryStrategy",
]
