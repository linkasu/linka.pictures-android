#!/usr/bin/env python3
"""CI-only Play publication. Never retry mutations, especially an uncertain commit.

API contracts: https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit
https://developers.google.com/android-publisher/api-ref/rest/v3/generatedapks/list
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = 'su.linka.pictures'
BASE = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/'
MAX_FILE = 512 * 1024 * 1024
MAX_JSON = 4 * 1024 * 1024


class PublishError(Exception):
    pass


class ApiError(PublishError):
    pass


class UncertainRequest(PublishError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward bearer credentials to a redirected host.
        return None


def request(url, token, method='GET', body=None, file=None):
    if not token:
        raise PublishError('Missing API credential')
    headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/json'}
    data = None
    if file is not None:
        if not 0 < file.stat().st_size <= MAX_FILE:
            raise PublishError('Artifact must be nonempty and at most 512 MiB')
        data = file.read_bytes()
        headers['Content-Type'] = 'application/octet-stream'
    elif body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.build_opener(NoRedirect).open(req, timeout=180) as response:
            raw = response.read(MAX_JSON + 1)
        if len(raw) > MAX_JSON:
            raise UncertainRequest('API response too large; inspect remote state before retry')
        return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        # API error bodies and exception strings may contain credentials: never log them.
        code = error.code
        error.close()
        raise ApiError(f'API HTTP {code}; inspect console before retry') from None
    except (OSError, ValueError):
        raise UncertainRequest('API result unknown; inspect remote state before retry') from None


def validate_inputs(args):
    if args.package != PACKAGE or args.version_code != 7 or args.release_version != '2.0.1':
        raise PublishError('This approved lane allows only su.linka.pictures 2.0.1 (7)')
    if not re.fullmatch(r'[1-9][0-9]*', args.source_run_id):
        raise PublishError('Explicit positive source_run_id required')
    if not re.fullmatch(r'[0-9a-f]{40}', args.source_sha):
        raise PublishError('Explicit checkout source SHA required')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', args.repository):
        raise PublishError('Invalid repository')


def verify_source(args):
    root = f'https://api.github.com/repos/{args.repository}/actions/runs/{args.source_run_id}'
    token = os.environ.get('GH_TOKEN', '')
    run = request(root, token)
    if (run.get('head_branch') != 'main' or run.get('head_sha') != args.source_sha
            or run.get('path') != '.github/workflows/android.yml'
            or run.get('status') != 'completed' or run.get('conclusion') != 'success'
            or run.get('event') != 'workflow_dispatch'
            or run.get('repository', {}).get('full_name') != args.repository
            or run.get('head_repository', {}).get('full_name') != args.repository):
        raise PublishError('Source must be successful main Android CI at the exact checkout SHA')
    jobs = request(root + '/jobs?per_page=100&filter=latest', token)
    if jobs.get('total_count', 0) > 100:
        raise PublishError('Too many source jobs; refusing incomplete validation')
    for name in ('build', 'android-e2e', 'bundle-release'):
        matching = [job for job in jobs.get('jobs', []) if job.get('name') == name]
        if len(matching) != 1 or matching[0].get('conclusion') != 'success':
            raise PublishError(f'Source job {name} must have succeeded')
    artifacts = request(root + '/artifacts?per_page=100', token)
    if artifacts.get('total_count', 0) > 100:
        raise PublishError('Too many artifacts; refusing incomplete validation')
    matching = [a for a in artifacts.get('artifacts', []) if a.get('name') == 'android-release-aab']
    if len(matching) != 1 or matching[0].get('expired') is not False:
        raise PublishError('Require exactly one unexpired android-release-aab artifact')
    return {'artifact_id': matching[0]['id'], 'artifact_digest': matching[0].get('digest'),
            'source_run_id': args.source_run_id, 'source_sha': args.source_sha}


def production_release(track, version_code, release_version):
    if track.get('track') != 'production':
        raise PublishError('Unexpected production track response')
    for release in track.get('releases', []):
        if release.get('status') != 'completed':
            raise PublishError('Existing draft, halted or staged release requires manual resolution')
        if any(int(code) >= version_code for code in release.get('versionCodes', [])):
            raise PublishError('Production already contains this or a newer version')
        if release.get('countryTargeting'):
            raise PublishError('Existing country-targeted release requires manual resolution')
    # Do not call country availability APIs or change the app country configuration.
    return {'track': 'production', 'releases': [{
        'versionCodes': [str(version_code)], 'status': 'completed',
        'name': f'{release_version} ({version_code})',
        'releaseNotes': [{'language': 'ru-RU', 'text': 'Высота строки сообщения по умолчанию — 20%. В настройках доступны 16%, 20%, 24%, 28% и 32%.\nСетка карточек центрируется в области общения.\nУлучшена совместимость резервных копий; существующие наборы и черновики сохраняются.'}],
    }]}


def save(evidence, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)


def publish(args, evidence):
    token = os.environ.get('ACCESS_TOKEN', '')
    base = BASE + PACKAGE
    evidence['status'] = 'EDIT_INSERT_PENDING'
    save(evidence, args.evidence)
    edit = request(base + '/edits', token, 'POST', {})
    edit_id = edit.get('id', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', edit_id):
        raise PublishError('Invalid edit ID')
    evidence.update(edit_id=edit_id, status='EDIT_CREATED')
    save(evidence, args.evidence)
    edit_url = base + '/edits/' + edit_id
    track = request(edit_url + '/tracks/production', token)
    release = production_release(track, args.version_code, args.release_version)
    evidence['status'] = 'BUNDLE_UPLOAD_PENDING'
    save(evidence, args.evidence)
    upload_url = edit_url.replace('/androidpublisher/', '/upload/androidpublisher/', 1)
    bundle = request(upload_url + '/bundles?uploadType=media', token, 'POST', file=args.aab)
    if str(bundle.get('versionCode')) != str(args.version_code):
        raise PublishError('Uploaded bundle versionCode does not match approved version')
    if bundle.get('sha256', '').lower() != evidence['aab_sha256']:
        raise PublishError('Uploaded bundle SHA256 does not match downloaded artifact')
    evidence['status'] = 'BUNDLE_UPLOADED'
    save(evidence, args.evidence)
    request(edit_url + '/tracks/production', token, 'PUT', release)
    request(edit_url + ':validate', token, 'POST')
    evidence['status'] = 'COMMIT_RESULT_UNKNOWN'
    save(evidence, args.evidence)
    # Refuse to cancel any review already in progress; never fallback to a draft commit.
    request(edit_url + ':commit?changesNotSentForReview=false&changesInReviewBehavior=ERROR_IF_IN_REVIEW',
            token, 'POST')
    evidence['status'] = 'EDIT_COMMITTED_FOR_REVIEW'
    save(evidence, args.evidence)
    # Availability is not guaranteed. Return metadata without inventing a ready APK.
    try:
        generated = request(base + f'/generatedApks/{args.version_code}', token)
        evidence['generated_universal_apks'] = [
            {'certificate_sha256': group.get('certificateSha256Hash'),
             'download_id': group['generatedUniversalApk']['downloadId']}
            for group in generated.get('generatedApks', [])
            if group.get('generatedUniversalApk', {}).get('downloadId')]
        evidence['universal_apk_status'] = ('METADATA_AVAILABLE_NOT_DOWNLOADED'
            if evidence['generated_universal_apks'] else 'NOT_AVAILABLE')
    except PublishError:
        evidence['universal_apk_status'] = 'QUERY_FAILED_NOT_DOWNLOADED'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', default=PACKAGE)
    parser.add_argument('--source-run-id', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--release-version', required=True)
    parser.add_argument('--aab', type=Path)
    parser.add_argument('--evidence', type=Path, default=Path('publish-evidence.json'))
    parser.add_argument('--verify-source-only', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    evidence = {'status': 'BLOCKED', 'outcome': 'BLOCKED', 'package': args.package, 'version_code': args.version_code,
                'release_version': args.release_version, 'source_sha': args.source_sha,
                'source_run_id': args.source_run_id}
    result = 0
    try:
        validate_inputs(args)
        if args.dry_run:
            evidence['status'] = 'DRY_RUN_NO_EXTERNAL_CALLS_SOURCE_NOT_VERIFIED'
        else:
            evidence.update(verify_source(args))
            evidence['status'] = 'SOURCE_VERIFIED'
        if not args.verify_source_only:
            if args.aab is None or not args.aab.is_file() or not 0 < args.aab.stat().st_size <= MAX_FILE:
                raise PublishError('Require one nonempty AAB at most 512 MiB')
            with args.aab.open('rb') as stream:
                evidence['aab_sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
            if not args.dry_run:
                publish(args, evidence)
        evidence['outcome'] = evidence['status']
    except PublishError as error:
        # Keep the last persisted mutation phase, especially an uncertain commit.
        evidence['error'] = str(error)
        evidence['outcome'] = 'FAILED_OR_UNCERTAIN_DO_NOT_BLINDLY_RERUN'
        result = 1
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        evidence['error'] = 'Invalid local input or API response; inspect evidence and remote state. Do not blindly rerun.'
        evidence['outcome'] = 'FAILED_OR_UNCERTAIN_DO_NOT_BLINDLY_RERUN'
        result = 1
    finally:
        save(evidence, args.evidence)
        print(json.dumps(evidence, ensure_ascii=False))
    return result


if __name__ == '__main__':
    sys.exit(main())
