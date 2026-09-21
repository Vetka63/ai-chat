"""Сборка наставника из общих механизмов и собственных политик."""
from pathlib import Path
from capabilities.memory_layers.service import LayeredMemory
from capabilities.token_accounting.call import RecordedLlmCall
from .agent import AlgorithmCoachAgent
from .config import AlgorithmCoachConfig
from .context_policy import CoachContextPolicy
from .input_policy import CoachInputPolicy
from .memory_policy import AlgorithmMemoryPolicy
from .output_policy import CoachOutputPolicy
from .lifecycle_policy import CoachLifecyclePolicy
from .validators.invariants import CoachInvariantPolicy
from .llm_judge.invariants import InvariantJudge
from capabilities.invariants.service import InvariantGuard


def build_algorithm_coach(model, llm, store, repository, catalog, accounting, workflow_repository=None,
                          invariant_repository=None):
    directory = Path(__file__).with_name('prompts')
    config = AlgorithmCoachConfig(model=model, system_prompt=(directory/'system.txt').read_text(encoding='utf-8'),
        proposals_prompt=(directory/'proposals.txt').read_text(encoding='utf-8'))
    calls = RecordedLlmCall(llm, accounting, catalog)
    invariants = InvariantGuard(invariant_repository, CoachInvariantPolicy(),
        InvariantJudge(calls, accounting, catalog)) if invariant_repository else None
    return AlgorithmCoachAgent(config, store, LayeredMemory(repository, AlgorithmMemoryPolicy()),
        calls, catalog, accounting, CoachInputPolicy(), CoachOutputPolicy(), CoachContextPolicy(),
        workflow_repository, invariants, CoachLifecyclePolicy())
