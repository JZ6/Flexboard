# Gboard 17.7.7 bindings

Every obfuscated name this project depends on, what it is, and how it was identified. These are
facts about **one build** — `17.7.7.932364120-release-arm64-v8a` — and `COMPATIBILITY_GBOARD`
pins the bundle to it precisely so a newer Gboard is refused rather than mispatched.

Kept as a document as well as in `GboardBindings.kt` because the derivation is the expensive part
and does not survive in code.

## Touch and dispatch

| Binding | Signature | What it is |
|---|---|---|
| Pointer tracker | `Lpbl;` | Owns a finger. Where the gesture is observed. |
| — movement | `Lpbl;->B(Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;FFJI)V` | `p1` is the soft key under the finger, **`p2`/`p3` are pointer x/y**. `registers=21`. |
| — cancel | `Lpbl;->s(J)V` | Pointer cancelled. |
| — reset | `Lpbl;->C()V` | Pointer state reset. |
| — anchor | `Lpbl;->ac()V` | Call site upstream uses as an insertion anchor inside the movement method. |
| Gesture dispatcher | `Lpbj;` | Turns a pointer into a key press. |
| — dispatch | `Lpbj;->f(Lpbl;Loth;Loud;Lowd;JZZIZJI)V` | `p1` is the tracker, which is what lets a veto match a press to the gesture that consumed it. |
| — delegate field | `Lpbj;->o:Lpbh;` | |
| — delegate call | `Lpbh;->o(Lpbl;Loth;Loud;Lowd;JZZIZJI)V` | What the stock dispatch forwards to. |
| Soft key view | `Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;` | Not obfuscated. |

Note the frame of `Lpbl;->B` — 21 registers, 7 parameter words — is the one that makes
`p2` resolve to `v16`. See [`register-encoding.md`](register-encoding.md).

## Preferences

| Binding | Signature | What it is |
|---|---|---|
| Store | `Lpnp;` | `public final`, extends `Lcbv;`. Process-wide singleton. |
| — instance | `Lpnp;->N(Landroid/content/Context;)Lpnp;` | `public static`. Builds from `getApplicationContext()`. |
| — read boolean | `Lpnp;->at(I)Z` | By resource id. Body is `return Lcbv;->x(id, false)`. |
| — write any | `Lpnp;->aa(ILjava/lang/Object;)V` | Resolves the id to a key, dispatches on the boxed type, commits with `Editor.apply()`. |
| — raw prefs | `Lpnp;->I()Landroid/content/SharedPreferences;` | The live instance, so listeners can be registered on it. |
| Key cache | `Lpnj;->a(I)Ljava/lang/String;` | Resource id to preference key name. Identified by its own log strings: `PreferenceKeyCache`, `Failed to get key name from id %d:`. |

`Lpnp;` declares **three** `(I)Z` methods — `ar`, `at`, `ay`. Only a call site identifies which
carries a given preference; do not guess.

Full derivation of the glide preference id and why the write path is used:
[`glide-detection.md`](glide-detection.md).

## Gesture and decoding

| Binding | What it is |
|---|---|
| `Lgmb;` | HMM gesture decoder holder. **CJK only** — subclasses are `Lhda;` (Korean), `Lhvy;` (Pinyin Qwerty/T9), `Ljqf;`, `Ljqk;`. Not on the English glide path. |
| `Lgmb;->c()V` | Reads gesture preferences and builds the `HmmGestureDecoder` into `Lgmb;->a`. Runs on keyboard show. Never tears an existing decoder down; only `b()` does. |
| `Lgmb;->h(Lnbj;)Z` | Per-event handler. Called only from the Pinyin, Zhuyin and Korean decode processors. |
| `…/libs/gestureui/AbstractGestureMotionEventHandler` | The **Latin** glide handler. Not obfuscated. `g(Landroid/view/MotionEvent;)V` is the live path, 1453 instructions. |
| `…/motioneventhandler/scrubmove/ScrubMotionEventHandler` | Generic scrub engine. Not obfuscated. |
| `Lpbv;` | Config struct for the scrub engine, `<init>(IZIIIIII)V`. First argument is an Android keycode. |
| `Lpax;->a:Lnea;` | Phenotype flag consulted alongside the preference in both `Lgmb;->c()` and `AbstractGestureMotionEventHandler.d()`. `Lnea;->g()` returns a boxed value. |

See [`motion-event-handlers.md`](motion-event-handlers.md) for how handlers are attached and what
the scrub engine does.

## How to re-derive these

Tools and worked examples are in [`../tools/apk/`](../tools/apk/README.md).

1. **Resolve by exact signature, never by name.** `GboardMethodTarget.resolve` matches defining
   class, name, parameter types and return type, and throws `Could not find <reference>` on a
   miss. That is deliberate: a partial match on a renamed build is worse than a hard failure.
2. **Find a method by its call site, not its name.** Obfuscated names carry no meaning and
   sibling methods share signatures. What identifies `at` among `ar`/`at`/`ay` is that
   `Lgmb;->c()V` passes the glide resource id to it.
3. **Use unobfuscated neighbours as anchors.** Framework classes, `com.google.android.libraries.*`
   names, and log strings survive minification and are the fastest way into an unfamiliar area.
4. **Assert frames, do not adapt to them.** `requireRegisterCount` turns a Gboard change into a
   loud patch-time failure instead of a register that silently means something else.
