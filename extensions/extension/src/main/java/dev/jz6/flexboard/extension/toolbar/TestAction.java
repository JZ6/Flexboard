package dev.jz6.flexboard.extension.toolbar;

import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * Types a literal {@code "test"} into the editor when tapped.
 *
 * <p>Exists only as the payload for the <b>Toolbar Native Test</b> patch, which registers a
 * toolbar button through Gboard's own access-point registry (the {@code ArrayMap} on the bar
 * controller, populated via {@code Lmlh;->g}) instead of splicing it into the split method's
 * list. The point of the patch is to prove out persistence and drag-placeability through
 * Gboard's customize UI, not to build a generally useful action — so the action here is the
 * cheapest possible write that is visibly different from "did nothing".
 *
 * <p>Live wires into Gboard: none. Gboard hands this object to the access point's builder
 * ({@code mhx.c()} → {@code builder.q(Runnable)}), and when the button is tapped its
 * {@code ACCESS_POINT_ACTION} dispatch calls {@link #run()}. The input connection comes from
 * the published {@link ImeService}, so no Gboard internals are named from Java.
 */
public final class TestAction implements Runnable {

    /** Required by the patch, which emits {@code new-instance} plus this constructor. */
    public TestAction() {
    }

    @Override
    public void run() {
        InputConnection connection = ImeService.connection();
        if (connection == null) {
            return;
        }
        connection.commitText("test", 1);
    }
}
