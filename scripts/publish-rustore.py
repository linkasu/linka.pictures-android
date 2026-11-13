#!/usr/bin/env python3
"""CI-only RuStore submission of Google-generated 2.0.1 (7), without retries.

Contracts: https://www.rustore.ru/help/en/work-with-rustore-api/api-authorization-token
https://www.rustore.ru/help/en/work-with-rustore-api/api-upload-publication-app/create-draft-version
https://www.rustore.ru/help/en/work-with-rustore-api/api-upload-publication-app/apk-file-upload/file-upload-apk
https://developers.google.com/android-publisher/api-ref/rest/v3/generatedapks/download
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location('play_publish', Path(__file__).with_name('publish-google-play.py'))
play = importlib.util.module_from_spec(spec)
spec.loader.exec_module(play)
PublishError = play.PublishError
PACKAGE = play.PACKAGE
BASE = 'https://public-api-m.rustore.ru/public'
APP = BASE + '/v1/application/' + PACKAGE
DETAIL = BASE + '/v2/application/' + PACKAGE
NEW_CERT = '01e2aa1f93150de38ad38888ca4bcfab56bca9991e3775d53907667802385121'
OLD_CERT = '28ed911ea1b24c0827a079597200319d73b61f5cdb321bf5ef6bf87b72a1ac52'
TEMPLATE_ID = 2064943286
LISTING_FIELDS = ('appName', 'shortDescription', 'fullDescription', 'categories', 'ageLegal',
                  'paid', 'appType', 'privacyExplanation', 'privacyDataTypes', 'developerContacts')


def rustore(path, token='', method='GET', body=None, apk=None):
    if not (path == BASE + '/auth' or path.startswith(APP + '/') or path.startswith(DETAIL + '/')):
        raise PublishError('RuStore URL outside approved application endpoints')
    headers = {'Accept': 'application/json'}
    if token:
        headers['Public-Token'] = token
    data = None
    if apk is not None:
        boundary = 'linka-' + os.urandom(16).hex()
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                'filename="google-universal.apk"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n').encode()
        data += apk.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
    elif body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        req = urllib.request.Request(path, data=data, headers=headers, method=method)
        with urllib.request.build_opener(play.NoRedirect).open(req, timeout=180) as response:
            raw = response.read(play.MAX_JSON + 1)
        if len(raw) > play.MAX_JSON:
            raise PublishError('RuStore response too large; inspect remote state, do not retry')
        result = json.loads(raw)
        if result.get('code') != 'OK':
            raise PublishError('RuStore rejected request; inspect console, do not blindly retry')
        return result['body']
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        raise PublishError(f'RuStore HTTP {code}; inspect console, do not blindly retry') from None
    except (OSError, ValueError, KeyError, TypeError):
        raise PublishError('RuStore result unknown; inspect console, do not retry') from None


def authenticate():
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    key_id = os.environ.get('RUSTORE_API_KEY_ID', '')
    if key_id != '2351035158':
        raise PublishError('Require approved app-scoped RuStore key ID')
    try:
        private = serialization.load_der_private_key(
            base64.b64decode(os.environ.get('RUSTORE_API_PRIVATE_KEY', ''), validate=True), password=None)
        if not isinstance(private, rsa.RSAPrivateKey):
            raise ValueError()
        timestamp = datetime.now(timezone.utc).isoformat(timespec='milliseconds')
        signature = private.sign((key_id + timestamp).encode(), padding.PKCS1v15(), hashes.SHA512())
    except (ValueError, TypeError):
        raise PublishError('Invalid RuStore RSA private key; credential not logged') from None
    body = rustore(BASE + '/auth', method='POST', body={
        'keyId': key_id, 'timestamp': timestamp, 'signature': base64.b64encode(signature).decode()})
    token = body.get('jwe')
    if not isinstance(token, str) or not token:
        raise PublishError('Missing RuStore authorization token')
    return token


def verify_source(args):
    if not re.fullmatch(r'[1-9][0-9]*', args.google_run_id):
        raise PublishError('Explicit positive Google publish run ID required')
    if not re.fullmatch(r'[0-9a-f]{40}', args.source_sha):
        raise PublishError('Exact source SHA required')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        raise PublishError('Invalid repository')
    root = f'https://api.github.com/repos/{args.repository}'
    token = os.environ.get('GH_TOKEN', '')
    run = play.request(root + '/actions/runs/' + args.google_run_id, token)
    if (run.get('path') != '.github/workflows/publish-android.yml'
            or run.get('head_branch') != 'main' or run.get('head_sha') != args.source_sha
            or run.get('event') != 'workflow_dispatch' or run.get('status') != 'completed'
            or run.get('conclusion') != 'success'
            or run.get('repository', {}).get('full_name') != args.repository
            or run.get('head_repository', {}).get('full_name') != args.repository):
        raise PublishError('Require successful Google publication workflow at exact main checkout SHA')
    if play.request(root + '/branches/main', token).get('commit', {}).get('sha') != args.source_sha:
        raise PublishError('Checkout must still be current main')
    artifacts = play.request(root + '/actions/runs/' + args.google_run_id + '/artifacts?per_page=100', token)
    name = 'google-play-publish-evidence-' + args.google_run_id
    found = [item for item in artifacts.get('artifacts', []) if item.get('name') == name]
    if artifacts.get('total_count', 0) > 100 or len(found) != 1 or found[0].get('expired') is not False:
        raise PublishError('Require exactly one unexpired Google publication receipt artifact')
    return {'google_run_id': args.google_run_id, 'source_sha': args.source_sha,
            'google_receipt_artifact_id': found[0]['id']}


def validate_receipt(receipt, source_sha):
    if (receipt.get('status') != 'EDIT_COMMITTED_FOR_REVIEW'
            or receipt.get('outcome') != 'EDIT_COMMITTED_FOR_REVIEW'
            or receipt.get('package') != PACKAGE or receipt.get('version_code') != 7
            or receipt.get('release_version') != '2.0.1' or receipt.get('source_sha') != source_sha):
        raise PublishError('Google receipt must confirm approved version committed for review at exact SHA')


def select_universal(metadata):
    candidates = {}
    for group in metadata.get('generatedApks', []):
        cert = str(group.get('certificateSha256Hash', '')).lower().replace(':', '')
        download_id = group.get('generatedUniversalApk', {}).get('downloadId')
        if download_id and cert in (NEW_CERT, OLD_CERT):
            if cert in candidates or not isinstance(download_id, str):
                raise PublishError('Ambiguous generated universal APK metadata')
            candidates[cert] = download_id
    for cert in (NEW_CERT, OLD_CERT):
        if cert in candidates:
            return candidates[cert], cert
    raise PublishError('No approved Google-signed universal APK ready; STOP, no alternate APK')


def download_apk(args, evidence):
    token = os.environ.get('ACCESS_TOKEN', '')
    base = play.BASE + PACKAGE + '/generatedApks/7'
    generated = play.request(base, token)
    evidence['google_generated_variants'] = [
        dict(certificate_sha256=group.get('certificateSha256Hash'),
             universal_available=bool(group.get('generatedUniversalApk', {}).get('downloadId')),
             standalone=[{k: value for k, value in apk.items() if k != 'downloadId'}
                         for apk in group.get('generatedStandaloneApks', [])])
        for group in generated.get('generatedApks', [])]
    download_id, cert = select_universal(generated)
    url = base + '/downloads/' + urllib.parse.quote(download_id, safe='') + ':download?alt=media'
    try:
        req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token})
        with urllib.request.build_opener(play.NoRedirect).open(req, timeout=180) as response:
            with args.apk.open('wb') as stream:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > play.MAX_FILE:
                        raise PublishError('Downloaded APK exceeds 512 MiB')
                    stream.write(chunk)
        if not size:
            raise PublishError('Empty generated APK')
    except (OSError, ValueError):
        raise PublishError('Google APK download failed; URL and credential not logged') from None
    evidence['apk_sha256'] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    evidence['generated_certificate_sha256'] = cert
    evidence['status'] = 'GOOGLE_APK_DOWNLOADED_NOT_YET_VERIFIED'
    play.save(evidence, args.evidence)
    verify_apk(args, evidence)


def verify_apk(args, evidence=None):
    commands = [
        ([args.aapt, 'dump', 'badging', str(args.apk)], None),
        ([args.apksigner, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '24',
          '--max-sdk-version', '32', str(args.apk)], OLD_CERT),
        ([args.apksigner, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '33',
          str(args.apk)], NEW_CERT),
    ]
    for command, cert in commands:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode:
            raise PublishError('APK verification failed; no upload or re-signing')
        if cert:
            hashes = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$',
                                result.stdout, re.M | re.I)
            actual = [value.lower() for value in hashes]
            if evidence is not None:
                evidence.setdefault('observed_signers', []).append(dict(
                    sdk='24-32' if cert == OLD_CERT else '33+', sha256=actual))
            if actual != [cert]:
                raise PublishError('APK signing lineage does not match approved API24-32/API33+ certificates')
        elif not re.search(r"^package: name='su\.linka\.pictures' versionCode='7' versionName='2\.0\.1'(?: |$)", result.stdout, re.M):
            raise PublishError('APK package/version is not approved 2.0.1 (7)')


def preflight(token):
    page = rustore(APP + '/version?filterTestingType=ALL&page=0&size=100', token)
    versions = page.get('content', [])
    if (page.get('totalPages') != 1 or page.get('totalElements') != len(versions)
            or not versions):
        raise PublishError('Require complete single-page RuStore version history; inspect console')
    safe = {'ACTIVE', 'PREVIOUS_ACTIVE', 'ARCHIVED', 'DELETED_DRAFT'}
    for version in versions:
        if version.get('versionStatus') not in safe or int(version.get('versionCode', 7)) >= 7:
            raise PublishError('Existing draft, review, pending publication or version >=7: STOP; never cancel/overwrite')
    active = [v for v in versions if v.get('versionStatus') == 'ACTIVE' and v.get('testingType') is None]
    if len(active) != 1:
        raise PublishError('Require one active production version to inherit existing listing/privacy')
    detail = rustore(DETAIL + '/version/' + str(int(active[0]['versionId'])), token)
    if (detail.get('appId') != 2063785208 or detail.get('paid') is not False
            or detail.get('ageLegal') != '0+' or detail.get('appType') != 'MAIN'
            or detail.get('categories') != ['tools']):
        raise PublishError('Active app identity/free/age metadata differs; inspect console')
    return active[0]['versionId']


def positive_id(value):
    if not re.fullmatch(r'[1-9][0-9]*', str(value)):
        raise PublishError('Explicit positive RuStore version ID required')
    return int(value)


def version_detail(token, version_id):
    try:
        detail = rustore(DETAIL + '/version/' + str(positive_id(version_id)), token)
    except PublishError as error:
        raise PublishError('BLOCKED: RuStore v2 version detail unavailable; no endpoint fallback: ' + str(error)) from None
    if not isinstance(detail, dict):
        raise PublishError('BLOCKED: invalid RuStore v2 version detail')
    return detail


def version_history(token):
    page = rustore(APP + '/version?filterTestingType=ALL&page=0&size=100', token)
    versions = page.get('content')
    if (not isinstance(versions, list) or not versions or page.get('totalPages') != 1
            or page.get('totalElements') != len(versions)):
        raise PublishError('BLOCKED: require complete single-page RuStore version history')
    ids = [positive_id(version['versionId']) for version in versions]
    if len(ids) != len(set(ids)):
        raise PublishError('BLOCKED: duplicate RuStore version IDs')
    return versions


def field_hash(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def detail_evidence(detail):
    packages = detail.get('packages')
    if not isinstance(packages, list):
        raise PublishError('BLOCKED: missing RuStore package inventory')
    return {'app_id': detail.get('appId'), 'app_name': detail.get('appName'),
            'package_count': len(packages),
            'packages': [{key: item.get(key) for key in ('verCode', 'verName')} for item in packages],
            'field_sha256': {key: field_hash(value) for key, value in sorted(detail.items())}}


def inspect(args, evidence):
    delivery_sha = os.environ.get('GITHUB_SHA', '')
    if not re.fullmatch(r'[0-9a-f]{40}', delivery_sha):
        raise PublishError('BLOCKED: exact CI delivery code GITHUB_SHA required for inspection')
    token = authenticate()
    versions = version_history(token)
    template_id = positive_id(args.template_version_id)
    detail = version_detail(token, template_id)
    evidence.update(template_version_id=template_id, template=detail_evidence(detail),
                    delivery_sha=delivery_sha,
                    versions=[{key: version.get(key) for key in
                               ('versionId', 'versionStatus', 'versionCode', 'testingType')}
                              for version in versions],
                    verified_certificate_sha256={'api24_32': OLD_CERT, 'api33_plus': NEW_CERT},
                    status='RUSTORE_READ_ONLY_INSPECTION')
    play.save(evidence, args.evidence)
    evidence.update(template_listing_sha256=listing_hash(detail),
                    status='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED')


def listing(detail):
    if (detail.get('appId') != 2063785208 or detail.get('paid') is not False
            or detail.get('ageLegal') != '0+' or detail.get('appType') != 'MAIN'
            or detail.get('categories') != ['tools']
            or any(not isinstance(detail.get(key), str) or not detail[key].strip()
                   for key in ('appName', 'shortDescription', 'fullDescription'))):
        raise PublishError('BLOCKED: listing identity/free/age/required text differs or is missing')
    return {key: detail[key] for key in LISTING_FIELDS if key in detail}


def listing_hash(detail):
    return field_hash(listing(detail))


def validate_inspection(args, evidence):
    receipt = json.loads(args.inspection.read_text())
    delivery_sha = os.environ.get('GITHUB_SHA', '')
    status = 'GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED'
    if (not re.fullmatch(r'[0-9a-f]{40}', delivery_sha)
            or receipt.get('status') != status or receipt.get('outcome') != status
            or receipt.get('delivery_sha') != delivery_sha
            or receipt.get('source_sha') != args.source_sha
            or receipt.get('google_run_id') != args.google_run_id
            or receipt.get('package') != PACKAGE or receipt.get('version_code') != 7
            or receipt.get('release_version') != '2.0.1' or receipt.get('app_id') != 2063785208
            or receipt.get('template_version_id') != positive_id(args.template_version_id)
            or not re.fullmatch(r'[0-9a-f]{64}', evidence.get('apk_sha256', ''))
            or receipt.get('apk_sha256') != evidence['apk_sha256']
            or not re.fullmatch(r'[0-9a-f]{64}', receipt.get('template_listing_sha256', ''))):
        raise PublishError('BLOCKED: require successful matching inspection receipt for this code/source/Google run/APK/template')
    return receipt['template_listing_sha256']


def draft_preflight(args, token, inspected_listing_hash):
    draft_id = positive_id(args.draft_id)
    template_id = positive_id(args.template_version_id)
    if draft_id == template_id:
        raise PublishError('BLOCKED: target must be a separate duplicated draft, not the template')
    versions = version_history(token)
    targets = [version for version in versions if positive_id(version['versionId']) == draft_id]
    if len(targets) != 1 or targets[0].get('versionStatus') != 'DRAFT' or targets[0].get('testingType') is not None:
        raise PublishError('BLOCKED: target must be the production DRAFT in complete history')
    safe = {'ACTIVE', 'PREVIOUS_ACTIVE', 'ARCHIVED', 'DELETED_DRAFT'}
    for version in versions:
        if positive_id(version['versionId']) != draft_id:
            if version.get('versionStatus') not in safe or int(version.get('versionCode', 7)) >= 7:
                raise PublishError('BLOCKED: another draft/review/pending/unknown or version >=7 exists')
    template = version_detail(token, template_id)
    if listing_hash(template) != inspected_listing_hash:
        raise PublishError('BLOCKED: template listing changed since verified inspection')
    target = version_detail(token, draft_id)
    if listing(target) != listing(template):
        raise PublishError('BLOCKED: duplicated draft listing/privacy/contacts differ from template')
    if target.get('packages') != []:
        raise PublishError('BLOCKED: target package inventory must be explicitly empty; never overwrite APK')
    if target.get('publishType') != 'INSTANTLY' or target.get('partialValue') not in (100, -1):
        raise PublishError('BLOCKED: set instant 100% publication in console before upload')
    return draft_id, target


def publish_draft(args, evidence):
    inspected_listing_hash = validate_inspection(args, evidence)
    token = authenticate()
    draft_id, before = draft_preflight(args, token, inspected_listing_hash)
    evidence.update(rustore_version_id=draft_id, template_version_id=positive_id(args.template_version_id),
                    template_listing_sha256=inspected_listing_hash, delivery_sha=os.environ['GITHUB_SHA'],
                    draft_before_upload=detail_evidence(before), status='APK_UPLOAD_RESULT_UNKNOWN')
    play.save(evidence, args.evidence)
    version_url = APP + '/version/' + str(draft_id)
    rustore(version_url + '/apk?servicesType=Unknown&isMainApk=true', token, 'POST', apk=args.apk)
    after = version_detail(token, draft_id)
    packages = after.get('packages', [])
    if (len(packages) != 1 or packages[0].get('verCode') != 7 or packages[0].get('verName') != '2.0.1'
            or listing(after) != listing(before) or after.get('publishType') != 'INSTANTLY'
            or after.get('partialValue') not in (100, -1)):
        raise PublishError('BLOCKED: uploaded draft APK/listing/publication settings mismatch; no commit')
    evidence.update(draft_after_upload=detail_evidence(after), status='COMMIT_RESULT_UNKNOWN')
    play.save(evidence, args.evidence)
    rustore(version_url + '/commit?priorityUpdate=0', token, 'POST')
    evidence['status'] = 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION'


def publish(args, evidence):
    token = authenticate()
    evidence['inherited_active_version_id'] = preflight(token)
    evidence['status'] = 'DRAFT_CREATE_RESULT_UNKNOWN'
    play.save(evidence, args.evidence)
    # Missing fields inherit the active listing, privacy, contacts and screenshots unchanged.
    version_id = rustore(APP + '/version', token, 'POST', {
        'publishType': 'INSTANTLY', 'partialValue': 100,
        'whatsNew': 'Высота строки сообщения по умолчанию — 20%. В настройках доступны 16%, 20%, 24%, 28% и 32%.\nСетка карточек центрируется в области общения.\nСуществующие наборы и черновики сохраняются.'})
    if type(version_id) is not int or version_id <= 0:
        raise PublishError('Invalid created version ID; inspect console, do not retry')
    evidence.update(rustore_version_id=version_id, status='APK_UPLOAD_RESULT_UNKNOWN')
    play.save(evidence, args.evidence)
    version_url = APP + '/version/' + str(version_id)
    rustore(version_url + '/apk?servicesType=Unknown&isMainApk=true', token, 'POST', apk=args.apk)
    detail = rustore(DETAIL + '/version/' + str(version_id), token)
    packages = detail.get('packages', [])
    if (len(packages) != 1 or packages[0].get('verCode') != 7
            or packages[0].get('verName') != '2.0.1'
            or detail.get('publishType') != 'INSTANTLY' or detail.get('partialValue') not in (100, -1)):
        raise PublishError('Uploaded draft version/publication settings mismatch; STOP')
    evidence['status'] = 'COMMIT_RESULT_UNKNOWN'
    play.save(evidence, args.evidence)
    rustore(version_url + '/commit?priorityUpdate=0', token, 'POST')
    evidence['status'] = 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--google-run-id', required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--receipt', type=Path, default=Path('google-receipt/publish-evidence.json'))
    parser.add_argument('--inspection', type=Path, default=Path('inspection/rustore-publish-evidence.json'))
    parser.add_argument('--evidence', type=Path, default=Path('rustore-publish-evidence.json'))
    parser.add_argument('--apk', type=Path, default=Path('google-universal.apk'))
    parser.add_argument('--apksigner', default='apksigner')
    parser.add_argument('--aapt', default='aapt')
    parser.add_argument('--draft-id')
    parser.add_argument('--template-version-id', default=str(TEMPLATE_ID))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--verify-source-only', action='store_true')
    mode.add_argument('--prepare', action='store_true')
    mode.add_argument('--publish', action='store_true')
    mode.add_argument('--inspect', action='store_true')
    mode.add_argument('--publish-draft', action='store_true')
    args = parser.parse_args()
    if args.publish_draft and args.draft_id is None:
        parser.error('--publish-draft requires --draft-id')
    evidence = {'status': 'PREPARE_NO_API_CALLS', 'package': PACKAGE,
                'version_code': 7, 'release_version': '2.0.1', 'app_id': 2063785208}
    result = 0
    try:
        if args.publish_draft:
            positive_id(args.draft_id)
            positive_id(args.template_version_id)
        if args.verify_source_only or args.publish or args.inspect or args.publish_draft:
            evidence.update(verify_source(args))
            evidence['status'] = 'SOURCE_VERIFIED'
        if args.publish or args.inspect or args.publish_draft:
            validate_receipt(json.loads(args.receipt.read_text()), args.source_sha)
            download_apk(args, evidence)
            evidence['status'] = 'GOOGLE_APK_VERIFIED'
            play.save(evidence, args.evidence)
            if args.inspect:
                inspect(args, evidence)
            elif args.publish_draft:
                publish_draft(args, evidence)
            else:
                publish(args, evidence)
        elif not args.verify_source_only:
            evidence['planned_actions'] = [
                'Verify successful Google publish run, current main SHA and committed-for-review receipt',
                'Download only Google-generated universal APK 7 and verify package, version and both SDK signing certificates',
                'Authenticate approved app-scoped RuStore key and STOP on existing draft/review/pending version',
                'Inherit active free Tools 0+ listing/privacy, create instant 100% draft, upload once and submit once',
                'Never cancel, delete, archive, re-sign, retry mutations or claim public availability']
        evidence['outcome'] = evidence['status']
    except (PublishError, OSError, ValueError, KeyError, TypeError, AttributeError, ImportError) as error:
        evidence['error'] = str(error) if isinstance(error, PublishError) else 'Invalid local input/API response or missing dependency; inspect state, no blind retry'
        evidence['outcome'] = 'FAILED_OR_UNCERTAIN_DO_NOT_BLINDLY_RERUN'
        result = 1
    finally:
        play.save(evidence, args.evidence)
        print(json.dumps(evidence, ensure_ascii=False))
    return result


if __name__ == '__main__':
    sys.exit(main())
