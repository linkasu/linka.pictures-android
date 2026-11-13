#!/usr/bin/env python3
"""Keep the tested app SHA fixed while reviewing only the RuStore delivery repair."""
import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse

SOURCE = '1fec1cdeb842f170eddfa08436a595941b2be29a'
BRANCH = 'fix/rustore-draft-delivery-20261113'
REPO = 'linkasu/linka.pictures-android'
GOOGLE_RUN = '43308623148'
ALLOWED = {'.github/workflows/publish-rustore.yml', 'scripts/publish-rustore.py',
           'scripts/test-publish-rustore.py', 'scripts/check-rustore-delivery.py',
           'scripts/certificates/russian-trusted-root-ca.pem'}


def check(env, checkout, main, delivery, compare):
    assert env['GITHUB_REPOSITORY'] == REPO
    assert env['GITHUB_EVENT_NAME'] == 'workflow_dispatch'
    assert env['GITHUB_REF'] == 'refs/heads/' + BRANCH
    assert env['GITHUB_WORKFLOW_REF'] == REPO + '/.github/workflows/publish-rustore.yml@refs/heads/' + BRANCH
    assert env['SOURCE_SHA'] == main == SOURCE
    assert env['GOOGLE_RUN_ID'] == GOOGLE_RUN
    assert checkout == delivery == env['GITHUB_SHA']
    assert compare['base_commit']['sha'] == compare['merge_base_commit']['sha'] == SOURCE
    assert compare['status'] == 'ahead' and 0 < compare['total_commits'] <= 10
    files = compare['files']
    assert 0 < len(files) <= len(ALLOWED)
    assert all(f['filename'] in ALLOWED and f['status'] in ('added', 'modified') for f in files), 'App/source files changed'


def self_test():
    env = dict(GITHUB_REPOSITORY=REPO, GITHUB_EVENT_NAME='workflow_dispatch',
               GITHUB_REF='refs/heads/' + BRANCH, GITHUB_SHA='b' * 40, SOURCE_SHA=SOURCE,
               GOOGLE_RUN_ID=GOOGLE_RUN,
               GITHUB_WORKFLOW_REF=REPO + '/.github/workflows/publish-rustore.yml@refs/heads/' + BRANCH)
    compare = dict(base_commit=dict(sha=SOURCE), merge_base_commit=dict(sha=SOURCE),
                   status='ahead', total_commits=1, files=[dict(filename=f, status='modified') for f in ALLOWED])
    check(env, 'b' * 40, SOURCE, 'b' * 40, compare)
    bad = []
    for key, value in [('GITHUB_REF', 'refs/pull/1/merge'), ('GITHUB_REPOSITORY', 'fork/repo'),
                       ('SOURCE_SHA', 'c' * 40), ('GOOGLE_RUN_ID', '1'), ('GITHUB_EVENT_NAME', 'push')]:
        bad.append((dict(env, **{key: value}), copy.deepcopy(compare)))
    for field, value in [('files', [dict(filename='app/build.gradle', status='modified')]),
                         ('files', [dict(filename='scripts/publish-rustore.py', status='renamed')]),
                         ('total_commits', 11), ('merge_base_commit', dict(sha='c' * 40))]:
        changed = copy.deepcopy(compare)
        changed[field] = value
        bad.append((env, changed))
    for invalid_env, invalid_compare in bad:
        try:
            check(invalid_env, 'b' * 40, SOURCE, 'b' * 40, invalid_compare)
        except AssertionError:
            pass
        else:
            raise AssertionError('Unsafe delivery repair accepted')
    print('PASS: delivery-only repair guard, valid source and 9 rejected cases')


def main():
    if '--self-test' in sys.argv:
        return self_test()
    spec = importlib.util.spec_from_file_location('play', Path(__file__).with_name('publish-google-play.py'))
    play = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(play)
    token = os.environ['GH_TOKEN']
    root = 'https://api.github.com/repos/' + REPO
    main_sha = play.request(root + '/branches/main', token)['commit']['sha']
    delivery = play.request(root + '/branches/' + urllib.parse.quote(BRANCH, safe=''), token)['commit']['sha']
    compare = play.request(root + '/compare/' + SOURCE + '...' + os.environ['GITHUB_SHA'], token)
    checkout = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    check(os.environ, checkout, main_sha, delivery, compare)
    print('PASS: exact tested main app source; changes limited to reviewed RuStore delivery files')


if __name__ == '__main__':
    main()
