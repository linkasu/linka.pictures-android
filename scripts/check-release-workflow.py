#!/usr/bin/env python3
"""Offline assertions for this workflow's layout/gates, not a GitHub runtime test."""
import ast
import itertools
from pathlib import Path
import re
from types import SimpleNamespace as NS


def check():
    workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/android.yml').read_text()
    triggers = workflow.split('\nconcurrency:', 1)[0]
    for event in ('push', 'pull_request'):
        assert f'  {event}:\n    branches:\n      - main\n' in triggers
    assert '  workflow_dispatch:\n    inputs:\n' in triggers
    for flag in ('run_cloud_tests', 'sign_release'):
        block = re.search(rf'^      {flag}:\n((?:        .+\n)+)', triggers, re.M)
        assert block, f'missing manual input: {flag}'
        assert '        type: boolean\n' in block[1]
        assert '        default: false\n' in block[1]
    assert '\npermissions:\n  contents: read\n' in workflow
    jobs = dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                           workflow.split('\njobs:\n', 1)[1], re.M | re.S))
    assert set(jobs) == {'build', 'firebase-test', 'bundle-release'}
    assert not re.search(r'^    (if|needs):', jobs['build'], re.M)
    for command in ('python3 -B scripts/check-release-workflow.py',
                     './gradlew :shared:jvmTest :app:testDebugUnitTest :app:testReleaseUnitTest :app:lint :shared:lint :app:verifyReleaseSafety :app:verifyUiSelectors',
                     'python3 -B shared/src/commonTest/check_localization.py',
                     './gradlew :app:assembleDebug :app:assembleDebugAndroidTest :shared:assembleDebugAndroidTest'):
        assert command in jobs['build']
    assert '\n    needs: build\n' in jobs['firebase-test']
    assert '\n    needs: [build, firebase-test]\n' in jobs['bundle-release']
    gates = {}
    for job in ('firebase-test', 'bundle-release'):
        expression = re.search(r'^    if: (.+)$', jobs[job], re.M)[1]
        tree = ast.parse(expression.replace('&&', ' and '), mode='eval')
        allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Compare, ast.Eq,
                   ast.Attribute, ast.Subscript, ast.Name, ast.Load, ast.Constant)
        assert all(isinstance(node, allowed) for node in ast.walk(tree))
        assert all(not node.attr.startswith('_') for node in ast.walk(tree)
                   if isinstance(node, ast.Attribute))
        gates[job] = compile(tree, '<workflow condition>', 'eval')
    count = 0
    for event, ref, cloud, sign, build, firebase in itertools.product(
            ('push', 'pull_request', 'workflow_dispatch'),
            ('refs/heads/main', 'refs/heads/other'), (False, True), (False, True),
            ('success', 'failure', 'cancelled', 'skipped'),
            ('success', 'failure', 'cancelled', 'skipped')):
        context = dict(github=NS(event_name=event, ref=ref),
                       inputs=NS(run_cloud_tests=cloud, sign_release=sign),
                       needs={'build': NS(result=build), 'firebase-test': NS(result=firebase)})
        for job, gate in gates.items():
            expected = event == 'workflow_dispatch' and cloud and build == 'success'
            if job == 'bundle-release':
                expected = expected and sign and ref == 'refs/heads/main' and firebase == 'success'
            actual = bool(eval(gate, {'__builtins__': {}}, context))
            assert actual == expected, (job, event, ref, cloud, sign, build, firebase)
        count += 1
    release = jobs['bundle-release']
    assert 'run: ./gradlew :app:verifyReleaseSafety :app:bundleRelease' in release
    assert '--robo-script' not in workflow
    assert '--test-targets "class su.linka.pictures.bootstrap.AndroidLibraryAdmissionTest"' in jobs['firebase-test']
    assert '--test-targets "class su.linka.pictures.activity.DevelopmentStartupTest"' in jobs['firebase-test']
    assert re.search(r'- name: Clean up keystore\n        if: always\(\)\n'
                     r'        run: rm -f -- "\$\{GITHUB_WORKSPACE\}/ci-release\.jks"', release)
    assert 'rm -rf' not in workflow
    print(f'PASS: workflow structure and {count} event/input/result combinations (offline only)')


if __name__ == '__main__':
    check()
