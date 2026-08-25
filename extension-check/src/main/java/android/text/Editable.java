package android.text;

/**
 * Compile-time shape only — the full interface extends CharSequence et al.; the import box only
 * ever calls toString().
 */
public interface Editable extends CharSequence {
}
