# Gboard's glide typing setting

> **Written against Gboard 17.7.7.** The bundle now targets `18.0.3.954559732-release-arm64-v8a`,
> and the disassembly listings below — obfuscated names *and* instruction offsets — are as they
> stood on 17.7.7. Every mechanism Flexboard actually depends on was re-checked against 18 and
> still holds, which is what the patches assert at patch time; the surrounding listings were not
> re-derived, because rewriting them would mean asserting a line-by-line correspondence nobody
> verified. Read them for the reasoning, and take current names from
> [`gboard-bindings.md`](gboard-bindings.md) and offsets from the APK.

Flexboard turns glide typing off for as long as Swipe to Delete is applied. It does not put it
back. This is the record of what that preference is on 17.7.7, how those facts were established,
and — because two earlier attempts failed — which approaches do not work.

## Why it matters

A leftward drag across the letters is a glide-typing input. If both are live, a swipe-delete
either steals a glided word or a glide swallows a delete. There is no way to tell the two apart
from the pointer stream — the finger does the same thing.

## The facts, on 17.7.7.932364120

| | |
|---|---|
| Preference key | `enable_gesture_input` |
| Resource id | `0x7f14097b` |
| Store instance | `Lpnp;->N(Landroid/content/Context;)Lpnp;` — public static, process singleton |
| Read | `Lpnp;->at(I)Z` — which is just `return Lcbv;->x(id, false)` |
| Write | `Lpnp;->aa(ILjava/lang/Object;)V` |
| Key resolution | `Lpnj;->a(I)` — `PreferenceKeyCache`, resource id to key name |

All three of `N`, `at` and `aa` are `public` on a `public final` class, so reflection reaches them
without `setAccessible`.

Neighbouring ids, recorded because they are easy to grab by mistake:

| | |
|---|---|
| `enable_gesture_auto_commit` | `0x7f140979` |
| `pref_gesture_preview_trail` | `0x7f1409ca` — the trail, not the feature |
| `settings_header_gesture` | `0x7f140ac2` — the settings **header**, not the checkbox |

## Two corrections

An earlier version of this document stated that the value "now lives in ProtoDataStore" and that
"no setter has been found", and Flexboard was built around working past both. Both are wrong.

**It is plain SharedPreferences.** `Lpnp;->at(I)Z` is one instruction of substance:

```
0: const/4        v0, #0
1: invoke-virtual {v1, v2, v0}, Lcbv;->x(IZ)Z
```

**There is a setter.** `Lpnp;->aa(ILjava/lang/Object;)V` resolves the id to a key and commits:

```
2:  invoke-interface {v0}, Lpng;->b()Landroid/content/SharedPreferences$Editor;
8:  invoke-virtual   {v1, v2}, Lpnj;->a(I)Ljava/lang/String;
12: invoke-static    {v0, v1, v3}, Lpnp;->ae(Editor, String, Object)V
15: invoke-interface {v0}, Editor;->apply()V
```

`ae` dispatches on the boxed type — `instanceof Boolean` → `Editor.putBoolean` — and throws
`IllegalArgumentException` for anything it does not recognise. This is exactly the write Gboard's
own checkbox performs.

## How they were derived

Read-only inspection of the APK with Python; no apktool, aapt or adb.

1. **The key string.** Gboard's preference keys are themselves string resources. Resource *names*
   are stripped from the shipped build but resource *values* are not, so the literal key text
   survives in `resources.arsc`. Searching it for the historic candidates (`enable_gesture_input`,
   `gesture_input`, `glide_typing`, …) matches exactly one: `enable_gesture_input`.

2. **The resource id.** Parse `resources.arsc`: locate the string in the global string pool, then
   walk the package's `ResTable_type` chunks for the `string` type (`0x14`) and find the entry
   whose value points at that pool index. The id is
   `(packageId << 24) | (typeId << 16) | entryIndex`.

   Sanity-check the parser by resolving `settings_header_gesture` as well — it must come back
   `0x7f140ac2`, which was recorded independently from an apktool dump. If it does not, the parse
   is wrong and the other id cannot be trusted either.

3. **The accessors.** `Lpnp;` is found from any call site; its 80 methods are then read directly
   rather than guessed. It declares three `(I)Z` methods — `ar`, `at` and `ay` — and only a call
   site identifies which one carries this preference. `aa(ILjava/lang/Object;)V` is the only
   id-keyed writer.

   **Do not derive the accessor by searching for the id as a literal.** That was the original
   method and it is misleading: the bytes `7b 09 14 7f` appear in only two places, both CJK, and
   the reader that matters on a Latin keyboard receives the id in a register. There are 251 call
   sites of `at(I)Z` in total.

## Why intercepting the read did not work

The first working version hooked `Lpnp;->at(I)Z` at each `return` and substituted `false` for this
one id. On device it was correct at keyboard show, then glide typing came back mid-session and
stayed back until the keyboard was hidden and shown again.

The consumers cache, and each of them caches somewhere the override cannot reach:

- **Latin.** `libs/gestureui/AbstractGestureMotionEventHandler.d()` reads a gesture preference and
  stashes the result in a field. `d()` runs on keyboard show.
- **CJK.** `Lgmb;->c()` reads the preference and, if set, builds an `HmmGestureDecoder` into
  `Lgmb;->a`. Nothing in `c()` tears an existing decoder down — only `b()` does, from `close()`.
  So an override that misses one activation leaves a live decoder for the rest of the session.

An override has to win at every point a consumer happens to read. A write has to win once.

Worth noting for anyone tempted by the same idea from the other direction: `Lgmb;` is CJK only —
`Lhda;` is Korean, `Lhvy;` is Pinyin, and the four callers of `Lgmb;->h(Lnbj;)Z` are the Pinyin,
Zhuyin and Korean decode processors. It is not on the English glide path.

## What Flexboard does

> This section described a `GlideTypingRuntime` class reaching `Lpnp;` by reflection, recording the
> previous value and restoring it, and reporting failures under a Diagnostics screen. None of that
> exists — there is no such class, no reflection on this path and no Diagnostics screen anywhere in
> the tree. It documented a design that was never built, or was replaced before release.

`forceScrubPreferencesPatch` inserts a call at Gboard's Application start, and
`GboardSettings.forceScrubPreferences` does the write through Gboard's own preference store. It is
a bytecode patch, not reflection.

Both writes are unconditional. They used to be gated on a `flexboard_enabled` preference, so that
turning Flexboard off stopped it re-forcing glide typing at every launch; that switch is gone, so
the writes now happen on every start for as long as the patch is applied. There is no record of the
previous value and no restore.

The consequence is worth stating plainly, because it is user-visible: glide typing cannot be turned
back on while Swipe to Delete is applied, and re-enabling it means re-patching without that patch.
Removing Flexboard leaves glide typing off, and the user has to tick it back on themselves.

## When Gboard updates

`COMPATIBILITY_GBOARD` records the build the bindings were derived against, but it does not gate
anything: `Patcher` never reads `compatiblePackages`, so a user on another build is warned by the
host at most. The resource ids this write depends on are checked by `tools/apk/preflight.py`
instead, and that runs locally rather than in CI. There is no runtime verification and no
Diagnostics screen to report to — a stale id would write some other preference.
