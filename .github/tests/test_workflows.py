"""Contract checks against the real workflows; no YAML/runtime dependency.

These deliberately inspect the repository's fixed YAML layout, not arbitrary YAML.
actionlint checks the full GitHub Actions syntax separately.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOWS = Path(__file__).resolve().parents[1] / "workflows"
CI = (WORKFLOWS / "android.yml").read_text()
VERIFY = (WORKFLOWS / "android-verify.yml").read_text()
PRODUCTION = (WORKFLOWS / "google-play-production.yml").read_text()
APP_BUILD = (WORKFLOWS.parents[1] / "app" / "build.gradle").read_text()


def job(workflow, name):
    return re.search(rf"(?ms)^  {name}:\n(.*?)(?=^  [\w-]+:\n|\Z)",
                     workflow.split("\njobs:\n", 1)[1])[1]


def step(workflow, name):
    return re.search(rf"(?ms)^      - name: {re.escape(name)}\n(.*?)(?=^      - name:|^  [\w-]+:\n|\Z)",
                     workflow)[1]


def run_script(step_body):
    return textwrap.dedent(step_body.split("        run: |\n", 1)[1]).strip()


class WorkflowContract(unittest.TestCase):
    def test_tracked_bundled_sets_have_no_case_fold_collisions(self):
        paths = subprocess.check_output(
            ["git", "ls-files", "-z", "--", "app/src/main/assets/sets"],
            cwd=WORKFLOWS.parents[1]).decode().split("\0")
        names = [Path(path).name for path in paths if path]
        self.assertEqual(len(names), len({name.casefold() for name in names}), names)
        self.assertEqual(len(names), 6)

    def test_all_pr_bases_and_main_push(self):
        triggers = CI.split("\npermissions:", 1)[0]
        self.assertRegex(triggers, r"push:\n    branches: \[main\]")
        self.assertRegex(triggers, r"pull_request:\s*\Z")
        self.assertNotIn("secrets:", CI)
        self.assertIn("uses: ./.github/workflows/android-verify.yml", CI)
        self.assertIn("sha: ${{ github.sha }}", CI)

    def test_exact_build_and_device_gates(self):
        build = job(VERIFY, "build")
        for task in ("shared:jvmTest", "app:lintDebug", "app:lintRelease", "app:testDebugUnitTest",
                     "app:testReleaseUnitTest", "app:verifyReleasePrivacy", "app:verifyReleasePolicy",
                     "app:assembleDebug", "app:assembleDebugAndroidTest"):
            self.assertIn(":" + task, build)
        self.assertIn("ref: ${{ inputs.sha }}", build)
        identity = step(build, "Check source identity and release trust")
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', identity)
        self.assertIn('git merge-base --is-ancestor "$EXPECTED_SHA" origin/main', identity)
        device = job(VERIFY, "device")
        self.assertIn("needs: build", device)
        self.assertIn("api: [24, 36]", device)
        self.assertIn("api-level: ${{ matrix.api }}", device)
        self.assertIn("name: android-debug-${{ inputs.sha }}", device)
        self.assertIn("adb install apks/debug/app-debug.apk", device)
        self.assertIn("adb install apks/androidTest/debug/app-debug-androidTest.apk", device)
        self.assertIn("adb shell am instrument -w -r", device)
        self.assertIn("grep -Eq '^OK ", device)
        self.assertIn("INSTRUMENTATION_FAILED", device)
        checkout = step(device, "Checkout exact smoke source and bundled fixtures")
        self.assertIn("ref: ${{ inputs.sha }}", checkout)
        self.assertIn("persist-credentials: false", checkout)
        smoke = "python3 -B .github/tests/process_death.py"
        self.assertIn(smoke, device)
        self.assertLess(device.index("INSTRUMENTATION_ABORTED|Process crashed"), device.index(smoke))
        # Ordinary gates may never be conditional or failure-tolerant.
        for body in (step(build, "Unit, lint, privacy and APK gates"), device):
            self.assertNotRegex(body, r"(?m)^\s+if:")
        for workflow in (CI, VERIFY, PRODUCTION):
            self.assertNotIn("continue-on-error", workflow)
            self.assertNotIn("|| true", workflow)
        self.assertNotIn("always()", CI + PRODUCTION)
        self.assertNotRegex(job(VERIFY, "build") + device + PRODUCTION, r"(?m)^    if:")

    def test_publication_cannot_bypass_or_rebuild(self):
        triggers = PRODUCTION.split("\npermissions:", 1)[0]
        self.assertIn("types: [published]", triggers)
        self.assertRegex(triggers, r"version_code:\n        description: [^\n]+\n        required: true")
        verify = job(PRODUCTION, "verify")
        self.assertIn("needs: resolve", verify)
        self.assertIn("uses: ./.github/workflows/android-verify.yml", verify)
        self.assertIn("sha: ${{ needs.resolve.outputs.sha }}", verify)
        self.assertIn("release: true", verify)
        gated = job(VERIFY, "verified-artifact")
        self.assertIn("needs: [build, device]", gated)
        self.assertIn("if: always()", gated)
        guard = step(gated, "Require successful build and every device")
        self.assertIn("BUILD_RESULT: ${{ needs.build.result }}", guard)
        self.assertIn("DEVICE_RESULT: ${{ needs.device.result }}", guard)
        self.assertIn('test "$BUILD_RESULT" = success', guard)
        self.assertIn('test "$DEVICE_RESULT" = success', guard)
        self.assertNotIn("if:", guard)
        self.assertIn("name: release-candidate-${{ inputs.sha }}", gated)
        self.assertIn("name: verified-release-${{ inputs.sha }}", gated)
        self.assertIn("artifact_id: ${{ steps.upload.outputs.artifact-id }}", gated)
        self.assertIn("artifact_digest: ${{ steps.upload.outputs.artifact-digest }}", gated)
        publish = job(PRODUCTION, "publish")
        self.assertIn("needs: [resolve, verify]", publish)
        self.assertIn("artifact-ids: ${{ needs.verify.outputs.artifact_id }}", publish)
        self.assertIn("ARTIFACT_DIGEST: ${{ needs.verify.outputs.artifact_digest }}", publish)
        self.assertIn('= "sha256:$ARTIFACT_DIGEST"', publish)
        self.assertIn('= "$RUN_ID"', publish)
        self.assertIn('test "$VERIFIED_SHA" = "$EXPECTED_SHA"', publish)
        for body in (gated, publish):
            self.assertIn("sha256sum --check SHA256SUMS", body)
            self.assertIn('test "$(cat commit.txt)" = ', body)
            self.assertIn('test "$(cat version-code.txt)" = "$VERSION_CODE"', body)
            self.assertNotIn("gradlew", body)
            self.assertNotIn("actions/checkout@", body)
        self.assertIn("releaseFiles: verified-release/app-release.aab", publish)
        signed = step(VERIFY, "Build signed release candidate")
        self.assertIn('--no-configuration-cache --dependency-verification strict :app:verifyReleaseBundle -PverifyReleaseSigning=true -PreleaseVersionCode="$VERSION_CODE"', signed)
        for secret in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
                       "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
            self.assertIn("${" + secret + ":?}", signed)
        self.assertIn("sha256sum app-release.aab > SHA256SUMS", signed)
        self.assertIn("git rev-parse HEAD > release-candidate/commit.txt", signed)

    def test_release_manifest_gate_reads_the_aab_not_a_sidecar(self):
        gate = APP_BUILD.split("tasks.register('verifyReleaseBundle') {", 1)[1].split(
            "\ndef needsReleaseSigning", 1)[0]
        self.assertIn("dependsOn 'bundleRelease'", gate)
        self.assertIn("outputs/bundle/release/app-release.aab", gate)
        self.assertIn("new java.util.zip.ZipFile(bundle)", gate)
        self.assertIn("base/manifest/AndroidManifest.xml", gate)
        self.assertIn("com.android.aapt.Resources.XmlNode.parseFrom(stream)", gate)
        self.assertIn("requireReleaseManifest(", gate)
        for field in ("packageName", "versionCode", "minSdkVersion", "targetSdkVersion"):
            self.assertIn("manifest." + field, gate)
        self.assertIn("], versionCodeInput)", gate)
        self.assertNotIn("version-code.txt", gate)

    def test_verification_is_strict_and_never_generated_by_ci(self):
        for workflow in (CI, VERIFY, PRODUCTION):
            for line in workflow.splitlines():
                if "./gradlew " in line:
                    self.assertIn("--dependency-verification strict", line)
            self.assertNotIn("--write-verification-metadata", workflow)
            self.assertNotIn("--dependency-verification off", workflow)
            self.assertNotIn("--dependency-verification lenient", workflow)
        guard = run_script(step(VERIFY, "Require reviewed dependency verification metadata"))
        self.assertIn("test -s gradle/verification-metadata.xml", guard)
        self.assertIn("git ls-files --error-unmatch gradle/verification-metadata.xml", guard)
        metadata = '''<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
          <configuration><verify-metadata>true</verify-metadata></configuration>
          <components><component group="example" name="library" version="1">
            <artifact name="library-1.jar"><sha256 value="HASH"/></artifact>
          </component></components></verification-metadata>'''.replace("HASH", "a" * 64)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", directory], check=True)
            path = root / "gradle" / "verification-metadata.xml"
            path.parent.mkdir()
            cases = [(None, False), ("", False),
                     (metadata.replace('<components>', '<components-disabled>').replace(
                         '</components>', '</components-disabled>'), False),
                     (metadata.replace("<verify-metadata>true", "<verify-metadata>false"), False),
                     (metadata.replace("a" * 64, "not-a-checksum"), False),
                     (metadata.replace("</configuration>", '<trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts></configuration>'), False),
                     (metadata, True)]
            for content, success in cases:
                with self.subTest(content=content):
                    if content is not None:
                        path.write_text(content)
                        subprocess.run(["git", "add", str(path)], cwd=root, check=True)
                    result = subprocess.run(["bash", "-e", "-c", guard], cwd=root,
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, success, result.stderr)

    def test_pins_and_runtime(self):
        for workflow in (CI, VERIFY, PRODUCTION):
            for action in re.findall(r"uses: (\S+)", workflow):
                self.assertTrue(action.startswith("./.github/workflows/") or
                                re.fullmatch(r"[\w./-]+@[0-9a-f]{40}", action), action)
            for runner in re.findall(r"runs-on: (\S+)", workflow):
                self.assertEqual(runner, "ubuntu-24.04")
        self.assertIn("java-version: '21.0.13+9'", VERIFY)
        self.assertNotIn("FIREBASE_KEY", CI + VERIFY)
        self.assertNotIn("secrets: inherit", PRODUCTION)

    def test_final_check_fails_instead_of_skipping_upstream_failure(self):
        guard = run_script(step(VERIFY, "Require successful build and every device"))
        for build, device in (("success", "success"), ("failure", "skipped"),
                              ("success", "failure"), ("cancelled", "cancelled"),
                              ("success", "skipped")):
            with self.subTest(build=build, device=device):
                result = subprocess.run(["bash", "-e", "-c", guard],
                                        env={**os.environ, "BUILD_RESULT": build,
                                             "DEVICE_RESULT": device}, capture_output=True)
                self.assertEqual(result.returncode == 0, build == device == "success")

    def test_real_resolver_rejects_untrusted_refs_and_bad_versions(self):
        resolver = step(PRODUCTION, "Resolve and authorize immutable release source")
        self.assertIn("VERSION_CODE: ${{ inputs.version_code || vars.ANDROID_VERSION_CODE }}", resolver)
        script = run_script(resolver)
        self.assertNotIn("${{", script)  # Ref input is data, never interpolated shell.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args],
                                               stderr=subprocess.DEVNULL, text=True).strip()
            git("init", "-b", "main")
            git("config", "user.email", "ci@example.invalid")
            git("config", "user.name", "CI contract")
            git("commit", "--allow-empty", "-m", "trusted")
            trusted = git("rev-parse", "HEAD")
            git("tag", "trusted")
            git("checkout", "-b", "untrusted")
            git("commit", "--allow-empty", "-m", "not merged")
            git("tag", "untrusted")
            git("checkout", "main")
            git("remote", "add", "origin", directory)
            git("fetch", "origin", "main:refs/remotes/origin/main")
            for ref, code, success in ((trusted, "1", True), ("trusted", "2100000000", True),
                                       ("untrusted", "2", False),
                                       ("+refs/tags/untrusted:refs/remotes/origin/main", "2", False),
                                       ("trusted", "", False), ("trusted", "0", False),
                                       ("trusted", "2100000001", False),
                                       ("trusted", "1;exit 0", False)):
                with self.subTest(ref=ref, code=code):
                    output = root / "output"
                    output.write_text("")
                    result = subprocess.run(["bash", "-c", script], cwd=root,
                                            env={**os.environ, "RELEASE_REF": ref,
                                                 "VERSION_CODE": code, "GITHUB_OUTPUT": str(output)},
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, success, result.stderr)
                    self.assertEqual(git("rev-parse", "origin/main"), trusted)
                    if success:
                        self.assertIn("sha=" + trusted, output.read_text())
                    else:
                        self.assertEqual(output.read_text(), "")


if __name__ == "__main__":
    unittest.main()
