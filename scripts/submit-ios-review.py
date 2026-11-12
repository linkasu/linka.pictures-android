#!/usr/bin/env python3
"""Explicit CI-only App Review submission; --prepare is entirely offline.

Apple's first-party endpoint/schema references (checked 2026-11-12):
https://developer.apple.com/documentation/appstoreconnectapi/post-v1-reviewsubmissions
https://developer.apple.com/documentation/appstoreconnectapi/post-v1-reviewsubmissionitems
https://developer.apple.com/documentation/appstoreconnectapi/patch-v1-reviewsubmissionitems-_id_
https://developer.apple.com/documentation/appstoreconnectapi/patch-v1-reviewsubmissions-_id_
https://developer.apple.com/documentation/appstoreconnectapi/patch-v1-appstoreversions-_id_-relationships-build
https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/manage-a-submission-with-unresolved-issues/
"""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

APP = '6832546390'
BUNDLE = 'su.linka.pictures'
VERSION = '2.0.0'
BUILD = '2'
ORIGIN = 'https://api.appstoreconnect.apple.com'
NOTES = '''Version 2.0.0 (build 2) updates the picture communication interface:
- The message row now targets 20% of the window height by default.
- Settings > Message row height offers 16%, 20%, 24%, 28%, 32%; changes
  apply after Save.
- The whole card grid is centered in the remaining communication area;
  card images, labels and order are unchanged.
- The iOS application now uses the UIScene lifecycle required by newer SDKs.
To review without an account, choose guest mode. The starter picture sets and
communication features are available offline. Existing review contact details
remain unchanged. No physical-device testing is claimed by this submission.'''


class Blocked(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Blocked(message)


def resource_id(value):
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9-]+', value),
            'Unexpected API resource ID')
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api_url(path):
    url = path if path.startswith('https://') else ORIGIN + path
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' and parsed.netloc == 'api.appstoreconnect.apple.com'
            and parsed.path.startswith('/v1/') and not parsed.fragment,
            'Refusing non-Apple API URL')
    return url


def jwt_token():
    # cryptography handles P-256 key validation and DER decoding, not a homegrown signer.
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, utils
    key_id, issuer = os.environ['ASC_API_KEY_ID'], os.environ['ASC_API_ISSUER_ID']
    require(re.fullmatch(r'[A-Z0-9]+', key_id), 'Invalid key ID')
    require(re.fullmatch(r'[0-9a-fA-F-]{36}', issuer), 'Invalid issuer ID')
    key = serialization.load_pem_private_key(Path(os.environ['ASC_KEY_PATH']).read_bytes(), None)
    require(isinstance(key, ec.EllipticCurvePrivateKey) and isinstance(key.curve, ec.SECP256R1),
            'Require an ES256 P-256 private key')
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=')
    now = int(time.time())
    header = dict(alg='ES256', kid=key_id, typ='JWT')
    payload = dict(iss=issuer, iat=now - 5, exp=now + 600, aud='appstoreconnect-v1')
    unsigned = b'.'.join(encode(json.dumps(v, separators=(',', ':')).encode()) for v in (header, payload))
    r, s = utils.decode_dss_signature(key.sign(unsigned, ec.ECDSA(hashes.SHA256())))
    return (unsigned + b'.' + encode(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))).decode()


