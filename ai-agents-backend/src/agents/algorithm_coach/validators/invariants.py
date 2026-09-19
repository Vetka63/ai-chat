"""Консервативные AST-проверки Python. Это не sandbox и не доказательство семантики."""
import ast
import re
import sys
from agent_core.models import AgentError
from capabilities.invariants.models import RuleVerdict


class CoachInvariantPolicy:
    """Проверяет конфигурацию и поддерживаемые формы кода, ничего не исполняя."""
    def signature(self, value):
        tree = ast.parse('def '+value+':\n    pass')
        if len(tree.body) != 1 or not isinstance(tree.body[0], ast.FunctionDef):
            raise ValueError('Ожидается сигнатура одной функции')
        function = tree.body[0]
        if len(function.body) != 1 or not isinstance(function.body[0], ast.Pass):
            raise ValueError('Сигнатура не должна содержать код')
        return function

    def validate_rules(self, rules):
        ids, singletons = set(), set()
        for rule in rules:
            if rule.id and rule.id in ids:
                raise AgentError('invalid_invariants', 'ID правил не должны повторяться')
            ids.add(rule.id)
            if rule.active and rule.kind != 'semantic':
                if rule.kind in singletons:
                    raise AgentError('invalid_invariants', 'Для языка, сигнатуры и импортов допустимо по одному активному правилу')
                singletons.add(rule.kind)
            try:
                if rule.kind == 'language' and rule.value.lower() != 'python':
                    raise ValueError('В этой версии статическая проверка поддерживает только Python')
                if rule.kind == 'signature':
                    self.signature(rule.value)
                if rule.kind == 'allowed_imports' and rule.value != '-':
                    imports = [s.strip() for s in rule.value.split(',')]
                    if any(not s.isidentifier() or s not in sys.stdlib_module_names or s in {'builtins', 'importlib'} for s in imports):
                        raise ValueError('Укажите корни модулей стандартной библиотеки через запятую, либо - для запрета импортов')
            except (ValueError, SyntaxError) as exc:
                raise AgentError('invalid_invariants', str(exc)) from exc

    def validate(self, rules, text, require_solution=False):
        """Объяснение без кода допустимо; полный solution при кодовых правилах проверяется строже."""
        checks = []
        blocks = re.findall(r'```([^\n`]*)\n(.*?)```', text, flags=re.S)
        code_rules = [r for r in rules if r.active and r.kind != 'semantic']
        if not code_rules:
            return checks
        if not blocks and (require_solution or re.search(r'(?m)^\s*(?:def |class |import |from )', text)):
            blocks = [('', text)]
        for language, code in blocks:
            language = language.strip().lower()
            if language in ('text', 'plaintext', 'json', 'output') and not require_solution:
                continue
            if language not in ('', 'py', 'python', 'python3'):
                checks.append(RuleVerdict(rule_id=code_rules[0].id, verdict='conflict', reason='Блок кода не на Python: '+language))
                continue
            try:
                tree = ast.parse(code.strip())
            except (SyntaxError, ValueError, RecursionError):
                checks.append(RuleVerdict(rule_id=code_rules[0].id, verdict='uncertain', reason='Не удалось разобрать полный Python-код. Пришлите синтаксически корректный блок'))
                continue
            for rule in code_rules:
                if rule.kind == 'signature':
                    expected = self.signature(rule.value)
                    functions = [n for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]
                    matches = [n for n in functions if n.name == expected.name]
                    if (functions or require_solution) and (not matches or any(
                        isinstance(n, ast.AsyncFunctionDef) or ast.dump(n.args) != ast.dump(expected.args) or ast.dump(n.returns or ast.Constant(None)) != ast.dump(expected.returns or ast.Constant(None)) for n in matches)):
                        checks.append(RuleVerdict(rule_id=rule.id, verdict='conflict', reason='Требуется точная сигнатура '+rule.value))
                if rule.kind == 'allowed_imports':
                    allowed = set() if rule.value == '-' else {s.strip() for s in rule.value.split(',')}
                    for node in ast.walk(tree):
                        if isinstance(node, (ast.Import, ast.ImportFrom)):
                            names = [a.name.split('.')[0] for a in node.names] if isinstance(node, ast.Import) else [(node.module or '').split('.')[0]]
                            if any(n not in allowed for n in names) or isinstance(node, ast.ImportFrom) and node.level:
                                checks.append(RuleVerdict(rule_id=rule.id, verdict='conflict', reason='Импорт вне разрешённого списка'))
                                break
                        if isinstance(node, ast.Name) and node.id in {'__import__', 'eval', 'exec', 'compile', 'getattr', 'setattr', 'globals', 'locals', '__builtins__'} or isinstance(node, ast.Attribute) and (node.attr.startswith('__') or node.attr in {'import_module', 'load_module', 'exec_module'}):
                            checks.append(RuleVerdict(rule_id=rule.id, verdict='uncertain', reason='Динамическая загрузка/интроспекция не поддерживается проверкой импортов'))
                            break
        # Несколько блоков могут нарушать одно правило; в аудите одна оценка ID.
        unique = {}
        for check in checks:
            if check.rule_id not in unique or check.verdict == 'conflict':
                unique[check.rule_id] = check
        return list(unique.values())
