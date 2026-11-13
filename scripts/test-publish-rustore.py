#!/usr/bin/env python3
"""Offline checks only: no store, GitHub, credentials or APK downloads."""
import base64
import copy
import importlib.util
import json
import os
import ssl
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('rustore', Path(__file__).with_name('publish-rustore.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class SafetyTests(unittest.TestCase):
    def test_rustore_ca_is_pinned_and_does_not_disable_tls_or_hostname_checks(self):
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

    def test_sdk_range_signers_match_real_apksigner31_output_without_accepting_stamp(self):
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

    def setUp(self):
        environment = patch.dict(os.environ, GITHUB_SHA='d' * 40)
        environment.start()
        self.addCleanup(environment.stop)

    def draft_fixture(self):
        args = SimpleNamespace(draft_id='43', template_version_id=str(r.TEMPLATE_ID),
                               apk=Path('synthetic.apk'), source_sha='a' * 40, google_run_id='123')
        versions = [dict(versionId=r.TEMPLATE_ID, versionStatus='DELETED_DRAFT', versionCode=6),
                    dict(versionId=43, versionStatus='DRAFT', versionCode=0, testingType=None)]
        page = dict(content=versions, totalPages=1, totalElements=2)
        detail = dict(appId=2063785208, appName='Линка', shortDescription='Картинки',
                      fullDescription='Общение', paid=False, ageLegal='0+', appType='MAIN',
                      categories=['tools'], privacyExplanation='synthetic-private',
                      privacyDataTypes=[], developerContacts={'email': 'synthetic-private'},
                       packages=[], publishType='INSTANTLY', partialValue=100)
        detail.update(iconUid=r.ICON_UID, fileUids=[dict(fileUid=uid, type='SCREENSHOT', ordinal=index,
                      orientation='PORTRAIT', deviceType='PHONE', fileUrl='synthetic-private')
                      for index, uid in enumerate(sorted(r.SCREEN_UIDS))])
        return args, page, copy.deepcopy(detail), detail

    def inspection_receipt(self, args, template):
        return dict(status='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED',
                    outcome='GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED', delivery_sha='d' * 40,
                    source_sha=args.source_sha, google_run_id=args.google_run_id,
                    apk_sha256='f' * 64, package=r.PACKAGE, version_code=7, release_version='2.0.1',
                    app_id=2063785208, template_version_id=r.TEMPLATE_ID,
                    template_listing_sha256=r.listing_hash(template),
                    template_media_sha256=r.field_hash(r.media_snapshot(template)),
                    inspected_draft_id=r.positive_id(args.draft_id))

    def test_inspection_receipt_must_match_every_delivery_identity_before_auth(self):
        args, _, template, _ = self.draft_fixture()
        receipt = self.inspection_receipt(args, template)
        with tempfile.TemporaryDirectory() as directory:
            args.inspection = Path(directory) / 'inspection.json'
            evidence = {'apk_sha256': 'f' * 64}
            args.inspection.write_text(json.dumps(receipt))
            self.assertEqual(r.validate_inspection(args, evidence), receipt)
            for key, value in [('status', 'FAILED'), ('outcome', 'FAILED'), ('delivery_sha', 'b' * 40),
                               ('source_sha', 'b' * 40), ('google_run_id', '124'), ('apk_sha256', 'e' * 64),
                                ('template_version_id', 42), ('template_listing_sha256', ''),
                                ('template_media_sha256', ''),
                               ('package', 'other'), ('version_code', 6)]:
                args.inspection.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.subTest(key=key), patch.object(r, 'authenticate') as auth:
                    with self.assertRaises(r.PublishError):
                        r.publish_draft(args, evidence)
                    auth.assert_not_called()
            args.inspection.unlink()
            with patch.object(r, 'authenticate') as auth:
                with self.assertRaises(OSError):
                    r.publish_draft(args, evidence)
                auth.assert_not_called()

    def test_inspect_gets_history_and_v2_detail_without_mutations_or_private_values(self):
        args, page, template, _ = self.draft_fixture()
        args.draft_id = None
        evidence = {'apk_sha256': 'a' * 64}
        template.update(jwe='synthetic-private', moderationInfo='synthetic-private')
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(r, 'authenticate', return_value='synthetic-token'), \
                patch.object(r, 'rustore', side_effect=[page, template]) as api:
            args.evidence = Path(directory) / 'evidence.json'
            r.inspect(args, evidence)
            self.assertNotIn('synthetic-private', args.evidence.read_text())
        self.assertEqual(api.call_count, 2)
        self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))
        self.assertEqual(api.call_args.args[0], r.DETAIL + '/version/' + str(r.TEMPLATE_ID))
        self.assertEqual(evidence['status'], 'GOOGLE_APK_VERIFIED_AND_RUSTORE_INSPECTED')
        self.assertEqual(evidence['template']['app_name'], 'Линка')
        self.assertEqual(evidence['verified_certificate_sha256']['api24_32'], r.OLD_CERT)
        self.assertNotIn('synthetic-private', json.dumps(evidence))
        self.assertIn('developerContacts', evidence['template']['field_sha256'])

    def test_inspect_verifies_authorized_clone_without_mutating(self):
        args, page, source, target = self.replacement_fixture()
        evidence = {}
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(r, 'authenticate', return_value='synthetic-token'), \
                patch.object(r, 'rustore', side_effect=[page, source, page, source, target]) as api:
            args.evidence = Path(directory) / 'evidence.json'
            r.inspect(args, evidence)
        self.assertEqual(evidence['inspected_draft_id'], r.REPLACEMENT_ID)
        self.assertEqual(evidence['draft']['package_count'], 0)
        self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_draft_blockers_prevent_every_mutation(self):
        cases = ['wrong_app', 'wrong_state', 'pending', 'other_draft', 'existing_apk',
                 'metadata', 'missing_privacy', 'missing_name', 'settings', 'same_id', 'incomplete',
                 'template_changed_since_inspect']
        for case in cases:
            args, page, template, target = self.draft_fixture()
            if case == 'wrong_app':
                target['appId'] = 1
            elif case == 'wrong_state':
                page['content'][1]['versionStatus'] = 'MODERATION'
            elif case in ('pending', 'other_draft'):
                page['content'][0]['versionStatus'] = 'MODERATION' if case == 'pending' else 'DRAFT'
            elif case == 'existing_apk':
                target['packages'] = [{'verCode': 6}]
            elif case == 'metadata':
                target['fullDescription'] = 'Changed'
            elif case == 'missing_privacy':
                del target['privacyExplanation']
            elif case == 'missing_name':
                target['appName'] = ' '
            elif case == 'settings':
                target['partialValue'] = 50
            elif case == 'same_id':
                args.draft_id = args.template_version_id
            elif case == 'incomplete':
                page['totalPages'] = 2
            elif case == 'template_changed_since_inspect':
                template['fullDescription'] = 'Changed after inspect'
            with self.subTest(case=case), patch.object(r, 'authenticate', return_value='synthetic-token'), \
                    patch.object(r, 'validate_inspection', return_value=self.inspection_receipt(args, self.draft_fixture()[2])), \
                    patch.object(r, 'rustore', side_effect=[page, template, target]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish_draft(args, {})
                self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_targeted_draft_upload_and_commit_once_with_unknown_journals(self):
        for failure in (None, 'upload', 'commit', 'metadata_after'):
            args, page, template, target = self.draft_fixture()
            after = dict(target, packages=[dict(verCode=7, verName='2.0.1')])
            if failure == 'metadata_after':
                after['developerContacts'] = {'email': 'changed'}
            responses = [page, template, target,
                         r.PublishError('Unknown upload') if failure == 'upload' else {}, after,
                         r.PublishError('Unknown commit') if failure == 'commit' else {},
                         dict(after, versionStatus='MODERATION')]
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, \
                    patch.object(r, 'authenticate', return_value='synthetic-token'), \
                    patch.object(r, 'rustore', side_effect=responses) as api:
                args.evidence = Path(directory) / 'evidence.json'
                args.inspection = Path(directory) / 'inspection.json'
                args.inspection.write_text(json.dumps(self.inspection_receipt(args, template)))
                evidence = {'apk_sha256': 'f' * 64}
                if failure:
                    with self.assertRaises(r.PublishError):
                        r.publish_draft(args, evidence)
                else:
                    r.publish_draft(args, evidence)
                    self.assertEqual(evidence['status'], 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION')
                mutations = [call for call in api.call_args_list if len(call.args) > 2]
                self.assertEqual(len(mutations), 1 if failure in ('upload', 'metadata_after') else 2)
                self.assertEqual(mutations[0].args, (r.APP + '/version/43/apk?servicesType=Unknown&isMainApk=true',
                                                    'synthetic-token', 'POST'))
                self.assertEqual(json.loads(args.evidence.read_text())['status'],
                                 'APK_UPLOAD_RESULT_UNKNOWN' if failure in ('upload', 'metadata_after')
                                 else 'COMMIT_RESULT_UNKNOWN')
                self.assertNotIn('synthetic-private', args.evidence.read_text())
                self.assertTrue(all(call.args[2] == 'POST' for call in mutations))

    def test_v2_forbidden_blocks_without_endpoint_fallback(self):
        with patch.object(r, 'rustore', side_effect=r.PublishError('RuStore HTTP 403')) as api:
            with self.assertRaisesRegex(r.PublishError, 'BLOCKED: RuStore v2'):
                r.version_detail('synthetic-token', r.TEMPLATE_ID)
            api.assert_called_once()

    def replacement_fixture(self):
        args, page, template, target = self.draft_fixture()
        args.draft_id, args.replace_template = str(r.REPLACEMENT_ID), True
        page['content'][0]['versionStatus'] = 'MODERATION'
        page['content'][1]['versionId'] = r.REPLACEMENT_ID
        template['packages'] = [dict(verCode=6, verName='2.0.0', apkUid='old-apk')]
        template['fileUids'].append(dict(fileUid='old-apk', type='APK', fileUrl='synthetic-private'))
        return args, page, template, target

    def test_media_snapshot_preserves_public_ids_and_metadata_but_not_urls_or_binaries(self):
        _, _, source, target = self.replacement_fixture()
        self.assertEqual(r.media_snapshot(source), r.media_snapshot(target))
        changed = copy.deepcopy(source)
        changed['fileUids'].reverse()
        for item in changed['fileUids']:
            item['fileUrl'] = 'different-secret-url'
            item['blurhash'] = 'changed'
        changed['fileUids'].append(dict(fileUid='extra-aab', type='AAB'))
        self.assertEqual(r.media_snapshot(source), r.media_snapshot(changed))
        self.assertNotIn('synthetic-private', json.dumps(r.media_snapshot(source)))
        for change in ('icon', 'missing', 'unknown', 'duplicate'):
            detail = copy.deepcopy(target)
            if change == 'icon':
                detail['iconUid'] = 'other'
            elif change == 'missing':
                detail['fileUids'].pop()
            elif change == 'unknown':
                detail['fileUids'].append(dict(fileUid='extra', type='SCREENSHOT'))
            else:
                detail['fileUids'].append(detail['fileUids'][0])
            with self.subTest(change=change), self.assertRaises(r.PublishError):
                r.media_snapshot(detail)

    def test_media_accepts_exact_observed_rustore_storage_paths_not_arbitrary_suffixes(self):
        _, _, source, target = self.replacement_fixture()
        for detail in (source, target):
            detail['iconUid'] = r.MEDIA_PATHS[r.ICON_UID]
            for item in detail['fileUids']:
                if item['fileUid'] in r.MEDIA_PATHS:
                    item['fileUid'] = r.MEDIA_PATHS[item['fileUid']]
        self.assertEqual(r.media_snapshot(source), r.media_snapshot(target))
        self.assertEqual(r.media_snapshot(target)['iconUid'], r.MEDIA_PATHS[r.ICON_UID])
        for change in ('icon', 'screen'):
            changed = copy.deepcopy(target)
            if change == 'icon':
                changed['iconUid'] = 'other/' + r.ICON_UID + '.png'
            else:
                changed['fileUids'][0]['fileUid'] = 'other/' + sorted(r.SCREEN_UIDS)[0] + '.png'
            with self.subTest(change=change), self.assertRaises(r.PublishError):
                r.media_snapshot(changed)

    def test_replacement_blockers_prevent_delete_and_upload(self):
        for case in ('wrong_target', 'wrong_source', 'source_state', 'source_code', 'source_testing',
                     'source_apk', 'source_apk_name', 'extra_apk', 'other_pending', 'other_active',
                     'missing_source_media', 'missing_target_media', 'changed_source_media',
                     'changed_target_media', 'changed_source_listing', 'changed_target_listing',
                     'existing_target_apk', 'target_state', 'target_testing', 'publication', 'flag_absent'):
            args, page, source, target = self.replacement_fixture()
            receipt = self.inspection_receipt(args, source)
            if case == 'wrong_target':
                args.draft_id = '43'
            elif case == 'wrong_source':
                args.template_version_id = '42'
            elif case == 'source_state':
                page['content'][0]['versionStatus'] = 'ACTIVE'
            elif case == 'source_code':
                page['content'][0]['versionCode'] = 7
            elif case == 'source_testing':
                page['content'][0]['testingType'] = 'CLOSED'
            elif case == 'source_apk':
                source['packages'][0]['verCode'] = 7
            elif case == 'source_apk_name':
                source['packages'][0]['verName'] = 'other'
            elif case == 'extra_apk':
                source['packages'].append(dict(verCode=6, verName='2.0.0'))
            elif case in ('other_pending', 'other_active'):
                page['content'].append(dict(versionId=99, versionCode=5,
                    versionStatus='MODERATION' if case == 'other_pending' else 'ACTIVE'))
                page['totalElements'] = 3
            elif case in ('missing_source_media', 'missing_target_media'):
                (source if case == 'missing_source_media' else target)['fileUids'].pop(0)
            elif case in ('changed_source_media', 'changed_target_media'):
                (source if case == 'changed_source_media' else target)['fileUids'][0]['orientation'] = 'LANDSCAPE'
            elif case in ('changed_source_listing', 'changed_target_listing'):
                (source if case == 'changed_source_listing' else target)['fullDescription'] = 'changed'
            elif case == 'existing_target_apk':
                target['packages'] = [dict(verCode=6)]
            elif case == 'target_state':
                page['content'][1]['versionStatus'] = 'MODERATION'
            elif case == 'target_testing':
                page['content'][1]['testingType'] = 'CLOSED'
            elif case == 'publication':
                target['partialValue'] = 50
            elif case == 'flag_absent':
                args.replace_template = False
            with self.subTest(case=case), patch.object(r, 'authenticate', return_value='synthetic-token'), \
                    patch.object(r, 'validate_inspection', return_value=receipt), \
                    patch.object(r, 'rustore', side_effect=[page, source, target]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish_draft(args, {})
                self.assertTrue(all(len(call.args) == 2 for call in api.call_args_list))

    def test_replacement_single_delete_and_order_including_hard_deleted_source(self):
        for failure in (None, 'deleted_tombstone', 'delete_400', 'delete_unknown', 'source_still_pending',
                        'history_extra', 'history_target_changed', 'target_media_changed', 'target_listing_changed',
                        'target_has_apk', 'upload_unknown', 'uploaded_media_changed', 'commit_unknown',
                        'commit_read_unknown', 'commit_not_confirmed', 'commit_wrong_version'):
            args, page, source, target = self.replacement_fixture()
            post_page = dict(content=[copy.deepcopy(page['content'][1])], totalPages=1, totalElements=1)
            after_delete = copy.deepcopy(target)
            after_upload = dict(copy.deepcopy(target), packages=[dict(verCode=7, verName='2.0.1')])
            committed = dict(copy.deepcopy(after_upload), versionStatus='MODERATION')
            if failure == 'deleted_tombstone':
                post_page['content'].append(dict(page['content'][0], versionStatus='DELETED_DRAFT'))
                post_page['totalElements'] = 2
            elif failure == 'source_still_pending':
                post_page = page
            elif failure == 'history_extra':
                post_page['content'].append(dict(versionId=99, versionStatus='ACTIVE', versionCode=5))
                post_page['totalElements'] = 2
            elif failure == 'history_target_changed':
                post_page['content'][0]['versionStatus'] = 'MODERATION'
            elif failure == 'target_media_changed':
                after_delete['fileUids'][0]['ordinal'] = 99
            elif failure == 'target_listing_changed':
                after_delete['fullDescription'] = 'changed'
            elif failure == 'target_has_apk':
                after_delete['packages'] = [dict(verCode=6)]
            elif failure == 'uploaded_media_changed':
                after_upload['fileUids'][0]['deviceType'] = 'TABLET'
            elif failure == 'commit_not_confirmed':
                committed['versionStatus'] = 'DRAFT'
            elif failure == 'commit_wrong_version':
                committed['packages'][0]['verCode'] = 6
            responses = [page, source, target,
                         r.PublishError('HTTP 400') if failure == 'delete_400' else
                         r.PublishError('Unknown delete') if failure == 'delete_unknown' else None,
                         post_page, after_delete,
                         r.PublishError('Unknown upload') if failure == 'upload_unknown' else None,
                         after_upload, r.PublishError('Unknown commit') if failure == 'commit_unknown' else None,
                         r.PublishError('Unknown read') if failure == 'commit_read_unknown' else committed]
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, \
                    patch.object(r, 'authenticate', return_value='synthetic-token'):
                args.evidence = Path(directory) / 'evidence.json'
                args.inspection = Path(directory) / 'inspection.json'
                args.inspection.write_text(json.dumps(self.inspection_receipt(args, source)))
                evidence = {'apk_sha256': 'f' * 64}
                responses = iter(responses)
                def respond(url, token, method='GET', **kwargs):
                    if method != 'GET':
                        phase = json.loads(args.evidence.read_text())['status']
                        self.assertEqual(phase, 'TEMPLATE_DELETE_RESULT_UNKNOWN' if method == 'DELETE' else
                                         'APK_UPLOAD_RESULT_UNKNOWN' if '/apk?' in url else 'COMMIT_RESULT_UNKNOWN')
                    response = next(responses)
                    if isinstance(response, Exception):
                        raise response
                    return response
                with patch.object(r, 'rustore', side_effect=respond) as api:
                    if failure not in (None, 'deleted_tombstone'):
                        with self.assertRaises(r.PublishError):
                            r.publish_draft(args, evidence)
                    else:
                        r.publish_draft(args, evidence)
                        self.assertTrue(evidence['template_deletion_confirmed'])
                        self.assertEqual(evidence['status'], 'SUBMITTED_FOR_REVIEW_NOT_PROOF_OF_PUBLICATION')
                        self.assertEqual(evidence['rustore_version_status'], 'MODERATION')
                calls = [(call.args[0], call.args[2] if len(call.args) > 2 else 'GET')
                         for call in api.call_args_list]
                history = r.APP + '/version?filterTestingType=ALL&page=0&size=100'
                draft = r.APP + '/version/' + str(r.REPLACEMENT_ID)
                detail = r.DETAIL + '/version/' + str(r.REPLACEMENT_ID)
                expected = [(history, 'GET'), (r.DETAIL + '/version/' + str(r.TEMPLATE_ID), 'GET'),
                            (detail, 'GET'), (r.APP + '/version/' + str(r.TEMPLATE_ID), 'DELETE'),
                            (history, 'GET'), (detail, 'GET'),
                            (draft + '/apk?servicesType=Unknown&isMainApk=true', 'POST'), (detail, 'GET'),
                            (draft + '/commit?priorityUpdate=0', 'POST'), (detail, 'GET')]
                self.assertEqual(calls, expected[:len(calls)])
                self.assertEqual(sum(method == 'DELETE' for _, method in calls), 1)
                if failure in ('delete_400', 'delete_unknown'):
                    self.assertEqual(len(calls), 4)
                if failure not in (None, 'deleted_tombstone'):
                    self.assertEqual(json.loads(args.evidence.read_text())['status'],
                                     'TEMPLATE_DELETE_RESULT_UNKNOWN' if len(calls) <= 6 else
                                     'APK_UPLOAD_RESULT_UNKNOWN' if len(calls) <= 8 else 'COMMIT_RESULT_UNKNOWN')
                self.assertNotIn('synthetic-private', args.evidence.read_text())

    def test_replacement_flag_requires_exact_mode_and_ids_before_any_service(self):
        for flags in (['--inspect'], ['--publish-draft', '--draft-id', '43'],
                      ['--publish-draft', '--draft-id', str(r.REPLACEMENT_ID), '--template-version-id', '42']):
            argv = ['publish-rustore.py', '--replace-template', '--google-run-id', '123',
                    '--source-sha', 'a' * 40, '--repository', 'owner/repo'] + flags
            with self.subTest(flags=flags), patch.object(r.sys, 'argv', argv), \
                    patch.object(r, 'verify_source') as source, patch.object(r, 'authenticate') as auth, \
                    patch.object(r.sys, 'stderr'):
                with self.assertRaises(SystemExit):
                    r.main()
                source.assert_not_called()
                auth.assert_not_called()

    def test_ok_without_body_is_accepted_for_delete_and_commit(self):
        for suffix, method in (('/version/' + str(r.TEMPLATE_ID), 'DELETE'),
                               ('/version/' + str(r.REPLACEMENT_ID) + '/commit?priorityUpdate=0', 'POST')):
            with patch.object(r.urllib.request, 'build_opener') as opener:
                response = MagicMock()
                response.read.return_value = b'{"code":"OK"}'
                opener.return_value.open.return_value.__enter__.return_value = response
                self.assertIsNone(r.rustore(r.APP + suffix, 'synthetic-token', method))
                self.assertEqual(opener.return_value.open.call_count, 1)

    def test_only_approved_rustore_urls_are_allowed(self):
        for url in ('https://example.invalid/public/auth', r.BASE + '/v1/application/other/version',
                    'https://public-api-m.rustore.ru.evil/public/auth'):
            with self.subTest(url=url), patch.object(r.urllib.request, 'build_opener') as opener:
                with self.assertRaises(r.PublishError):
                    r.rustore(url, 'synthetic-token')
                opener.assert_not_called()

    def test_inspect_and_publish_draft_keep_source_receipt_apk_gates(self):
        for mode in ('--inspect', '--publish-draft'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                receipt = Path(directory) / 'receipt.json'
                receipt.write_text('{}')
                evidence = Path(directory) / 'evidence.json'
                argv = ['publish-rustore.py', mode, '--draft-id', '43', '--google-run-id', '123',
                        '--source-sha', 'a' * 40, '--repository', 'owner/repo',
                        '--receipt', str(receipt), '--evidence', str(evidence)]
                order = []
                def mark(name):
                    return lambda *args: order.append(name) or {}
                with patch.object(r.sys, 'argv', argv), patch('builtins.print'), \
                        patch.object(r, 'verify_source', side_effect=mark('source')), \
                        patch.object(r, 'validate_receipt', side_effect=mark('receipt')), \
                        patch.object(r, 'download_apk', side_effect=mark('apk')), \
                        patch.object(r, 'inspect', side_effect=mark('inspect')), \
                        patch.object(r, 'publish_draft', side_effect=mark('draft')):
                    self.assertEqual(r.main(), 0)
                self.assertEqual(order, ['source', 'receipt', 'apk', 'inspect' if mode == '--inspect' else 'draft'])

    def test_receipt_requires_success_exact_version_sha(self):
        receipt = dict(status='EDIT_COMMITTED_FOR_REVIEW', outcome='EDIT_COMMITTED_FOR_REVIEW',
                       package=r.PACKAGE, version_code=7, release_version='2.0.1', source_sha='a' * 40)
        r.validate_receipt(receipt, 'a' * 40)
        for key, value in [('status', 'COMMIT_RESULT_UNKNOWN'), ('outcome', 'FAILED'),
                           ('version_code', 6), ('source_sha', 'b' * 40), ('package', 'other')]:
            with self.subTest(key=key), self.assertRaises(r.PublishError):
                r.validate_receipt(dict(receipt, **{key: value}), 'a' * 40)

    def test_no_split_standalone_or_unknown_certificate_fallback(self):
        for groups in [[], [{'certificateSha256Hash': r.NEW_CERT, 'generatedStandaloneApks': [{}]}],
                       [{'certificateSha256Hash': 'f' * 64, 'generatedUniversalApk': {'downloadId': 'x'}}]]:
            with self.assertRaises(r.PublishError):
                r.select_universal({'generatedApks': groups})
        group = lambda cert: {'certificateSha256Hash': cert, 'generatedUniversalApk': {'downloadId': cert}}
        self.assertEqual(r.select_universal({'generatedApks': [group(r.OLD_CERT), group(r.NEW_CERT)]}),
                         (r.NEW_CERT, r.NEW_CERT))
        with self.assertRaises(r.PublishError):
            r.select_universal({'generatedApks': [group(r.NEW_CERT), group(r.NEW_CERT)]})

    def test_pending_and_unknown_status_stop_before_creation(self):
        for status in ['MODERATION', 'TAKEN_FOR_MODERATION', 'AUTO_CHECK', 'DRAFT',
                       'READY_FOR_PUBLICATION', 'PARTIAL_ACTIVE', 'UNKNOWN', 'REJECTED_BY_MODERATOR']:
            page = {'content': [{'versionStatus': status, 'versionCode': 6}],
                    'totalElements': 1, 'totalPages': 1}
            with patch.object(r, 'rustore', return_value=page) as api:
                with self.assertRaises(r.PublishError):
                    r.preflight('synthetic-token')
                api.assert_called_once()
                self.assertNotIn('POST', api.call_args.args)

    def test_preflight_preserves_active_metadata_and_app_scope(self):
        page = {'content': [{'versionStatus': 'ACTIVE', 'versionCode': 6, 'versionId': 42,
                             'testingType': None}], 'totalElements': 1, 'totalPages': 1}
        detail = {'appId': 2063785208, 'paid': False, 'ageLegal': '0+', 'appType': 'MAIN', 'categories': ['tools']}
        with patch.object(r, 'rustore', side_effect=[page, detail]) as api:
            self.assertEqual(r.preflight('synthetic-token'), 42)
            self.assertEqual(api.call_args.args[0], r.DETAIL + '/version/42')
        for changed in [dict(detail, appId=1), dict(detail, paid=True)]:
            with patch.object(r, 'rustore', side_effect=[page, changed]), self.assertRaises(r.PublishError):
                r.preflight('synthetic-token')
        with patch.object(r, 'rustore', return_value=dict(page, totalPages=2)), self.assertRaises(r.PublishError):
            r.preflight('synthetic-token')

    def test_auth_uses_rsa_sha512_key_id_timestamp_not_local_jwe(self):
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
        with patch.dict(os.environ, RUSTORE_API_KEY_ID='2351035158', RUSTORE_API_PRIVATE_KEY=private), \
                patch.object(r, 'rustore', side_effect=auth):
            self.assertEqual(r.authenticate(), 'synthetic-jwe')
        for missing in (None, {}, [], {'jwe': ''}):
            with self.subTest(missing=missing), \
                    patch.dict(os.environ, RUSTORE_API_KEY_ID='2351035158', RUSTORE_API_PRIVATE_KEY=private), \
                    patch.object(r, 'rustore', return_value=missing) as api:
                with self.assertRaisesRegex(r.PublishError, 'Missing RuStore authorization token'):
                    r.authenticate()
                api.assert_called_once()

    def test_http_failure_does_not_leak_or_retry(self):
        error = urllib.error.HTTPError('https://example.invalid/?secret=synthetic-secret', 403,
                                      'synthetic-secret', {}, None)
        with patch.object(r.urllib.request, 'build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(r.PublishError) as caught:
                r.rustore(r.APP + '/version', 'synthetic-secret', 'POST', {})
            self.assertNotIn('synthetic-secret', str(caught.exception))
            self.assertEqual(opener.return_value.open.call_count, 1)

    def test_apk_requires_both_sdk_signers_and_exact_package_version(self):
        args = SimpleNamespace(aapt='aapt', apksigner='apksigner', apk=Path('synthetic.apk'))
        result = lambda output: SimpleNamespace(returncode=0, stdout=output)
        outputs = [result("package: name='su.linka.pictures' versionCode='7' versionName='2.0.1' platformBuildVersionName='15'"),
                   result('Signer #1 certificate SHA-256 digest: ' + r.OLD_CERT),
                   result('Signer #1 certificate SHA-256 digest: ' + r.NEW_CERT)]
        with patch.object(r.subprocess, 'run', side_effect=outputs) as run:
            r.verify_apk(args)
            self.assertEqual(run.call_count, 3)
        with patch.object(r.subprocess, 'run', side_effect=[outputs[0], outputs[2]]), self.assertRaises(r.PublishError):
            r.verify_apk(args)

    def test_source_rejects_other_workflow_before_other_requests(self):
        args = SimpleNamespace(google_run_id='123', source_sha='a' * 40, repository='owner/repo')
        with patch.object(r.play, 'request', return_value={'path': '.github/workflows/android.yml'}) as api:
            with self.assertRaises(r.PublishError):
                r.verify_source(args)
            api.assert_called_once()

    def test_uncertain_commit_preserves_phase_and_never_retries_or_cancels(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(apk=Path('synthetic.apk'), evidence=Path(directory) / 'evidence.json')
            detail = {'packages': [{'verCode': 7, 'verName': '2.0.1'}],
                      'publishType': 'INSTANTLY', 'partialValue': 100}
            evidence = {'status': 'GOOGLE_APK_VERIFIED'}
            with patch.object(r, 'authenticate', return_value='synthetic-secret'), \
                    patch.object(r, 'preflight', return_value=42), \
                    patch.object(r, 'rustore', side_effect=[43, {}, detail, r.PublishError('Unknown commit')]) as api:
                with self.assertRaises(r.PublishError):
                    r.publish(args, evidence)
                self.assertEqual(api.call_count, 4)
                self.assertTrue(api.call_args.args[0].endswith('/43/commit?priorityUpdate=0'))
                self.assertEqual(json.loads(args.evidence.read_text())['status'], 'COMMIT_RESULT_UNKNOWN')
                self.assertNotIn('synthetic-secret', args.evidence.read_text())
                create = api.call_args_list[0].args[3]
                self.assertEqual(create['publishType'], 'INSTANTLY')
                self.assertNotIn('privacyExplanation', create)

    def test_prepare_calls_no_external_service(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / 'evidence.json'
            argv = ['publish-rustore.py', '--prepare', '--google-run-id', '123',
                    '--source-sha', 'a' * 40, '--repository', 'owner/repo', '--evidence', str(evidence)]
            with patch.object(r.sys, 'argv', argv), patch.object(r, 'verify_source') as source, \
                    patch.object(r, 'rustore') as api, patch('builtins.print'):
                self.assertEqual(r.main(), 0)
                source.assert_not_called()
                api.assert_not_called()
                self.assertEqual(json.loads(evidence.read_text())['status'], 'PREPARE_NO_API_CALLS')


if __name__ == '__main__':
    unittest.main()
