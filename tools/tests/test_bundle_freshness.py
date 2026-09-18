"""Tests for the bundle freshness check.

The thing being defended against is a lane that passes for the wrong reason, so most of these build
a bundle that is wrong in one specific way and assert that exactly that reason comes back. A check
that says "stale" for everything is as useless as one that says "fresh" for everything.
"""

import unittest
import zipfile

import support  # noqa: F401  -- puts tools/ on the path

import bundle_freshness


def write_bundle(path, version="2.5.0-dev.3", timestamp=1_789_699_652_610, manifest=None):
    """A zip carrying just enough manifest to be read as a bundle."""
    if manifest is None:
        manifest = "Manifest-Version: 1.0\nName: Flexboard\n"
        if version is not None:
            manifest += f"Version: {version}\n"
        if timestamp is not None:
            manifest += f"Timestamp: {timestamp}\n"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
    return str(path)


class ManifestReading(unittest.TestCase):
    def setUp(self):
        self.dir = __import__("tempfile").mkdtemp()

    def path(self, name="b.mpp"):
        return f"{self.dir}/{name}"

    def test_reads_version_and_timestamp(self):
        b = write_bundle(self.path())
        self.assertEqual(bundle_freshness.manifest_fields(b), ("2.5.0-dev.3", 1_789_699_652))

    def test_timestamp_is_seconds_not_milliseconds(self):
        # The manifest writes epoch milliseconds; git speaks seconds. Comparing the two directly
        # makes every bundle look 56_000 years newer than every commit, and nothing is ever stale.
        _, stamp = bundle_freshness.manifest_fields(write_bundle(self.path()))
        self.assertLess(stamp, 4_000_000_000)

    def test_unwraps_a_continuation_line(self):
        # Manifests wrap at 72 bytes with a leading space, which can land mid-version.
        manifest = "Manifest-Version: 1.0\nVersion: 2.5.0-de\n v.3\nTimestamp: 1000\n"
        b = write_bundle(self.path(), manifest=manifest)
        self.assertEqual(bundle_freshness.manifest_fields(b)[0], "2.5.0-dev.3")

    def test_a_file_that_is_not_a_zip(self):
        p = self.path("not.mpp")
        open(p, "w").write("plain text")
        self.assertEqual(bundle_freshness.manifest_fields(p), (None, None))

    def test_a_zip_with_no_manifest(self):
        p = self.path("empty.mpp")
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("patches/x.class", "")
        self.assertEqual(bundle_freshness.manifest_fields(p), (None, None))

    def test_a_missing_file(self):
        self.assertEqual(bundle_freshness.manifest_fields(self.path("nope.mpp")), (None, None))


class Reasons(unittest.TestCase):
    """`reasons()` with git and gradle.properties stubbed, so the answers are deterministic."""

    def setUp(self):
        self.dir = __import__("tempfile").mkdtemp()
        self._saved = (bundle_freshness.tree_version,
                       bundle_freshness.last_source_commit,
                       bundle_freshness.dirty_sources)
        self.stub(version="2.5.0-dev.3", commit=(1_789_699_000, "some commit"), dirty=[])

    def tearDown(self):
        (bundle_freshness.tree_version,
         bundle_freshness.last_source_commit,
         bundle_freshness.dirty_sources) = self._saved

    def stub(self, version, commit, dirty):
        bundle_freshness.tree_version = lambda: version
        bundle_freshness.last_source_commit = lambda: commit
        bundle_freshness.dirty_sources = lambda: dirty

    def bundle(self, **kw):
        return write_bundle(f"{self.dir}/b.mpp", **kw)

    def test_current_bundle_has_no_reasons(self):
        self.assertEqual(bundle_freshness.reasons(self.bundle()), [])

    def test_version_mismatch(self):
        found = bundle_freshness.reasons(self.bundle(version="2.5.0-dev.2"))
        self.assertEqual(len(found), 1)
        self.assertIn("gradle.properties says 2.5.0-dev.3", found[0])

    def test_sources_committed_after_the_build(self):
        # The hole the version check alone leaves: both say dev.3, built an hour apart.
        self.stub("2.5.0-dev.3", (1_789_699_652 + 3600, "fix: weaken the guard"), [])
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("moved 60 minute(s) after", found[0])
        self.assertIn("weaken the guard", found[0])

    def test_a_commit_before_the_build_is_fine(self):
        self.stub("2.5.0-dev.3", (1_789_699_652 - 3600, "earlier"), [])
        self.assertEqual(bundle_freshness.reasons(self.bundle()), [])

    def test_uncommitted_sources(self):
        self.stub("2.5.0-dev.3", (1_789_699_000, "c"), ["patches/src/a.kt"])
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("patches/src/a.kt", found[0])

    def test_many_dirty_files_are_summarised(self):
        self.stub("2.5.0-dev.3", (1_789_699_000, "c"), [f"patches/src/{i}.kt" for i in range(9)])
        self.assertIn("and more", bundle_freshness.reasons(self.bundle())[0])

    def test_reasons_accumulate(self):
        self.stub("2.4.0", (1_789_699_652 + 60, "later"), ["patches/src/a.kt"])
        self.assertEqual(len(bundle_freshness.reasons(self.bundle())), 3)

    def test_a_missing_timestamp_is_itself_a_reason(self):
        # Silently skipping the age comparison would leave the check nominal again.
        found = bundle_freshness.reasons(self.bundle(timestamp=None))
        self.assertEqual(len(found), 1)
        self.assertIn("no Timestamp", found[0])

    def test_an_unreadable_bundle_short_circuits(self):
        p = f"{self.dir}/x.mpp"
        open(p, "w").write("nope")
        found = bundle_freshness.reasons(p)
        self.assertEqual(len(found), 1)
        self.assertIn("may not be a patch bundle", found[0])

    def test_no_commits_touching_the_sources(self):
        self.stub("2.5.0-dev.3", (None, None), [])
        self.assertEqual(bundle_freshness.reasons(self.bundle()), [])


if __name__ == "__main__":
    unittest.main()
