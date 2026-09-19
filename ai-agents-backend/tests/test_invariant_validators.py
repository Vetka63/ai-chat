"""Локальные проверки Дня 14: без HTTP и без вызова LLM."""
import pytest
from agent_core.models import AgentError
from agents.algorithm_coach.validators.invariants import CoachInvariantPolicy
from capabilities.invariants.models import RuleDraft, TaskInvariant


def rule(kind, value):
    return TaskInvariant(id=kind, kind=kind, label=kind, value=value, revision=1)


@pytest.mark.parametrize('kind,value', [
    ('language', 'python'), ('signature', 'two_sum(nums, target)'),
    ('allowed_imports', '-'), ('allowed_imports', 'collections, math'),
    ('semantic', 'Не менять входной массив'),
])
def test_supported_configurations(kind, value):
    CoachInvariantPolicy().validate_rules([rule(kind, value)])


@pytest.mark.parametrize('kind,value', [
    ('language', 'java'), ('signature', 'not a signature'),
    ('signature', 'f():\n return 1\ndef injected()'),
    ('allowed_imports', 'numpy'), ('allowed_imports', 'importlib'),
    ('allowed_imports', 'builtins'), ('allowed_imports', 'math,,sys'),
])
def test_invalid_configurations(kind, value):
    with pytest.raises(AgentError):
        CoachInvariantPolicy().validate_rules([rule(kind, value)])


def test_duplicate_singletons_and_ids_are_rejected():
    policy = CoachInvariantPolicy()
    with pytest.raises(AgentError):
        policy.validate_rules([rule('language', 'python'), rule('language', 'python')])
    drafts = [RuleDraft(kind='language', label='a', value='python'), RuleDraft(kind='language', label='b', value='python')]
    with pytest.raises(AgentError):
        policy.validate_rules(drafts)


@pytest.mark.parametrize('code', ['import numpy', 'from os import system', 'from .helper import x'])
def test_imports_outside_allowlist_are_conflicts(code):
    checks = CoachInvariantPolicy().validate([rule('allowed_imports', 'math')], code, True)
    assert checks[0].verdict == 'conflict'


@pytest.mark.parametrize('code', ["loader = __import__; loader('numpy')", "getattr(x, 'import_module')('numpy')", "exec('import numpy')", "x.__class__.__base__"])
def test_dynamic_forms_are_not_silently_allowed(code):
    checks = CoachInvariantPolicy().validate([rule('allowed_imports', '-')], code, True)
    assert checks[0].verdict == 'uncertain'


def test_signature_and_complete_solution_are_checked():
    policy = CoachInvariantPolicy()
    rules = [rule('signature', 'two_sum(nums, target)')]
    assert not policy.validate(rules, '```python\ndef two_sum(nums, target):\n    return []\n```', True)
    for code in ['def other(nums, target): return []', 'def two_sum(target, nums): return []', 'async def two_sum(nums, target): return []', 'answer = []']:
        assert policy.validate(rules, code, True)[0].verdict == 'conflict'


def test_explanation_without_code_is_not_a_static_violation():
    rules = [rule('language', 'python'), rule('signature', 'two_sum(nums, target)')]
    assert not CoachInvariantPolicy().validate(rules, 'Сначала обсудим хеш-таблицу.')


def test_non_python_and_invalid_syntax_fail_closed():
    policy = CoachInvariantPolicy()
    rules = [rule('language', 'python')]
    assert policy.validate(rules, '```java\nclass Solution {}\n```')[0].verdict == 'conflict'
    assert policy.validate(rules, '```python\ndef f(:\n```')[0].verdict == 'uncertain'


def test_static_checks_do_not_execute_code():
    rules = [rule('allowed_imports', 'math')]
    assert not CoachInvariantPolicy().validate(rules, 'import math\nraise RuntimeError("never executed")', True)


def test_multiple_blocks_have_one_verdict_per_rule_with_conflict_priority():
    checks = CoachInvariantPolicy().validate([rule('allowed_imports', '-')],
        '```python\ngetattr(obj, "x")\n```\n```python\nimport numpy\n```')
    assert len(checks) == 1 and checks[0].verdict == 'conflict'