class API:
    def __init__(self, output):
        self.opener = urllib.request.build_opener(NoRedirect())
        self.output = output

    def request(self, method, path, body=None):
        url = api_url(path)
        if method != 'GET':
            # Persist intent before transport: a lost response is NOT permission to retry.
            with (self.output / 'mutation-journal.jsonl').open('a') as journal:
                journal.write(json.dumps(dict(method=method, path=path, body=body)) + '\n')
        req = urllib.request.Request(url, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={'Authorization': 'Bearer ' + jwt_token(),
                                              'Content-Type': 'application/json'})
        try:
            with self.opener.open(req, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            # Do not print response bodies, server messages, headers, or credentials.
            raise Blocked(f'Apple API HTTP {error.code}; no mutation retry. Inspect current state before retrying.') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Blocked('Apple API transport failure; mutation outcome may be unknown. Read back in App Store Connect before retrying.') from None

    def get(self, path, **query):
        return self.request('GET', path + ('?' + urllib.parse.urlencode(query) if query else ''))

    def all(self, path, **query):
        page = self.get(path, **query)
        result = list(page['data'])
        for _ in range(100):
            following = page.get('links', {}).get('next')
            if not following:
                return result
            page = self.request('GET', api_url(following))
            result.extend(page['data'])
        raise Blocked('API pagination exceeded safety bound')

    def patch(self, kind, identifier, **attributes):
        identifier = resource_id(identifier)
        return self.request('PATCH', f'/v1/{kind}/{identifier}',
                            dict(data=dict(type=kind, id=identifier, attributes=attributes)))


def related(kind, identifier):
    return dict(data=dict(type=kind, id=resource_id(identifier)))


def wait_build(api, timeout, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while True:
        response = api.get('/v1/builds', **{'filter[app]': APP, 'filter[version]': BUILD,
                                           'include': 'preReleaseVersion,appEncryptionDeclaration', 'limit': 200})
        require(not response.get('links', {}).get('next'), 'Ambiguous build pagination')
        included = {(r['type'], r['id']): r for r in response.get('included', [])}
        matches = []
        for build in response['data']:
            ref = build.get('relationships', {}).get('preReleaseVersion', {}).get('data')
            pre = included.get((ref['type'], ref['id'])) if ref else None
            if pre and pre['attributes']['version'] == VERSION and pre['attributes']['platform'] == 'IOS':
                matches.append(build)
        require(len(matches) <= 1, 'Multiple matching builds; manual inspection required')
        if matches:
            build = matches[0]
            state = build['attributes']['processingState']
            require(state not in ('FAILED', 'INVALID'), 'Build processing failed; no submission performed')
            if state == 'VALID':
                require(not build['attributes'].get('expired'), 'Build is expired')
                encryption = build['attributes'].get('usesNonExemptEncryption')
                require(isinstance(encryption, bool), 'Export compliance unanswered: resolve for build 2 in App Store Connect; no encryption answer is guessed')
                if encryption:
                    ref = build.get('relationships', {}).get('appEncryptionDeclaration', {}).get('data')
                    declaration = included.get((ref['type'], ref['id'])) if ref else None
                    require(declaration and declaration['attributes'].get('appEncryptionDeclarationState') == 'APPROVED',
                            'Export compliance declaration not approved for build 2; resolve in App Store Connect')
                return build
            require(state == 'PROCESSING', 'Unknown build processing state')
        remaining = deadline - clock()
        require(remaining > 0, 'Build not ready within bounded wait; rerun manually after processing finishes')
        sleep(min(15, remaining))


def version_state(version):
    return version['attributes'].get('appVersionState') or version['attributes'].get('appStoreState')


def choose_version(versions):
    ios = [v for v in versions if v['attributes']['platform'] == 'IOS']
    exact = [v for v in ios if v['attributes']['versionString'] == VERSION]
    require(len(exact) == 1, 'Expected exactly one existing iOS 2.0.0 version')
    for version in ios:
        text = version['attributes']['versionString']
        require(re.fullmatch(r'\d+(\.\d+){0,2}', text), 'Unknown version ordering; inspect manually')
        number = tuple(map(int, text.split('.'))) + (0,) * (3 - len(text.split('.')))
        require(number <= (2, 0, 0), 'Newer iOS version exists; refusing stale submission')
        if version['id'] != exact[0]['id']:
            require(version_state(version) in ('REJECTED', 'DEVELOPER_REJECTED', 'READY_FOR_SALE', 'READY_FOR_DISTRIBUTION', 'REPLACED_WITH_NEW_VERSION', 'REMOVED_FROM_SALE', 'DEVELOPER_REMOVED_FROM_SALE'),
                    'Another iOS version has a conflicting state')
    require(version_state(exact[0]) in ('REJECTED', 'PREPARE_FOR_SUBMISSION'),
            'Version is not rejected/editable; inspect existing review before retrying')
    return exact[0]


def choose_submission(api, version_id):
    submissions = api.all('/v1/reviewSubmissions', **{'filter[app]': APP, 'limit': 200})
    active = [s for s in submissions if s['attributes'].get('platform') in ('IOS', None)
              and s['attributes']['state'] != 'COMPLETE']
    require(len(active) <= 1, 'Multiple active submissions; resolve manually')
    if not active:
        return None, None
    submission = active[0]
    require(submission['attributes']['state'] in ('READY_FOR_REVIEW', 'UNRESOLVED_ISSUES'),
            'An active review already exists; refusing duplicate submission')
    items = api.all(f'/v1/reviewSubmissions/{resource_id(submission["id"])}/items', limit=200)
    require(len(items) == 1, 'Existing submission must contain only this app version; no unrelated items will be changed')
    item = items[0]
    require(item.get('relationships', {}).get('appStoreVersion', {}).get('data') == dict(type='appStoreVersions', id=version_id),
            'Existing submission refers to another item/version')
    allowed = ('REJECTED', 'READY_FOR_REVIEW') if submission['attributes']['state'] == 'UNRESOLVED_ISSUES' else ('READY_FOR_REVIEW',)
    require(item['attributes']['state'] in allowed, 'Existing review item is not safely editable')
    return submission, item


def submit(api, output, timeout):
    app = api.get(f'/v1/apps/{APP}')['data']
    require(app['attributes']['bundleId'] == BUNDLE, 'App/bundle mismatch')
    version = choose_version(api.all(f'/v1/apps/{APP}/appStoreVersions', limit=200))
    version_id = resource_id(version['id'])
    submission, item = choose_submission(api, version_id)
    build = wait_build(api, timeout)
    build_id = resource_id(build['id'])
    # Processing may take 20 minutes: recheck mutable review state before writing.
    current = choose_version(api.all(f'/v1/apps/{APP}/appStoreVersions', limit=200))
    require(current['id'] == version_id, 'Version changed during build processing')
    submission, item = choose_submission(api, version_id)
    details = api.get(f'/v1/appStoreVersions/{version_id}/appStoreReviewDetail')['data']
    require(details and details['type'] == 'appStoreReviewDetails', 'Existing review contact details required')
    # All preflight blockers precede the first mutation; preserve contacts and store metadata.
    (output / 'preflight.json').write_text(json.dumps(dict(app=APP, version_id=version_id,
        build_id=build_id, version_state=version_state(version),
        submission_id=submission['id'] if submission else None), indent=2))
    api.request('PATCH', f'/v1/appStoreVersions/{version_id}/relationships/build', related('builds', build_id))
    selected = api.get(f'/v1/appStoreVersions/{version_id}/build')['data']
    require(selected and selected['id'] == build_id, 'Selected build readback mismatch; stop')
    api.patch('appStoreReviewDetails', details['id'], notes=NOTES)
    notes = api.get(f'/v1/appStoreVersions/{version_id}/appStoreReviewDetail')['data']
    require(notes['attributes'].get('notes') == NOTES, 'Review notes readback mismatch; stop')
    require({k: v for k, v in notes['attributes'].items() if k != 'notes'} ==
            {k: v for k, v in details['attributes'].items() if k != 'notes'},
            'Review details changed concurrently; stop without submitting')
    if submission is None:
        submission = api.request('POST', '/v1/reviewSubmissions', dict(data=dict(
            type='reviewSubmissions', attributes=dict(platform='IOS'), relationships=dict(app=related('apps', APP)))))['data']
        submission_id = resource_id(submission['id'])
        api.request('POST', '/v1/reviewSubmissionItems', dict(data=dict(type='reviewSubmissionItems', relationships=dict(
            reviewSubmission=related('reviewSubmissions', submission_id), appStoreVersion=related('appStoreVersions', version_id)))))
    else:
        submission_id = resource_id(submission['id'])
        if item['attributes']['state'] == 'REJECTED':
            api.patch('reviewSubmissionItems', item['id'], resolved=True)
    (output / 'submission-intent.json').write_text(json.dumps(dict(submission_id=submission_id,
        version_id=version_id, build_id=build_id, outcome='NOT_YET_VERIFIED'), indent=2))
    api.patch('reviewSubmissions', submission_id, submitted=True)
    result = api.get(f'/v1/reviewSubmissions/{submission_id}')['data']
    require(result['attributes']['state'] == 'WAITING_FOR_REVIEW',
            'Submission not verified WAITING_FOR_REVIEW; inspect manually, do not retry automatically')
    current_version = api.get(f'/v1/appStoreVersions/{version_id}')['data']
    require(version_state(current_version) == 'WAITING_FOR_REVIEW', 'Version review queue state not verified')
    (output / 'submission-result.json').write_text(json.dumps(dict(app=APP, version=VERSION, build=BUILD,
        submission_id=submission_id, state='WAITING_FOR_REVIEW',
        review_url=f'https://appstoreconnect.apple.com/apps/{APP}/appstore/reviewsubmissions/{submission_id}',
        publicly_available=False), indent=2))
    print('Verified WAITING_FOR_REVIEW; this is not public App Store availability.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--prepare', action='store_true', help='Offline plan only (default)')
    mode.add_argument('--submit', action='store_true', help='Explicit CI-only API mutations')
    parser.add_argument('--output', type=Path, default=Path('build/ios-review'))
    parser.add_argument('--wait-seconds', type=int, choices=range(0, 1201), default=1200, metavar='0..1200')
    args = parser.parse_args()
    if not args.submit:
        print(json.dumps(dict(mode='prepare', network=False, app=APP, bundle=BUNDLE,
                              version=VERSION, build=BUILD, notes=NOTES,
                              actions=['verify successful publish artifact in CI', 'check editable version and review conflicts',
                                       'wait for VALID build and answered export compliance', 'select exact build',
                                       'update notes only', 'resolve existing rejected item or create new submission',
                                       'submit once and read back WAITING_FOR_REVIEW']), indent=2))
        return
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and os.environ.get('IOS_PUBLISH_RECEIPT_VERIFIED') == 'true', 'Submission requires gated manual main CI')
    args.output.mkdir(parents=True, exist_ok=True)
    require(not (args.output / 'mutation-journal.jsonl').exists(), 'Prior mutation journal exists; inspect state before retrying')
    submit(API(args.output), args.output, args.wait_seconds)


if __name__ == '__main__':
    try:
        main()
    except Blocked as error:
        print(f'BLOCKED: {error}', file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('BLOCKED: unexpected failure (details withheld to protect credentials); inspect CI stage and current App Store Connect state before retrying.', file=sys.stderr)
        sys.exit(1)
