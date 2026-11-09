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
    for field in ('android_version_code', 'release_version'):
        block = re.search(rf'^      {field}:\n((?:        .+\n)+)', triggers, re.M)
        assert block and '        type: string\n' in block[1]
        assert "        default: ''\n" in block[1], 'Do not guess production metadata'
    assert '\npermissions:\n  contents: read\n' in workflow
    jobs = dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                           workflow.split('\njobs:\n', 1)[1], re.M | re.S))
    assert set(jobs) == {'build', 'firebase-test', 'bundle-release'}
    assert not re.search(r'^    (if|needs):', jobs['build'], re.M)
    for command in ('python3 -B scripts/check-release-workflow.py',
                     'python3 -B scripts/check-prerelease.py',
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
    assert 'ANDROID_VERSION_CODE: ${{ inputs.android_version_code }}' in release
    assert 'RELEASE_VERSION: ${{ inputs.release_version }}' in release
    assert release.index('run: ./gradlew :app:verifyReleaseMetadata') < release.index('- name: Decode keystore')
    signed_build = 'run: ./gradlew :app:verifyReleaseSigning :app:verifyReleaseSafety :app:bundleRelease'
    signature_check = 'run: python3 -B scripts/check-prerelease.py --aab app/build/outputs/bundle/release/app-release.aab'
    assert signed_build in release
    assert signature_check in release
    assert release.index('- name: Decode keystore') < release.index(signed_build)
    assert release.index(signed_build) < release.index(signature_check) < release.index('- name: Upload release bundle')
    assert '--robo-script' not in workflow
    assert '--test-targets "class su.linka.pictures.bootstrap.AndroidLibraryAdmissionTest"' in jobs['firebase-test']
    assert '--test-targets "class su.linka.pictures.activity.DevelopmentStartupTest"' in jobs['firebase-test']
    cloud_runs = jobs['firebase-test'].split('gcloud firebase test android run')[1:]
    assert len(cloud_runs) == 3, 'One bounded execution per approved lane'
    for run, minutes in zip(cloud_runs, (10, 15, 5)):
        assert f'--timeout {minutes}m' in run and '--num-flaky-test-attempts=0' in run
        assert run.count('--timeout ') == 1 and run.count('--num-flaky-test-attempts') == 1
        assert run.count('--device ') == 1 and '--num-shards' not in run and '--device-ids' not in run
    assert '--environment-variables expected_page_size=16384' in cloud_runs[0]
    assert all('--device model=MediumPhone_ps16k.arm,version=36,' in cloud_runs[i] for i in (0, 2))
    assert '--device model=redfin,version=30,' in cloud_runs[1]
    assert re.search(r'- name: Clean up keystore\n        if: always\(\)\n'
                     r'        run: rm -f -- "\$\{GITHUB_WORKSPACE\}/ci-release\.jks"', release)
    assert 'rm -rf' not in workflow
    print(f'PASS: workflow structure and {count} event/input/result combinations (offline only)')


if __name__ == '__main__':
    check()
