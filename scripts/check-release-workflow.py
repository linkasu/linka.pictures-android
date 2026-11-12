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
    for flag in ('run_e2e_tests', 'sign_release'):
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
    assert set(jobs) == {'build', 'android-e2e', 'bundle-release'}
    assert not re.search(r'^    (if|needs):', jobs['build'], re.M)
    for command in ('python3 -B scripts/check-release-workflow.py',
                     'python3 -B scripts/check-prerelease.py',
                     './gradlew :shared:jvmTest :app:testDebugUnitTest :app:testReleaseUnitTest :app:lint :shared:lint :app:verifyReleaseSafety :app:verifyUiSelectors',
                     'python3 -B shared/src/commonTest/check_localization.py',
                     './gradlew :app:assembleDebug :app:assembleDebugAndroidTest :shared:assembleDebugAndroidTest'):
        assert command in jobs['build']
    assert '\n    needs: build\n' in jobs['android-e2e']
    assert '\n    needs: [build, android-e2e]\n' in jobs['bundle-release']
    gates = {}
    for job in ('android-e2e', 'bundle-release'):
        expression = re.search(r'^    if: (.+)$', jobs[job], re.M)[1]
        tree = ast.parse(expression.replace('&&', ' and ').replace('||', ' or '), mode='eval')
        allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq,
                   ast.Attribute, ast.Subscript, ast.Name, ast.Load, ast.Constant)
        assert all(isinstance(node, allowed) for node in ast.walk(tree))
        assert all(not node.attr.startswith('_') for node in ast.walk(tree)
                   if isinstance(node, ast.Attribute))
        gates[job] = compile(tree, '<workflow condition>', 'eval')
    count = 0
    for event, ref, e2e_enabled, sign, build, e2e in itertools.product(
            ('push', 'pull_request', 'workflow_dispatch'),
            ('refs/heads/main', 'refs/heads/other'), (False, True), (False, True),
            ('success', 'failure', 'cancelled', 'skipped'),
            ('success', 'failure', 'cancelled', 'skipped')):
        context = dict(github=NS(event_name=event, ref=ref),
                        inputs=NS(run_e2e_tests=e2e_enabled, sign_release=sign),
                        needs={'build': NS(result=build), 'android-e2e': NS(result=e2e)})
        for job, gate in gates.items():
            expected = (event in ('push', 'pull_request') or e2e_enabled) and build == 'success'
            if job == 'bundle-release':
                expected = (event == 'workflow_dispatch' and e2e_enabled and sign
                            and ref == 'refs/heads/main' and build == 'success' and e2e == 'success')
            actual = bool(eval(gate, {'__builtins__': {}}, context))
            assert actual == expected, (job, event, ref, e2e_enabled, sign, build, e2e)
        count += 1
    assert count == 384, 'Keep exhaustive event/input/result gate coverage'
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
    for forbidden in ('firebase', 'gcloud', 'google-github-actions/', 'run_cloud_tests',
                      'PROJECT_ID', '--robo-script', '--type robo'):
        assert forbidden.lower() not in workflow.lower()
    e2e_job = jobs['android-e2e']
    assert e2e_job.startswith('    runs-on: ubuntu-latest\n')
    assert '\n    timeout-minutes: 30\n' in e2e_job
    assert e2e_job.count('uses: reactivecircus/android-emulator-runner@v2') == 1
    for setting in ('api-level: 35', 'target: google_apis', 'arch: x86_64',
                    'profile: pixel_2', 'force-avd-creation: true', 'emulator-boot-timeout: 600'):
        assert f'          {setting}\n' in e2e_job
    assert 'sudo udevadm trigger --name-match=kvm' in e2e_job
    commands = re.findall(r'^            (\./gradlew .+)$', e2e_job, re.M)
    assert commands == [
        './gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=su.linka.pictures.activity.DevelopmentStartupTest,su.linka.pictures.activity.MessageRowLayoutTest -Pandroid.testInstrumentationRunnerArguments.expected_page_size=4096 &&',
        './gradlew :shared:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=su.linka.pictures.bootstrap.AndroidLibraryAdmissionTest',
    ], 'Exactly three deterministic classes in two sequential tasks; Gradle propagates failures'
    assert '          script: >-\n' in e2e_job, 'The EXIT trap and tests must share one shell'
    assert "trap 'status=$?; trap - EXIT;" in e2e_job
    screenshot_dir = '/sdcard/Android/data/su.linka.pictures.development/files/message-row-layout/'
    assert f'if adb shell test -d {screenshot_dir}; then' in e2e_job
    assert f'mkdir -p artifacts/e2e && adb pull {screenshot_dir} artifacts/e2e/;' in e2e_job
    assert 'capture_status=$?; if [ "$status" -eq 0 ]; then status=$capture_status; fi;' in e2e_job
    assert 'fi; exit "$status"\' EXIT;' in e2e_job, 'Screenshot collection must preserve test failures'
    assert '            artifacts/e2e/\n' in e2e_job
    assert 'name: android-e2e-reports' in e2e_job and 'if: always()' in e2e_job
    assert 'secrets.' not in e2e_job, 'Emulator tests do not need cloud/account credentials'
    for forbidden in ('continue-on-error', 'strategy:', 'retry', '|| true', 'connectedCheck'):
        assert forbidden not in e2e_job, 'No parallel devices, retries, broad exploration or ignored failures'
    assert re.search(r'- name: Clean up keystore\n        if: always\(\)\n'
                     r'        run: rm -f -- "\$\{GITHUB_WORKSPACE\}/ci-release\.jks"', release)
    assert 'rm -rf' not in workflow
    print(f'PASS: workflow structure and {count} event/input/result combinations (offline only)')


if __name__ == '__main__':
    check()
