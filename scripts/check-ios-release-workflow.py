#!/usr/bin/env python3
"""Offline release structure, embedded script syntax and source-CI gate checks."""
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from unittest.mock import patch


def check():
    root = Path(__file__).resolve().parents[1]
    workflow = (root / '.github/workflows/publish-ios.yml').read_text()
    trigger = workflow.split('\npermissions:', 1)[0]
    assert '  workflow_dispatch:' in trigger and '      source_run_id:' in trigger
    assert not re.search(r'^  (push|pull_request|workflow_run):', trigger, re.M)
    assert "if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'" in workflow
    assert 'cancel-in-progress: false' in workflow and 'runs-on: macos-26' in workflow
    assert 'actions: read' in workflow and 'contents: read' in workflow
    ordered = ['Gate on pinned successful phone and tablet CI', 'Set up Java',
               'Build release framework and stage Compose resources',
               'Install and validate temporary signing material',
               'Archive with manual distribution signing',
               'Verify archive and matching application dSYM',
               'Export exact IPA for upload', 'Upload exact IPA once to App Store Connect',
               'Remove only owned signing material and restore search list']
    offsets = [workflow.index('- name: ' + name) for name in ordered]
    assert offsets == sorted(offsets)
    assert workflow.index('secrets.') > offsets[3]
    assert workflow.count('xcrun altool --upload-app') == 1
    assert 'if: always()\n        run: |\n          python3' in workflow
    for required in ('CODE_SIGN_STYLE=Manual', "CODE_SIGN_IDENTITY='Apple Distribution'",
                     'RELEASE_VERSION: 2.0.0', "RELEASE_BUILD_NUMBER: '2'",
                     'IOS_BUNDLE_ID: su.linka.pictures', 'DEVELOPMENT_TEAM: 29HEQ626X2',
                     ':shared:linkReleaseFrameworkIosArm64 :shared:syncComposeResourcesForIos',
                     'CONFIGURATION: Release', 'PLATFORM_NAME: iphoneos', 'ARCHS: arm64',
                     'UNLOCALIZED_RESOURCES_FOLDER_PATH: .', 'brew install gnupg',
                     'int(re.search', '>= 26', 'codesign --verify --deep --strict',
                     "entitlements.get('get-task-allow') is False", 'DeveloperCertificates',
                     "'-passin', 'env:P12_PASSWORD'", "'-T', '/usr/bin/codesign', '-T', '/usr/bin/security'",
                     "'delete-keychain'", "'search-list.json'", "'installed-profiles.json'",
                     'release-metadata.json', 'sha256', "method='app-store-connect'",
                     'manageAppVersionAndBuildNumber=False', 'not submitted to App Review'):
        assert required in workflow, required
    for forbidden in ('firebase', 'allowProvisioningUpdates', 'continue-on-error',
                      'set -x', 'securitypolicy', 'retry:', 'rm -rf'):
        assert forbidden.lower() not in workflow.lower(), forbidden
    # Validate every embedded Python and shell block without running build/sign/upload commands.
    scripts = re.findall(r'^        run: \|\n((?:          .*\n|\n)+)', workflow, re.M)
    assert len(scripts) >= 8
    gate = None
    for script in scripts:
        shell = '\n'.join(line[10:] if line.startswith('          ') else line for line in script.splitlines()) + '\n'
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        for python in re.findall(r"<<'PY'\n(.*?)\nPY", shell, re.S):
            compile(python, '<workflow Python>', 'exec')
            if "run_id = os.environ['SOURCE_RUN_ID']" in python:
                gate = python
    assert gate is not None
    sha = 'a' * 40
    run = dict(repository=dict(full_name='owner/repo'), path='.github/workflows/ios.yml',
               event='push', head_branch='main', head_sha=sha, status='completed',
               conclusion='success', run_attempt=1)
    jobs = [dict(name=f'simulator-tests ({device})', status='completed', conclusion='success', head_sha=sha)
            for device in ('phone', 'tablet')]
    cases = [('valid', True, {}, None, '123', sha),
             ('wrong path', False, dict(path='.github/workflows/android.yml'), None, '123', sha),
             ('PR run', False, dict(event='pull_request'), None, '123', sha),
             ('wrong branch', False, dict(head_branch='feature'), None, '123', sha),
             ('stale SHA', False, dict(head_sha='b' * 40), None, '123', sha),
             ('failed run', False, dict(conclusion='failure'), None, '123', sha),
             ('running', False, dict(status='in_progress'), None, '123', sha),
             ('foreign repo', False, dict(repository=dict(full_name='fork/repo')), None, '123', sha),
             ('missing tablet', False, {}, jobs[:1], '123', sha),
             ('duplicate jobs', False, {}, jobs + jobs, '123', sha),
             ('CI rerun during gate', False, {}, None, '123', sha),
             ('bad ID', False, {}, None, '../123', sha),
             ('main advanced', False, {}, None, '123', 'b' * 40)]
    for result in ('failure', 'cancelled', 'skipped', None):
        changed = copy.deepcopy(jobs)
        changed[1]['conclusion'] = result
        cases.append((f'tablet {result}', False, {}, changed, '123', sha))
    for label, expected, changes, job_changes, run_id, main_sha in cases:
        candidate = dict(run, **changes)
        run_reads = 0
        def fake_output(argv, **kwargs):
            nonlocal run_reads
            if argv[:2] == ['git', 'rev-parse']:
                return sha + '\n'
            path = argv[2]
            if path.endswith('/commits/main'):
                return json.dumps(dict(sha=main_sha))
            if '/jobs?' in path:
                return json.dumps([dict(jobs=jobs if job_changes is None else job_changes)])
            run_reads += 1
            if label == 'CI rerun during gate' and run_reads > 1:
                return json.dumps(dict(candidate, run_attempt=2))
            return json.dumps(candidate)
        with tempfile.TemporaryDirectory() as directory:
            env = dict(SOURCE_RUN_ID=run_id, GITHUB_REPOSITORY='owner/repo', GITHUB_SHA=sha, RELEASE_DIR=directory)
            with patch.dict(os.environ, env), patch('subprocess.check_output', side_effect=fake_output):
                try:
                    exec(compile(gate, '<source-CI gate>', 'exec'), {})
                    actual = True
                except AssertionError:
                    actual = False
            assert actual == expected, label
    print(f'PASS: iOS release structure, embedded syntax and {len(cases)} source-CI gate cases (offline only)')


if __name__ == '__main__':
    check()
