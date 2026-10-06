"""Tests for the bundle freshness check.

The thing being defended against is a lane that passes for the wrong reason, so most of these build
a bundle that is wrong in one specific way and assert that exactly that reason comes back. A check
that says "stale" for everything is as useless as one that says "fresh" for everything.
"""

import unittest
import zipfile

import support  # noqa: F401  -- puts tools/ on the path

import bundle_freshness


def write_bundle(path, version="2.5.0-dev.3", timestamp=1_789_699_652_610,
                 manifest=None, source="a" * 40):
    """A zip carrying just enough manifest to be read as a bundle."""
    if manifest is None:
        manifest = "Manifest-Version: 1.0\nName: Flexboard\n"
        if version is not None:
            manifest += f"Version: {version}\n"
        if timestamp is not None:
            manifest += f"Timestamp: {timestamp}\n"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        if source is not None:
            z.writestr("flexboard_source_commit.txt", source + "\n")
    return str(path)


class ManifestReading(unittest.TestCase):
    def setUp(self):
        temp = __import__("tempfile").TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name

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
        with open(p, "w") as out:
            out.write("plain text")
        self.assertEqual(bundle_freshness.manifest_fields(p), (None, None))

    def test_a_zip_with_no_manifest(self):
        p = self.path("empty.mpp")
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("patches/x.class", "")
        self.assertEqual(bundle_freshness.manifest_fields(p), (None, None))

    def test_a_missing_file(self):
        self.assertEqual(bundle_freshness.manifest_fields(self.path("nope.mpp")), (None, None))


class GitParsing(unittest.TestCase):
    """The real `_git` helpers, against this repository rather than a stub.

    Everything in `Reasons` stubs these out, which left the one genuinely subtle piece of parsing in
    the file -- porcelain's leading space, which `.strip()` eats -- covered by a comment and nothing
    else. That bug was real: the first version reported `atches/src/...`.
    """

    def test_dirty_sources_keeps_the_whole_path(self):
        original = bundle_freshness._git
        try:
            bundle_freshness._git = lambda *args: "M patches/src/a.kt\n?? patches/src/b.kt\nMM patches/src/c.kt"
            self.assertEqual(bundle_freshness.dirty_sources(),
                             ["patches/src/a.kt", "patches/src/b.kt", "patches/src/c.kt"])
        finally:
            bundle_freshness._git = original

    def test_git_raises_rather_than_returning_empty(self):
        with self.assertRaises(bundle_freshness.GitUnavailable):
            bundle_freshness._git("log", "--no-such-flag")

    def test_the_real_git_tree_compares_equal_to_itself(self):
        sha = bundle_freshness._git("rev-parse", "HEAD")
        self.assertEqual(bundle_freshness.source_changes(sha), [])

    def test_invalid_source_commit_does_not_turn_into_fresh(self):
        with self.assertRaises(bundle_freshness.GitUnavailable):
            bundle_freshness.source_changes("A" * 40)


class Reasons(unittest.TestCase):
    """`reasons()` with git and gradle.properties stubbed, so the answers are deterministic."""

    def setUp(self):
        temp = __import__("tempfile").TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name
        self._saved = (bundle_freshness.tree_version,
                       bundle_freshness.source_changes,
                       bundle_freshness.dirty_sources)
        self.stub(version="2.5.0-dev.3", moved=[], dirty=[])

    def tearDown(self):
        (bundle_freshness.tree_version,
         bundle_freshness.source_changes,
         bundle_freshness.dirty_sources) = self._saved

    def stub(self, version, moved, dirty):
        bundle_freshness.tree_version = lambda: version
        bundle_freshness.source_changes = lambda commit: moved
        bundle_freshness.dirty_sources = lambda: dirty

    def bundle(self, **kw):
        return write_bundle(f"{self.dir}/b.mpp", **kw)

    def test_current_bundle_has_no_reasons(self):
        self.assertEqual(bundle_freshness.reasons(self.bundle()), [])

    def test_version_mismatch(self):
        found = bundle_freshness.reasons(self.bundle(version="2.5.0-dev.2"))
        self.assertEqual(len(found), 1)
        self.assertIn("gradle.properties says 2.5.0-dev.3", found[0])

    def test_sources_changed_even_if_the_bundle_finished_later(self):
        # Build of A may finish after B was committed. Timestamp doesn't prove it includes B.
        self.stub("2.5.0-dev.3", ["patches/src/b.kt"], [])
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("patches/src/b.kt", found[0])

    def test_a_commit_before_the_build_is_fine(self):
        self.stub("2.5.0-dev.3", [], [])
        self.assertEqual(bundle_freshness.reasons(self.bundle()), [])

    def test_uncommitted_sources(self):
        self.stub("2.5.0-dev.3", [], ["patches/src/a.kt"])
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("patches/src/a.kt", found[0])

    def test_many_dirty_files_are_summarised(self):
        self.stub("2.5.0-dev.3", [], [f"patches/src/{i}.kt" for i in range(9)])
        self.assertIn("and more", bundle_freshness.reasons(self.bundle())[0])

    def test_reasons_accumulate(self):
        self.stub("2.4.0", ["patches/src/b.kt"], ["patches/src/a.kt"])
        self.assertEqual(len(bundle_freshness.reasons(self.bundle())), 3)

    def test_a_missing_timestamp_is_fine_with_an_exact_source_stamp(self):
        found = bundle_freshness.reasons(self.bundle(timestamp=None))
        self.assertEqual(found, [])

    def test_a_bundle_without_a_source_commit_is_not_accepted_on_a_timing_guess(self):
        found = bundle_freshness.reasons(self.bundle(source=None))
        self.assertEqual(len(found), 1)
        self.assertIn("no source commit stamp", found[0])

    def test_an_unreadable_bundle_short_circuits(self):
        p = f"{self.dir}/x.mpp"
        with open(p, "w") as out:
            out.write("nope")
        found = bundle_freshness.reasons(p)
        self.assertEqual(len(found), 1)
        self.assertIn("may not be a patch bundle", found[0])

    def test_git_failing_is_a_reason_not_a_silence(self):
        # This test used to assert the opposite -- that a git answer of "nothing" meant "fresh" --
        # which turned an accidental degradation into a documented contract. A renamed path in
        # SOURCE_PATHS, a shallow clone or a missing git all produce that answer, and all three
        # would have made this script report a stale bundle as current.
        def boom():
            raise bundle_freshness.GitUnavailable("no such path")

        bundle_freshness.source_changes = lambda commit: boom()
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("could not say", found[0])

    def test_dirty_check_failing_is_also_a_reason(self):
        def boom():
            raise bundle_freshness.GitUnavailable("not a repository")

        bundle_freshness.dirty_sources = boom
        self.assertIn("could not say", bundle_freshness.reasons(self.bundle())[0])

    def test_a_missing_version_key_is_a_reason(self):
        bundle_freshness.tree_version = lambda: None
        found = bundle_freshness.reasons(self.bundle())
        self.assertEqual(len(found), 1)
        self.assertIn("no version", found[0])


if __name__ == "__main__":
    unittest.main()
