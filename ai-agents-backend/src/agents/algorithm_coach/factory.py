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
from .workflow_policy import CoachWorkflowPolicy
from capabilities.task_workflow.service import TaskWorkflow


def build_algorithm_coach(model, llm, store, repository, catalog, accounting):
    directory = Path(__file__).with_name('prompts')
    config = AlgorithmCoachConfig(model=model, system_prompt=(directory/'system.txt').read_text(encoding='utf-8'),
        proposals_prompt=(directory/'proposals.txt').read_text(encoding='utf-8'))
    return AlgorithmCoachAgent(config, store, LayeredMemory(repository, AlgorithmMemoryPolicy()),
        RecordedLlmCall(llm, accounting, catalog), catalog, accounting,
        CoachInputPolicy(), CoachOutputPolicy(), CoachContextPolicy(),
        TaskWorkflow(repository.workflow, CoachWorkflowPolicy()))
