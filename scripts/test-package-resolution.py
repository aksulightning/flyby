#!/usr/bin/env python3
"""Offline regressions for release selection, provenance and source fallback."""
import hashlib
import http.client
import ssl
import importlib.util
import io
import os
import pathlib
import tarfile
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
from alpine_packages import BASE, package_record, rootfs_release, resolve, base_packages, image_requirements
from alpine_sources import source_manifest, recipe_sources

spec = importlib.util.spec_from_file_location('bundle', pathlib.Path(__file__).with_name('bundle-sources.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class PackageResolutionTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('FLYBY_TEST_DOCKER'), 'Docker integration is opt-in')
    def test_recipe_expands_variables_and_preserves_alias(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            (directory/'APKBUILD').write_text(
                'pkgname=example\npkgver=99.2\n'
                'source="$pkgname-pkgver.tar.gz::https://example.test/$pkgver.tar.gz"\n'
                'sha512sums="'+'a'*128+' example-pkgver.tar.gz"\n')
            self.assertEqual(recipe_sources(directory),
                             [('a'*128, 'example-pkgver.tar.gz', 'https://example.test/99.2.tar.gz')])

    def test_images_resolve_reverse_dependencies_and_parent_packages(self):
        content = b'P:libcrypto3\nV:old\n\nP:libssl3\nV:old\n'
        with tempfile.TemporaryDirectory() as tmp:
            rootfs = pathlib.Path(tmp)/'root.tar.gz'
            with tarfile.open(rootfs, 'w:gz') as archive:
                item = tarfile.TarInfo('./lib/apk/db/installed')
                item.size = len(content)
                archive.addfile(item, io.BytesIO(content))
            requirements = image_requirements(base_packages(rootfs))
        for image, packages in requirements.items():
            if image == 'base':
                self.assertTrue({'libcrypto3', 'libssl3', 'doas'} <= set(packages))
                continue
            self.assertTrue({'libcrypto3', 'libssl3', 'openrc-init'} <= set(packages))
        self.assertTrue(set(requirements['x11']) <= set(requirements['x11-gpu']))

    def test_release_is_selected_from_metadata(self):
        release = dict(flavor='alpine-minirootfs', arch='riscv64',
                       file='alpine-minirootfs-future-riscv64.tar.gz',
                       version='future', sha256='a'*64)
        self.assertEqual(rootfs_release([dict(flavor='netboot'), release]), release)
        with self.assertRaises(ValueError):
            rootfs_release([dict(release, file='../escape.tar.gz')])
        with self.assertRaises(ValueError):
            rootfs_release([release, release])

    def test_reuse_does_not_resolve_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = pathlib.Path(tmp)/'lock.json'
            lock.write_text('{"schema": 1}')
            with patch('alpine_packages.LOCK_PATH', lock), patch('alpine_packages.subprocess.run') as run:
                self.assertEqual(resolve(), {'schema': 1})
                run.assert_not_called()

    def test_package_version_and_repository_come_from_download(self):
        content = b'pkgname = example\npkgver = 99.2-r7\nlicense = MIT\norigin = example\ncommit = abc\n'
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)/'example.apk'
            with tarfile.open(path, 'w:gz') as tar:
                item = tarfile.TarInfo('.PKGINFO')
                item.size = len(content)
                tar.addfile(item, io.BytesIO(content))
            record = package_record(path, BASE+'community/riscv64/example-99.2-r7.apk', 'riscv64')
            self.assertEqual(record['version'], '99.2-r7')
            self.assertEqual(record['repository'], 'community')
            self.assertEqual(record['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
            with self.assertRaises(ValueError):
                package_record(path, BASE+'community/riscv64/example-1-r0.apk', 'riscv64')

    def test_source_alias_is_not_assumed_to_contain_version(self):
        digest = 'a'*128
        manifest = source_manifest(
            'libepoxy-pkgver.tar.gz::https://example.test/releases/1.5.10.tar.gz patch.diff',
            digest+' libepoxy-pkgver.tar.gz\n'+digest+' patch.diff')
        self.assertEqual(manifest, [
            (digest, 'libepoxy-pkgver.tar.gz', 'https://example.test/releases/1.5.10.tar.gz'),
            (digest, 'patch.diff', None)])

    def test_plain_url_and_unsafe_checksums(self):
        self.assertEqual(source_manifest('https://example.test/source.tar.gz', 'a'*128+' source.tar.gz'),
                         [('a'*128, 'source.tar.gz', 'https://example.test/source.tar.gz')])
        for manifest in ('bad file', 'a'*128+' ../file', 'a'*128+' file trailing'):
            with self.assertRaises(ValueError):
                source_manifest('', manifest)

    def test_missing_mirror_uses_recipe_url_and_verifies_bytes(self):
        content = b'exact corresponding source'
        digest = hashlib.sha512(content).hexdigest()
        upstream = 'https://example.test/releases/new.tar.gz'
        def download(url, path):
            if url != upstream:
                raise urllib.error.HTTPError(url, 404, 'gone', {}, None)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            return path
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(bundle, 'discover_source_mirrors', return_value=[]), patch.object(bundle, 'CACHE', pathlib.Path(tmp)), patch.object(bundle, 'download', side_effect=download):
                self.assertEqual(bundle.source_archive('alias.tar.gz', digest, upstream).read_bytes(), content)
                with self.assertRaises(ValueError):
                    bundle.source_archive('alias.tar.gz', '0'*128, upstream)


class SourceDownloadTests(unittest.TestCase):
    def setUp(self):
        mirrors = patch.object(bundle, 'discover_source_mirrors', return_value=[])
        mirrors.start()
        self.addCleanup(mirrors.stop)

    def test_discovered_mirror_precedes_unavailable_upstream(self):
        content = b'exact old source'
        checksum = hashlib.sha512(content).hexdigest()
        mirror = bundle.DISTFILES + 'v99.1/source.tar.gz'
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle, 'CACHE', pathlib.Path(tmp)):
            with patch.object(bundle, 'discover_source_mirrors', return_value=['v99.1/']), patch.object(
                    bundle.urllib.request, 'urlopen', side_effect=[
                        urllib.error.HTTPError('edge', 404, 'gone', {}, None), self.response(content)]) as request:
                self.assertEqual(bundle.source_archive(
                    'source.tar.gz', checksum, 'https://unavailable.test/source').read_bytes(), content)
                self.assertEqual(request.call_count, 2)
                self.assertEqual(request.call_args.args[0], mirror)

    def test_mirror_index_selects_newest_numeric_branches_and_rejects_other_links(self):
        index = ('<a href="v3.9/">old</a><a href="v3.25/">new</a>'
                 '<a href="v99.1/">future</a><a href="v3.25/">duplicate</a>'
                 '<a href="https://untrusted.test/v100.0/">external</a>'
                 '<a href="../">parent</a><a href="edge/">edge</a>'
                 '<a href="v100.0/../../escape/">unsafe</a>')
        self.assertEqual(bundle.parse_source_mirrors(index), ['v99.1/', 'v3.25/', 'v3.9/'])

    @staticmethod
    def response(content, length=None):
        response = io.BytesIO(content)
        response.headers = {'Content-Length': str(len(content) if length is None else length)}
        return response

    def test_disconnect_is_retried_then_complete_download_is_cached(self):
        content = b'complete source'
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle.time, 'sleep') as sleep:
            target = pathlib.Path(tmp)/'source.tar.gz'
            with patch.object(bundle.urllib.request, 'urlopen', side_effect=[
                    http.client.RemoteDisconnected('connection closed'), self.response(content)]) as request:
                self.assertEqual(bundle.download('https://example.test/source', target).read_bytes(), content)
                bundle.download('https://example.test/source', target)
                self.assertEqual(request.call_count, 2)
            sleep.assert_called_once_with(2)
            self.assertFalse(target.with_name(target.name + '.partial').exists())

    def test_truncated_body_restarts_without_caching_partial_bytes(self):
        content = b'complete source'
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle.time, 'sleep'):
            target = pathlib.Path(tmp)/'source.tar.gz'
            with patch.object(bundle.urllib.request, 'urlopen', side_effect=[
                    self.response(b'partial', 100), self.response(content)]) as request:
                self.assertEqual(bundle.download('https://example.test/source', target).read_bytes(), content)
                self.assertEqual(request.call_count, 2)
            self.assertFalse(target.with_name(target.name + '.partial').exists())

    def test_exhausted_mirror_retries_use_upstream_and_verify_checksum(self):
        content = b'exact corresponding source'
        checksum = hashlib.sha512(content).hexdigest()
        upstream = 'https://example.test/source'
        errors = [http.client.RemoteDisconnected('closed') for _ in range(4)]
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle.time, 'sleep') as sleep:
            with patch.object(bundle, 'CACHE', pathlib.Path(tmp)), patch.object(
                    bundle.urllib.request, 'urlopen', side_effect=errors + [self.response(content)]) as request:
                self.assertEqual(bundle.source_archive('source.tar.gz', checksum, upstream).read_bytes(), content)
                self.assertEqual(request.call_count, 5)
                self.assertEqual(request.call_args.args[0], upstream)
                self.assertEqual([call.args[0] for call in sleep.call_args_list], [2, 4, 8])

    def test_all_endpoints_failing_is_fatal_and_leaves_no_cached_file(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle.time, 'sleep'):
            with patch.object(bundle, 'CACHE', pathlib.Path(tmp)), patch.object(
                    bundle.urllib.request, 'urlopen', side_effect=TimeoutError('timed out')) as request:
                with self.assertRaisesRegex(FileNotFoundError, 'source.tar.gz.*example.test'):
                    bundle.source_archive('source.tar.gz', 'a'*128, 'https://example.test/source')
                self.assertEqual(request.call_count, 8)
                self.assertEqual([p for p in pathlib.Path(tmp).rglob('*') if p.is_file()], [])

    def test_http_503_is_retried(self):
        url = 'https://example.test/source'
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle.time, 'sleep'):
            with patch.object(bundle.urllib.request, 'urlopen', side_effect=[
                    urllib.error.HTTPError(url, 503, 'unavailable', {}, None), self.response(b'ok')]) as request:
                self.assertEqual(bundle.download(url, pathlib.Path(tmp)/'source').read_bytes(), b'ok')
                self.assertEqual(request.call_count, 2)

    def test_permanent_failures_are_not_retried(self):
        url = 'https://example.test/source'
        errors = [
            urllib.error.HTTPError(url, 404, 'gone', {}, None),
            urllib.error.URLError(ssl.SSLCertVerificationError('invalid certificate')),
            PermissionError('not writable'),
        ]
        for error in errors:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as tmp:
                with patch.object(bundle.time, 'sleep') as sleep, patch.object(
                        bundle.urllib.request, 'urlopen', side_effect=error) as request:
                    with self.assertRaises(type(error)):
                        bundle.download(url, pathlib.Path(tmp)/'source')
                    request.assert_called_once()
                    sleep.assert_not_called()

    def test_bad_checksum_is_fatal_without_trying_another_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(bundle, 'CACHE', pathlib.Path(tmp)):
            with patch.object(bundle.urllib.request, 'urlopen', return_value=self.response(b'wrong')) as request:
                with self.assertRaisesRegex(ValueError, 'Source checksum mismatch'):
                    bundle.source_archive('source.tar.gz', 'a'*128, 'https://example.test/source')
                request.assert_called_once()
                self.assertEqual([p for p in pathlib.Path(tmp).rglob('*') if p.is_file()], [])


if __name__ == '__main__':
    unittest.main()
