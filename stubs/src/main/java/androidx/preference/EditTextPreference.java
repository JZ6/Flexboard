package androidx.preference;

/**
 * Compile-time stub of the port's EditTextPreference — the text rows of the settings screen.
 *
 * <p>One member matters: {@code i(String)} is the ported {@code setText} — public final on
 * Gboard 18.0.3; it stores the value into the row's field, persists it through the datastore
 * bridge into Gboard's own store, propagates dependency state and notifies the row. That is the
 * lane the stock editor dialog writes through, and the one a blob import must also use:
 * {@code Hotkeys.applyBlob} writes the SharedPreferences file directly, which leaves the store's
 * in-memory view stale — imports changed the toolbar but not the rows. ({@code ae(String)},
 * persistString, is {@code protected} — that is exactly why this seam is {@code i} and not a
 * direct persist; ask preflight before trusting either's visibility.)
 */
public class EditTextPreference extends Preference {

    /** setText — store + notify, through the row's own persistence lane. */
    public void i(String text) {
        // stub
    }
}
