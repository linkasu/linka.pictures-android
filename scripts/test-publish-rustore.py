#!/usr/bin/env python3
"""Offline safety regressions: no credentials, store calls, or APK downloads."""
import base64
import copy
import importlib.util
import json
import os
from pathlib import Path
import ssl
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

spec = importlib.util.spec_from_file_location('rustore', Path(__file__).with_name('publish-rustore.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class SafetyTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, GITHUB_SHA='d' * 40)
        environment.start()
        self.addCleanup(environment.stop)

    def fixture(self):
        args = SimpleNamespace(draft_id=str(r.REPLACEMENT_ID), template_version_id=str(r.TEMPLATE_ID),
                               apk=Path('synthetic.apk'), source_sha='a' * 40, google_run_id='123')
        page = dict(totalPages=1, totalElements=2, content=[
            dict(versionId=r.TEMPLATE_ID, versionStatus='REJECTED_BY_MODERATOR', versionCode=6, testingType=None),
            dict(versionId=r.REPLACEMENT_ID, versionStatus='DRAFT', versionCode=-1, testingType=None)])
        target = dict(appId=2063785208, appName='Линка', shortDescription='Картинки',
                      fullDescription='Общение', paid=False, ageLegal='0+', appType='MAIN',
                      categories=['tools'], privacyExplanation='synthetic-private',
                      privacyDataTypes=[], developerContacts={'email': 'synthetic-private'},
                      packages=[], publishType='INSTANTLY', partialValue=-1, iconUid=r.MEDIA_PATHS[r.ICON_UID],
                      fileUids=[dict(fileUid=r.MEDIA_PATHS[uid], type='SCREENSHOT', ordinal=index,
                                orientation='LANDSCAPE', deviceType='MOBILE', fileUrl='synthetic-private')
                                for index, uid in enumerate(sorted(r.SCREEN_UIDS))])
        source = copy.deepcopy(target)
        source['packages'] = [dict(verCode=6, verName='2.0.0', apkUid='old-apk')]
        source['fileUids'].append(dict(fileUid='old-apk', type='APK'))
        return args, page, source, target

    def receipt(self, args, source):
        return dict(status='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED',
                    outcome='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED', delivery_sha='d' * 40,
                    source_sha=args.source_sha, google_run_id=args.google_run_id,
                    apk_sha256='f' * 64, package=r.PACKAGE, version_code=8, release_version='2.0.2',
                    app_id=2063785208, template_version_id=r.TEMPLATE_ID,
                    template_listing_sha256=r.listing_hash(source),
                    template_media_sha256=r.field_hash(r.media_snapshot(source)),
                    inspected_draft_id=r.REPLACEMENT_ID)

    def test_rustore_ca_is_pinned_without_global_trust_or_hostname_changes(self):
        before = set(ssl.create_default_context().get_ca_certs(binary_form=True))
        context = r.rustore_ssl_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertEqual(set(ssl.create_default_context().get_ca_certs(binary_form=True)), before)
        self.assertIn('d26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31',
                      {r.hashlib.sha256(cert).hexdigest() for cert in context.get_ca_certs(binary_form=True)})
        with patch.object(Path, 'read_text', return_value='-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----'):
            with self.assertRaises((r.PublishError, ValueError)):
                r.rustore_ssl_context()

    def test_sdk_signer_ranges_and_stamp_rejection(self):
        text = (f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {r.NEW_CERT}\n'
                f'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {r.OLD_CERT}\n'
                'Source Stamp Signer certificate SHA-256 digest: ' + 'f' * 64 + '\n')
        self.assertEqual(r.signer_hashes(text, 24), [r.OLD_CERT])
        self.assertEqual(r.signer_hashes(text, 33), [r.NEW_CERT])
        for changed in (text.replace(r.NEW_CERT, 'f' * 64), text.replace('maxSdkVersion=32', 'maxSdkVersion=33'),
                        text + f'Signer #1 certificate SHA-256 digest: {r.OLD_CERT}\n'):
            with self.assertRaises(r.PublishError):
                r.signer_hashes(changed, 33)
        self.assertEqual(r.signer_hashes('Source Stamp Signer certificate SHA-256 digest: ' + r.NEW_CERT, 33), [])

    def test_receipts_bind_exact_new_version_and_all_inspection_identities_before_auth(self):
        args, _, source, _ = self.fixture()
        receipt = self.receipt(args, source)
        with tempfile.TemporaryDirectory() as directory:
            args.inspection = Path(directory) / 'inspection.json'
            args.inspection.write_text(json.dumps(receipt))
            self.assertEqual(r.validate_inspection(args, {'apk_sha256': 'f' * 64}), receipt)
            changes = [('status', 'FAILED'), ('outcome', 'FAILED'), ('delivery_sha', 'b' * 40),
                       ('source_sha', 'b' * 40), ('google_run_id', '124'), ('apk_sha256', 'e' * 64),
                       ('template_version_id', 42), ('inspected_draft_id', 43),
                       ('template_listing_sha256', ''), ('template_media_sha256', ''),
                       ('package', 'other'), ('version_code', 7), ('release_version', '2.0.1')]
            for key, value in changes:
                args.inspection.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.subTest(key=key), patch.object(r, 'authenticate') as auth:
                    with self.assertRaises(r.PublishError):
                        r.publish_draft(args, {'apk_sha256': 'f' * 64})
                    auth.assert_not_called()

    def test_google_receipt_rejects_old_or_uncertain_release(self):
        receipt = dict(status='EDIT_COMMITTED_FOR_REVIEW', outcome='EDIT_COMMITTED_FOR_REVIEW',
                       package=r.PACKAGE, version_code=8, release_version='2.0.2', source_sha='a' * 40)
        r.validate_receipt(receipt, 'a' * 40)
        for key, value in [('status', 'COMMIT_RESULT_UNKNOWN'), ('outcome', 'FAILED'), ('version_code', 7),
                           ('release_version', '2.0.1'), ('source_sha', 'b' * 40), ('package', 'other')]:
            with self.subTest(key=key), self.assertRaises(r.PublishError):
                r.validate_receipt(dict(receipt, **{key: value}), 'a' * 40)

    def test_uploaded_draft_can_hide_packages_but_must_have_code8_in_history(self):
        args, page, source, target = self.fixture()
        page['content'][1]['versionCode'] = 8
        receipt = self.receipt(args, source)
        with patch.object(r, 'rustore', side_effect=[page, source, target]):
            self.assertEqual(r.draft_preflight(args, 'token', receipt, uploaded=True)[0], r.REPLACEMENT_ID)
        for case in ('wrong_code', 'missing_packages', 'wrong_apk', 'metadata'):
            changed_page, changed_target = copy.deepcopy(page), copy.deepcopy(target)
            if case == 'wrong_code':
                changed_page['content'][1]['versionCode'] = 7
            elif case == 'missing_packages':
                del changed_target['packages']
            elif case == 'wrong_apk':
                changed_target['packages'] = [dict(verCode=7, verName='2.0.1')]
            else:
                changed_target['privacyExplanation'] = 'changed'
            with self.subTest(case=case), patch.object(r, 'rustore',
                    side_effect=[changed_page, source, changed_target]), self.assertRaises(r.PublishError):
                r.draft_preflight(args, 'token', receipt, uploaded=True)

    def test_pending_hidden_upload_never_counts_as_empty_draft_for_reupload(self):
        args, page, source, target = self.fixture()
        page['content'][1]['versionCode'] = 8
        with patch.object(r, 'rustore', side_effect=[page, source, target]), self.assertRaises(r.PublishError):
            r.draft_preflight(args, 'token', self.receipt(args, source))

    def recovery_fixture(self, directory):
        args, page, source, target = self.fixture()
        args.source_sha, args.google_run_id = r.RECOVERY_SOURCE, r.RECOVERY_GOOGLE_RUN
        args.recovery, args.publish_draft = True, False
        args.failed_upload = Path(directory) / 'failed.json'
        args.inspection = Path(directory) / 'inspection.json'
        args.evidence = Path(directory) / 'evidence.json'
        prior = dict(self.receipt(args, source), status='APK_UPLOAD_RESULT_UNKNOWN',
                     outcome='FAILED_OR_UNCERTAIN_DO_NOT_BLINDLY_RERUN',
                     error='BLOCKED: uploaded draft APK/listing/publication settings mismatch; no commit',
                     delivery_sha=r.RECOVERY_SOURCE, rustore_version_id=r.REPLACEMENT_ID,
                     apk_sha256=r.RECOVERY_APK, draft_before_upload=dict(package_count=0, packages=[]))
        args.failed_upload.write_text(json.dumps(prior))
        page['content'][1]['versionCode'] = 8
        return args, page, source, target, prior

    def test_recovery_inspect_is_read_only_then_same_receipt_allows_only_one_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            args, page, source, target, _ = self.recovery_fixture(directory)
            evidence = dict(apk_sha256=r.RECOVERY_APK, source_sha=args.source_sha,
                            google_run_id=args.google_run_id, package=r.PACKAGE, version_code=8,
                            release_version='2.0.2', app_id=2063785208)
            with patch.object(r, 'authenticate', return_value='token'), \
                    patch.object(r, 'rustore', side_effect=[page, source, target]) as api:
                r.recover_existing_upload(args, evidence)
            self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))
            evidence['outcome'] = evidence['status']
            args.inspection.write_text(json.dumps(evidence))
            args.publish_draft = True
            committed = dict(target, packages=[dict(verCode=8, verName='2.0.2')],
                             versionStatus='TAKEN_FOR_MODERATION')
            for failure in (None, 'commit', 'readback'):
                def respond(url, token, method='GET', **kwargs):
                    if method == 'POST':
                        self.assertEqual(url, r.APP + '/version/' + str(r.REPLACEMENT_ID) + '/commit?priorityUpdate=0')
                        self.assertEqual(json.loads(args.evidence.read_text())['status'], 'COMMIT_RESULT_UNKNOWN')
                        if failure == 'commit':
                            raise r.PublishError('Unknown commit')
                    response = next(responses)
                    if isinstance(response, Exception):
                        raise response
                    return response
                responses = iter([page, source, target, None,
                                  r.PublishError('Unknown readback') if failure == 'readback' else committed])
                current = dict(apk_sha256=r.RECOVERY_APK)
                with self.subTest(failure=failure), patch.object(r, 'authenticate', return_value='token'), \
                        patch.object(r, 'rustore', side_effect=respond) as api:
                    if failure:
                        with self.assertRaises(r.PublishError):
                            r.recover_existing_upload(args, current)
                        self.assertEqual(current['status'], 'COMMIT_RESULT_UNKNOWN')
                    else:
                        r.recover_existing_upload(args, current)
                        self.assertEqual(current['status'], 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION')
                methods = [call.args[2] if len(call.args) > 2 else 'GET' for call in api.call_args_list]
                self.assertEqual(methods.count('POST'), 1)
                self.assertNotIn('synthetic-private', args.evidence.read_text())

    def test_recovery_prior_receipt_and_inspection_mismatches_block_before_auth(self):
        for case in ('unknown_upload', 'commit_started', 'wrong_apk', 'wrong_source', 'wrong_draft',
                     'wrong_google', 'no_empty_before', 'inspection_other_delivery', 'inspection_other_upload'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                args, _, source, _, prior = self.recovery_fixture(directory)
                args.publish_draft = True
                receipt = dict(self.receipt(args, source), status='EXISTING_UPLOAD_RECONCILED',
                               outcome='EXISTING_UPLOAD_RECONCILED', apk_sha256=r.RECOVERY_APK,
                               recovery_upload_run_id=r.RECOVERY_UPLOAD_RUN)
                if case == 'unknown_upload':
                    prior['error'] = 'Unknown upload'
                elif case == 'commit_started':
                    prior['status'] = 'COMMIT_RESULT_UNKNOWN'
                elif case == 'wrong_apk':
                    prior['apk_sha256'] = '0' * 64
                elif case == 'wrong_source':
                    prior['source_sha'] = 'b' * 40
                elif case == 'wrong_draft':
                    prior['rustore_version_id'] = 42
                elif case == 'wrong_google':
                    prior['google_run_id'] = '42'
                elif case == 'no_empty_before':
                    prior['draft_before_upload']['package_count'] = 1
                elif case == 'inspection_other_delivery':
                    receipt['delivery_sha'] = 'b' * 40
                else:
                    receipt['recovery_upload_run_id'] = '42'
                args.failed_upload.write_text(json.dumps(prior))
                args.inspection.write_text(json.dumps(receipt))
                with patch.object(r, 'authenticate') as auth, self.assertRaises(r.PublishError):
                    r.recover_existing_upload(args, {'apk_sha256': r.RECOVERY_APK})
                auth.assert_not_called()

    def test_recovery_source_requires_original_upload_and_delivery_only_diff(self):
        args = SimpleNamespace(google_run_id=r.RECOVERY_GOOGLE_RUN, source_sha=r.RECOVERY_SOURCE,
                               repository='linkasu/linka.pictures-android', recovery=True)
        google = dict(path='.github/workflows/publish-android.yml', head_branch='main',
                      head_sha=r.RECOVERY_SOURCE, event='workflow_dispatch', status='completed', conclusion='success',
                      repository={'full_name': args.repository}, head_repository={'full_name': args.repository})
        upload = dict(google, path='.github/workflows/publish-rustore.yml', conclusion='failure', run_attempt=1)
        compare = dict(status='ahead', files=[dict(filename='scripts/publish-rustore.py', status='modified')])
        artifact = lambda name: dict(total_count=1, artifacts=[dict(name=name, id=42, expired=False)])
        for case in (None, 'app_changed', 'upload_pending', 'repeated_upload', 'rerun', 'wrong_main'):
            comparison, run = copy.deepcopy(compare), copy.deepcopy(upload)
            if case == 'app_changed':
                comparison['files'].append(dict(filename='app/build.gradle.kts', status='modified'))
            elif case == 'upload_pending':
                run['status'] = 'in_progress'
            elif case == 'repeated_upload':
                run['run_attempt'] = 2
            responses = [google, {'commit': {'sha': 'b' * 40 if case == 'wrong_main' else 'd' * 40}},
                         comparison, run, artifact('rustore-publish-evidence-' + r.RECOVERY_UPLOAD_RUN),
                         artifact('google-play-publish-evidence-' + r.RECOVERY_GOOGLE_RUN)]
            with self.subTest(case=case), patch.dict(os.environ, GITHUB_RUN_ATTEMPT='2' if case == 'rerun' else '1'), \
                    patch.object(r.play, 'request', side_effect=responses):
                if case:
                    with self.assertRaises(r.PublishError):
                        r.verify_source(args)
                else:
                    self.assertEqual(r.verify_source(args)['source_sha'], r.RECOVERY_SOURCE)

    def test_inspect_checks_clone_with_only_reads_and_sanitizes_evidence(self):
        args, page, source, target = self.fixture()
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(r, 'authenticate', return_value='synthetic-token'), \
                patch.object(r, 'rustore', side_effect=[page, source, page, source, target]) as api:
            args.evidence = Path(directory) / 'evidence.json'
            evidence = {}
            r.inspect(args, evidence)
            self.assertNotIn('synthetic-private', args.evidence.read_text())
        self.assertEqual(evidence['inspected_draft_id'], r.REPLACEMENT_ID)
        self.assertEqual(evidence['draft']['package_count'], 0)
        self.assertNotIn('synthetic-private', json.dumps(evidence))
        self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_preflight_blockers_never_mutate(self):
        for case in ('wrong_target', 'wrong_source', 'source_pending', 'source_security_rejection',
                     'source_code', 'source_testing', 'source_apk', 'extra_version', 'existing_target_apk',
                     'target_pending', 'target_testing', 'wrong_app', 'paid', 'metadata', 'missing_privacy',
                     'missing_name', 'media', 'missing_media', 'settings', 'incomplete', 'duplicate_history',
                     'changed_since_inspection'):
            args, page, source, target = self.fixture()
            receipt = self.receipt(args, source)
            if case == 'wrong_target':
                args.draft_id = '43'
            elif case == 'wrong_source':
                args.template_version_id = '42'
            elif case == 'source_pending':
                page['content'][0]['versionStatus'] = 'MODERATION'
            elif case == 'source_security_rejection':
                page['content'][0]['versionStatus'] = 'REJECTED_BY_SECURITY'
            elif case == 'source_code':
                page['content'][0]['versionCode'] = 8
            elif case == 'source_testing':
                page['content'][0]['testingType'] = 'alpha'
            elif case == 'source_apk':
                source['packages'][0]['verCode'] = 8
            elif case == 'extra_version':
                page['content'].append(dict(versionId=99, versionCode=5, versionStatus='ACTIVE'))
                page['totalElements'] = 3
            elif case == 'existing_target_apk':
                target['packages'] = [dict(verCode=7)]
            elif case == 'target_pending':
                page['content'][1]['versionStatus'] = 'TAKEN_FOR_MODERATION'
            elif case == 'target_testing':
                page['content'][1]['testingType'] = 'alpha'
            elif case == 'wrong_app':
                target['appId'] = 1
            elif case == 'paid':
                target['paid'] = True
            elif case == 'metadata':
                target['fullDescription'] = 'Changed'
            elif case == 'missing_privacy':
                del target['privacyExplanation']
            elif case == 'missing_name':
                target['appName'] = ' '
            elif case == 'media':
                target['fileUids'][0]['ordinal'] = 99
            elif case == 'missing_media':
                target['fileUids'].pop()
            elif case == 'settings':
                target['partialValue'] = 50
            elif case == 'incomplete':
                page['totalPages'] = 2
            elif case == 'duplicate_history':
                page['content'][1]['versionId'] = r.TEMPLATE_ID
            elif case == 'changed_since_inspection':
                source['fullDescription'] = 'Changed'
            with self.subTest(case=case), patch.object(r, 'authenticate', return_value='synthetic-token'), \
                    patch.object(r, 'validate_inspection', return_value=receipt), \
                    patch.object(r, 'rustore', side_effect=[page, source, target]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish_draft(args, {})
                self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_upload_commit_once_with_intent_journals_and_no_delete_create_or_edit(self):
        for failure in (None, 'hidden_packages', 'upload', 'uploaded_listing', 'uploaded_media', 'uploaded_version',
                        'commit', 'readback', 'not_committed', 'committed_version', 'committed_listing'):
            args, page, source, target = self.fixture()
            after = dict(copy.deepcopy(target), packages=[dict(verCode=8, verName='2.0.2')])
            uploaded_page = copy.deepcopy(page)
            uploaded_page['content'][1]['versionCode'] = 8
            committed = dict(copy.deepcopy(after), versionStatus='TAKEN_FOR_MODERATION')
            if failure == 'hidden_packages':
                after['packages'] = []
            elif failure == 'uploaded_listing':
                after['developerContacts'] = {'email': 'changed'}
            elif failure == 'uploaded_media':
                after['fileUids'][0]['orientation'] = 'PORTRAIT'
            elif failure == 'uploaded_version':
                after['packages'][0]['verCode'] = 7
            elif failure == 'not_committed':
                committed['versionStatus'] = 'DRAFT'
            elif failure == 'committed_version':
                committed['packages'][0]['verCode'] = 7
            elif failure == 'committed_listing':
                committed['fullDescription'] = 'changed'
            responses = iter([page, source, target,
                              r.PublishError('Unknown upload') if failure == 'upload' else None,
                              uploaded_page, source, after,
                              r.PublishError('Unknown commit') if failure == 'commit' else None,
                              r.PublishError('Unknown readback') if failure == 'readback' else committed])
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, \
                    patch.object(r, 'authenticate', return_value='synthetic-token'):
                args.evidence = Path(directory) / 'evidence.json'
                args.inspection = Path(directory) / 'inspection.json'
                args.inspection.write_text(json.dumps(self.receipt(args, source)))
                evidence = {'apk_sha256': 'f' * 64}
                def respond(url, token, method='GET', **kwargs):
                    if method != 'GET':
                        phase = json.loads(args.evidence.read_text())['status']
                        self.assertEqual(phase, 'APK_UPLOAD_RESULT_UNKNOWN' if '/apk?' in url else 'COMMIT_RESULT_UNKNOWN')
                    response = next(responses)
                    if isinstance(response, Exception):
                        raise response
                    return response
                with patch.object(r, 'rustore', side_effect=respond) as api:
                    if failure and failure != 'hidden_packages':
                        with self.assertRaises(r.PublishError):
                            r.publish_draft(args, evidence)
                    else:
                        r.publish_draft(args, evidence)
                        self.assertEqual(evidence['rustore_version_status'], 'TAKEN_FOR_MODERATION')
                        self.assertEqual(evidence['status'], 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION')
                methods = [call.args[2] if len(call.args) > 2 else 'GET' for call in api.call_args_list]
                self.assertEqual(methods, ['GET', 'GET', 'GET', 'POST', 'GET', 'GET', 'GET', 'POST', 'GET'][:len(methods)])
                self.assertEqual(methods.count('POST'), 1 if failure in
                                 ('upload', 'uploaded_listing', 'uploaded_media', 'uploaded_version') else 2)
                self.assertNotIn('synthetic-private', args.evidence.read_text())

    def test_media_ignores_binary_and_signed_urls_but_preserves_exact_paths_and_order(self):
        _, _, source, target = self.fixture()
        self.assertEqual(r.media_snapshot(source), r.media_snapshot(target))
        changed = copy.deepcopy(source)
        changed['fileUids'].reverse()
        for item in changed['fileUids']:
            item['fileUrl'] = 'different-private'
            item['blurhash'] = 'different'
        self.assertEqual(r.media_snapshot(changed), r.media_snapshot(target))
        for case in ('icon', 'unknown_path', 'duplicate'):
            changed = copy.deepcopy(target)
            if case == 'icon':
                changed['iconUid'] = 'other/' + r.ICON_UID + '.png'
            elif case == 'unknown_path':
                changed['fileUids'][0]['fileUid'] = 'other/' + sorted(r.SCREEN_UIDS)[0] + '.png'
            else:
                changed['fileUids'].append(changed['fileUids'][0])
            with self.subTest(case=case), self.assertRaises(r.PublishError):
                r.media_snapshot(changed)

    def test_optional_null_matches_absence_but_missing_privacy_does_not(self):
        _, _, source, target = self.fixture()
        source['faq'] = None
        self.assertEqual(r.listing_hash(source), r.listing_hash(target))
        del target['privacyExplanation']
        self.assertNotEqual(r.listing_hash(source), r.listing_hash(target))

    def test_transport_blocks_every_destructive_write_or_wrong_target_before_network(self):
        for url, method in ((r.APP + '/version', 'POST'), (r.APP + '/version/' + str(r.TEMPLATE_ID), 'DELETE'),
                            (r.DETAIL + '/version/' + str(r.REPLACEMENT_ID), 'PATCH'),
                            (r.APP + '/version/43/apk?servicesType=Unknown&isMainApk=true', 'POST'),
                            ('https://example.invalid/public/auth', 'POST'),
                            (r.BASE + '/v1/application/other/version', 'GET')):
            with self.subTest(url=url, method=method), patch.object(r.urllib.request, 'build_opener') as opener:
                with self.assertRaises(r.PublishError):
                    r.rustore(url, 'synthetic-token', method)
                opener.assert_not_called()

    def test_successful_commit_without_body_is_valid(self):
        with patch.object(r.urllib.request, 'build_opener') as opener:
            response = MagicMock()
            response.read.return_value = b'{"code":"OK"}'
            opener.return_value.open.return_value.__enter__.return_value = response
            self.assertIsNone(r.rustore(r.APP + '/version/' + str(r.REPLACEMENT_ID) +
                                       '/commit?priorityUpdate=0', 'synthetic-token', 'POST'))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_http_failure_does_not_leak_or_retry(self):
        error = urllib.error.HTTPError('https://example.invalid/?secret=synthetic-secret', 403,
                                      'synthetic-secret', {}, None)
        with patch.object(r.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(r.PublishError) as caught:
                r.rustore(r.BASE + '/auth', 'synthetic-secret', 'POST', {})
            self.assertNotIn('synthetic-secret', str(caught.exception))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_auth_uses_approved_rsa_key_and_missing_body_blocks(self):
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private = base64.b64encode(key.private_bytes(serialization.Encoding.DER,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption())).decode()
        def auth(url, **kwargs):
            body = kwargs['body']
            self.assertEqual(body['keyId'], '2351035158')
            key.public_key().verify(base64.b64decode(body['signature']),
                (body['keyId'] + body['timestamp']).encode(), padding.PKCS1v15(), hashes.SHA512())
            return {'jwe': 'synthetic-jwe'}
        with patch.dict(os.environ, RUSTORE_API_KEY_ID='2351035158', RUSTORE_API_PRIVATE_KEY=private):
            with patch.object(r, 'rustore', side_effect=auth):
                self.assertEqual(r.authenticate(), 'synthetic-jwe')
            for body in (None, {}, [], {'jwe': ''}):
                with patch.object(r, 'rustore', return_value=body), self.assertRaises(r.PublishError):
                    r.authenticate()

    def test_apk_requires_exact_new_identity_both_signers_and_artifact_icon_check(self):
        args = SimpleNamespace(aapt='aapt', apksigner='apksigner', apk=Path('synthetic.apk'))
        result = lambda text, status=0: SimpleNamespace(returncode=status, stdout=text)
        outputs = [result("package: name='su.linka.pictures' versionCode='8' versionName='2.0.2'"),
                   result('Signer #1 certificate SHA-256 digest: ' + r.OLD_CERT),
                   result('Signer #1 certificate SHA-256 digest: ' + r.NEW_CERT), result('icon PASS')]
        with patch.object(r.subprocess, 'run', side_effect=outputs) as run:
            r.verify_apk(args)
            self.assertEqual(run.call_count, 4)
            self.assertIn('--apk', run.call_args.args[0])
            self.assertTrue(any(str(value).endswith('check-android-launcher.py') for value in run.call_args.args[0]))
        for case in ('old_version', 'wrong_signer', 'wrong_icon'):
            changed = list(outputs)
            if case == 'old_version':
                changed[0] = result("package: name='su.linka.pictures' versionCode='7' versionName='2.0.1'")
            elif case == 'wrong_signer':
                changed[1] = outputs[2]
            else:
                changed[-1] = result('icon FAIL', 1)
            with self.subTest(case=case), patch.object(r.subprocess, 'run', side_effect=changed):
                with self.assertRaises(r.PublishError):
                    r.verify_apk(args)

    def test_only_approved_universal_apk_without_fallback(self):
        group = lambda cert: dict(certificateSha256Hash=cert, generatedUniversalApk=dict(downloadId=cert))
        self.assertEqual(r.select_universal({'generatedApks': [group(r.OLD_CERT), group(r.NEW_CERT)]}),
                         (r.NEW_CERT, r.NEW_CERT))
        for groups in ([], [dict(certificateSha256Hash=r.NEW_CERT, generatedStandaloneApks=[{}])],
                       [group('f' * 64)], [group(r.NEW_CERT), group(r.NEW_CERT)]):
            with self.assertRaises(r.PublishError):
                r.select_universal({'generatedApks': groups})

    def test_source_rejects_other_workflow_before_other_calls(self):
        args = SimpleNamespace(google_run_id='123', source_sha='a' * 40, repository='owner/repo')
        with patch.object(r.play, 'request', return_value={'path': '.github/workflows/android.yml'}) as api:
            with self.assertRaises(r.PublishError):
                r.verify_source(args)
            api.assert_called_once()

    def test_v2_failure_has_no_endpoint_fallback(self):
        with patch.object(r, 'rustore', side_effect=r.PublishError('RuStore HTTP 403')) as api:
            with self.assertRaises(r.PublishError):
                r.version_detail('synthetic-token', r.TEMPLATE_ID)
            api.assert_called_once()

    def test_cli_preserves_source_receipt_apk_order_and_prepare_is_offline(self):
        for mode, recovery in (('--inspect', False), ('--publish-draft', False), ('--prepare', False),
                               ('--inspect', True), ('--publish-draft', True)):
            with self.subTest(mode=mode, recovery=recovery), tempfile.TemporaryDirectory() as directory:
                receipt = Path(directory) / 'receipt.json'
                receipt.write_text('{}')
                evidence = Path(directory) / 'evidence.json'
                argv = ['publish-rustore.py', mode, '--draft-id', str(r.REPLACEMENT_ID),
                        '--google-run-id', '123', '--source-sha', 'a' * 40, '--repository', 'owner/repo',
                         '--receipt', str(receipt), '--evidence', str(evidence)]
                if recovery:
                    argv.append('--recovery')
                order = []
                def mark(name):
                    return lambda *args: order.append(name) or {}
                with patch.object(r.sys, 'argv', argv), patch('builtins.print'), \
                        patch.object(r, 'verify_source', side_effect=mark('source')), \
                        patch.object(r, 'validate_receipt', side_effect=mark('receipt')), \
                        patch.object(r, 'download_apk', side_effect=mark('apk')), \
                        patch.object(r, 'inspect', side_effect=mark('inspect')), \
                        patch.object(r, 'publish_draft', side_effect=mark('draft')), \
                        patch.object(r, 'recover_existing_upload', side_effect=mark('recovery')):
                    self.assertEqual(r.main(), 0)
                self.assertEqual(order, [] if mode == '--prepare' else
                                 ['source', 'receipt', 'apk', 'recovery' if recovery else
                                  'inspect' if mode == '--inspect' else 'draft'])

    def test_workflow_recovery_modes_preserve_provenance_receipts_and_single_writer(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/publish-rustore.yml').read_text()
        for required in ('options: [inspect, publish-draft, recover-inspect, recover-commit]',
                         "github.ref == 'refs/heads/main'", 'cancel-in-progress: false',
                         'group: publish-su.linka.pictures-rustore',
                         "inputs.mode == 'publish-draft' || inputs.mode == 'recover-commit'",
                         'args=(--recovery --inspect --draft-id "$DRAFT_ID")',
                         'args=(--recovery --publish-draft --draft-id "$DRAFT_ID")',
                         'run-id: \'44899101916\'', 'name: rustore-publish-evidence-44899101916',
                         "run['head_sha'] == os.environ['GITHUB_SHA']"):
            self.assertIn(required, workflow)
        source = Path(__file__).with_name('publish-rustore.py').read_text()
        recovery = source.split('def recover_existing_upload(', 1)[1].split('\ndef commit_draft(', 1)[0]
        self.assertNotIn('publish_draft(', recovery)
        self.assertNotIn('apk=args.apk', recovery)
        self.assertEqual(workflow.count('google-github-actions/auth@'), 1)

    def test_old_mutating_cli_modes_are_removed_before_any_service(self):
        for mode in ('--replace-template', '--prepare-clone', '--publish'):
            argv = ['publish-rustore.py', mode, '--google-run-id', '123',
                    '--source-sha', 'a' * 40, '--repository', 'owner/repo']
            with self.subTest(mode=mode), patch.object(r.sys, 'argv', argv), \
                    patch.object(r.sys, 'stderr'), patch.object(r, 'authenticate') as auth:
                with self.assertRaises(SystemExit):
                    r.main()
                auth.assert_not_called()


if __name__ == '__main__':
    unittest.main()
