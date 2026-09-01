package dev.jz6.flexboard.patches.shared

/**
 * Which sections of Flexboard's settings screen to include, decided at patch time.
 *
 * A feature patch registers here in its `execute` block when it is selected (and only then);
 * `settingsScreenPatch` writes the XML with only those sections in its `finalize`, which always
 * runs after every `execute` has completed — the two-phase contract of Morphe's patcher. The
 * set is cleared by `settingsScreenPatch.execute`, so a JVM shared across runs starts fresh each
 * time; a patch that isn't ticked never executes, so never registers.
 *
 * Rows for unregistered sections are dropped by a sentinel-comment pass over the template
 * before `writePatchResource` writes it. `check_shared_constants.py` parses the template and
 * is untouched — the rows here are the template's, not the output's.
 */
internal enum class SettingsSection {
    SWIPE_TO_DELETE,
    HOTKEYS,
}

internal val selectedSettingsSections = mutableSetOf<SettingsSection>()
