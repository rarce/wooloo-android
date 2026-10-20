import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from release_metadata import MAX_FEED, feed, should_mark_latest, version_code


class ReleaseMetadataTest(unittest.TestCase):
    def test_latest_uses_all_stable_releases(self):
        self.assertTrue(should_mark_latest('1.0.0', []))
        self.assertFalse(should_mark_latest('1.0.1', ['v2.0.0']))
        self.assertTrue(should_mark_latest('2.0.1', ['v2.0.0']))
        self.assertTrue(should_mark_latest('2.0.0', ['v2.0.0']))
        # More than one API page, deliberately unsorted, with the highest version at the end.
        tags = ['v1.0.' + str(patch) for patch in range(150)] + ['v3.0.0']
        self.assertFalse(should_mark_latest('2.0.0', tags))
        for invalid in ['v01.0.0', 'legacy', '1.0.0', 'v1.1000.0', 'v0.0.0', '', 'v1.0.0\n']:
            with self.subTest(tag=invalid), self.assertRaises(ValueError):
                should_mark_latest('2.0.0', ['v1.0.0', invalid])

    def test_versions(self):
        for value in ['01.2.3', '1.02.3', '1.2.03', '0.0.0', '1.1000.0',
                      '1.0.1000', '2147.483.648', '999999999999999999999.0.0']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                version_code(value)
        self.assertEqual(2147483647, version_code('2147.483.647'))
        self.assertLess(version_code('1.999.999'), version_code('2.0.0'))
        self.assertEqual(1, version_code('0.0.1'))

    def test_feed_utf8_boundary_and_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            apk.write_bytes(b'apk')
            identity = "package: name='dev.herdroid' versionCode='1002003' versionName='1.2.3'"
            args = (apk, '', '1.2.3', 'https://example.com/app.apk', identity)
            fixture = Path(__file__).resolve().parents[1] / 'app/src/test/resources/update/generated-feed.json'
            self.assertEqual(fixture.read_bytes(), feed(apk, 'Mejoras 🐑\n更新', *args[2:]))
            payload = json.loads(feed(*args))
            self.assertEqual(3, payload['size'])
            self.assertEqual(hashlib.sha256(b'apk').hexdigest(), payload['sha256'])
            for wrong in [identity.replace('1002003', '1002004'), identity.replace('1.2.3', '1.2.4')]:
                with self.assertRaisesRegex(ValueError, 'package/version'):
                    feed(apk, '', *args[2:4], wrong)
            baseline = len(feed(*args))
            notes = 'é' * ((MAX_FEED - baseline) // 2)
            result = feed(apk, notes, *args[2:])
            self.assertLessEqual(len(result), MAX_FEED)
            with self.assertRaisesRegex(ValueError, '64 KiB'):
                feed(apk, notes + 'é', *args[2:])
            with self.assertRaisesRegex(ValueError, 'package/version'):
                feed(apk, '', *args[2:4], identity.replace('dev.herdroid', 'wrong'))
            with self.assertRaisesRegex(ValueError, 'HTTPS'):
                feed(apk, '', args[2], 'http://example.com/app.apk', identity)
            apk.write_bytes(b'')
            with self.assertRaisesRegex(ValueError, 'nonempty'):
                feed(*args)
            with apk.open('wb') as output:
                output.truncate(256 * 1024 * 1024 + 1)
            with self.assertRaisesRegex(ValueError, '256 MiB'):
                feed(*args)


if __name__ == '__main__':
    unittest.main()
