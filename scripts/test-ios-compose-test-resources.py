#!/usr/bin/env python3
"""Exercise native test resource packaging without linking FFmpeg or running a simulator."""

import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TASK = ':shared:copyTestComposeResourcesForIosSimulatorArm64'
DESTINATIONS = [ROOT / 'shared/build/bin/iosSimulatorArm64' / binary / 'compose-resources'
                for binary in ('debugTest', 'appHostDebugTest')]


def run(*arguments):
    return subprocess.run([str(ROOT / 'gradlew'), TASK, '--console=plain', *arguments],
                          cwd=ROOT, env=os.environ, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def main():
    result = run('--rerun')
    assert result.returncode == 0, result.stdout
    aggregate = ROOT / 'shared/build/kotlin-multiplatform-resources/aggregated-resources/iosSimulatorArm64'
    files = list(aggregate.rglob('*.cvr'))
    assert files, 'Missing actual Compose resource inputs'
    for destination in DESTINATIONS:
        for source in files:
            assert (destination / source.relative_to(aggregate)).read_bytes() == source.read_bytes()
    missing = DESTINATIONS[0] / files[0].relative_to(aggregate)
    missing.unlink()
    result = run()
    assert result.returncode == 0, result.stdout
    assert missing.read_bytes() == files[0].read_bytes(), 'Untracked test resource output was not restored'

    fixture = 'composeResources/native-test-regression/equal.txt'
    try:
        with tempfile.TemporaryDirectory(prefix='ios-resource-regression-', dir=ROOT / 'shared/build') as temp:
            directory = Path(temp)
            sources = [directory / 'one', directory / 'two']
            for source in sources:
                (source / fixture).parent.mkdir(parents=True)
                (source / fixture).write_text('identical bytes')
            init = directory / 'fixture.gradle'
            init.write_text('gradle.projectsEvaluated {\n'
                            f"    rootProject.project(':shared').tasks.named('{TASK.split(':')[-1]}') {{\n"
                            f"        from('{sources[0]}')\n        from('{sources[1]}')\n"
                            '    }\n}\n')
            result = run('--rerun', '-I', str(init))
            assert result.returncode == 0, result.stdout
            for destination in DESTINATIONS:
                assert (destination / fixture).read_text() == 'identical bytes'
            (sources[1] / fixture).write_text('different bytes')
            result = run('--rerun', '-I', str(init))
            assert result.returncode != 0, 'Different resources were silently accepted'
            assert f'Conflicting iOS test resource: {fixture}' in result.stdout, result.stdout
    finally:
        for destination in DESTINATIONS:
            (destination / fixture).unlink(missing_ok=True)
    print('PASS: main resources reach both test binaries; equal duplicates coalesce; differing bytes fail.')


if __name__ == '__main__':
    main()
