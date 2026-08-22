package dev.jz6.flexboard.extension.settings;

import android.content.Context;

import com.google.android.libraries.inputmethod.preferencewidgets.CommonPreferenceFragment;

/**
 * One settings screen per hotkey slot, all sharing one implementation.
 *
 * <p>Gboard's settings host instantiates fragments by class-name lookup with a public no-arg
 * constructor — that's why each slot is its own tiny {@code public static final} subclass of
 * {@link SlotFragment}. The subclasses carry nothing but their name; the name is the slot. The
 * smali side of the toolbar patch reads the same slot ordering, and the generated
 * {@code res/xml/flexboard_hotkey_N.xml} for a slot matches its row name on the main screen one
 * for one.
 *
 * <p>Being subclasses of {@code CommonPreferenceFragment} buys a whole screen's worth of
 * platform behaviour for free: the datastore bridge installs itself, the app bar and back stack
 * are Gboard's, and the rows inherit Gboard's own widget styling.
 */
public final class FlexboardHotkeys {

    private FlexboardHotkeys() {}

    private static final String SLOT_NAME_PREFIX = "Slot";

    /** The per-slot screen body: chooses the XML its own name calls for. */
    public static class SlotFragment extends CommonPreferenceFragment {

        public SlotFragment() {}

        /**
         * The slot's resource id. Returning 0 shows a blank screen rather than crash — the
         * deliberate failure mode when the process has no Context to give (no service published,
         * and the framework reflection refused).
         */
        @Override
        public int aB() {
            String simple = getClass().getSimpleName();
            if (!simple.startsWith(SLOT_NAME_PREFIX)) {
                return 0;
            }
            int slot;
            try {
                slot = Integer.parseInt(simple.substring(SLOT_NAME_PREFIX.length()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
            Context context = SettingsScreens.processContext();
            return SettingsScreens.xmlId(context, "flexboard_hotkey_" + slot);
        }
    }

    public static final class Slot1 extends SlotFragment { public Slot1() {} }
    public static final class Slot2 extends SlotFragment { public Slot2() {} }
    public static final class Slot3 extends SlotFragment { public Slot3() {} }
    public static final class Slot4 extends SlotFragment { public Slot4() {} }
    public static final class Slot5 extends SlotFragment { public Slot5() {} }
    public static final class Slot6 extends SlotFragment { public Slot6() {} }
    public static final class Slot7 extends SlotFragment { public Slot7() {} }
    public static final class Slot8 extends SlotFragment { public Slot8() {} }
    public static final class Slot9 extends SlotFragment { public Slot9() {} }
    public static final class Slot10 extends SlotFragment { public Slot10() {} }
    public static final class Slot11 extends SlotFragment { public Slot11() {} }
    public static final class Slot12 extends SlotFragment { public Slot12() {} }
}
