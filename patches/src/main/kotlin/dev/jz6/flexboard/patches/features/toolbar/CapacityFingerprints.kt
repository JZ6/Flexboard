package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.Fingerprint

/**
 * Survives R8 because a layout addresses it as a string, so it can be named outright — unlike
 * everything it talks to.
 */
internal const val ACCESS_POINTS_BAR =
    "Lcom/google/android/libraries/inputmethod/accesspoint/widget/AccessPointsBar;"

/** The flag name. A string, so R8 cannot rewrite it, which is why both edits anchor on it. */
internal const val MAX_ACCESS_POINTS_FLAG = "config_max_access_points"

/** The Phenotype flag factory the default is handed to. */
internal const val FLAG_FACTORY = "Lnxs;->e(Ljava/lang/String;JLjava/lang/String;)Lnxp;"

/** Reading a flag's current value. One call in the constructor: the capacity. */
internal const val FLAG_ACCESSOR = "Lnxp;->g()Ljava/lang/Object;"

/** Gboard's compiled-in default, meaning "unset". Asserted before it is overwritten. */
internal const val STOCK_FLAG_DEFAULT = -1L

/** Gboard's own upper bound on the flag. Asserted before it is overwritten. */
internal const val STOCK_CEILING = 8L

internal fun accessPointsBarStaticInitFingerprint() = Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<clinit>",
    parameters = emptyList(),
    returnType = "V",
)

internal fun accessPointsBarConstructorFingerprint() = Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V",
)
