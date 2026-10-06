# Parameter registers and invoke encoding

A patch that emits `invoke-static {p2, v0}, …` looks obviously correct and can still produce a
bundle that fails to apply, with an error that names nothing relevant. This is the record of that
failure, because the symptom gives no hint of the cause and it will happen again on any emitter
written from scratch.

> **Historical incident:** `SwipeDeletePointerPatch` and `installPointerHooks` below were removed.
> Current emitters may use `pN` in a 35c invoke **only when their asserted register frame proves**
> `registerCount - parameterWords + N <= 15`. For example, `SwipeUpEmitter` asserts 13 registers,
> two parameter words, so p0=v11 and p1=v12 fit. No global `fits35c` helper or textual ban exists.

## The symptom

```
app.morphe.patcher.patch.PatchException: The patch "Swipe Left to Delete" depends on
"BytecodePatch@…", which raised an exception:
Caused by: java.util.NoSuchElementException: Collection is empty.
    at …SwipeDeletePointerPatchKt.installPointerHooks(SwipeDeletePointerPatch.kt:62)
```

Nothing in that mentions registers, encoding, or the instruction that is actually wrong.

The reason it reads like this: when smali cannot *encode* an instruction — as opposed to failing
to parse it — the assembler rejects the method semantically but leaves
`parser.numberOfSyntaxErrors` at zero. The patcher sees a successful parse that produced a class
with **no methods**, and the next thing to touch it calls `classDef.methods.first()`. So an
encoding fault surfaces as an empty collection several frames away.

If you ever see `Collection is empty` out of a patch, suspect the bytecode you emitted, not the
code that crashed.

## The cause

`pN` is not a register. It names a **parameter slot**, and parameters sit at the top of the
frame:

```
pN  ==  registerCount - parameterWords + N
```

`parameterWords` counts `this` for a non-static method and two words for each `J` or `D`.

`invoke-kind` in format **35c** packs each register argument into a **4-bit nibble**, so it can
only address **v0–v15**. Format **3rc** — the `/range` form — takes a 16-bit start register
instead and has no such limit.

For `Lpbl;->B(Lcom/…/SoftKeyView;FFJI)V`, which the pointer hook targets:

| | |
|---|---|
| `registerCount` | 21 |
| `parameterWords` | 7 — `this`, SoftKeyView, F, F, J (two), I |
| so `p2` | `21 - 7 + 2` = **v16** |

One register past what 35c can encode. The emitter never saw `v16`; it saw the token `p2` and
assumed it was small.

## The fix

An emitter can resolve `pN` **after pinning the target method's frame**. The conceptual old
`fits35c` rule was:

```kotlin
val fits35c = words.size <= MAX_35C_WORDS && words.all { word ->
    word.prefix != 'p' && (word.index == null || word.index <= MAX_35C_REGISTER)
}
```

The shown `fits35c` function is historical and does not exist in this repo. **35c and 3rc are both
three code units**; `/range` is useful when the arguments are consecutive and their absolute
register numbers are above v15. A proven low `pN` also works in 35c.

Where a mixed expression is unavoidable — passing a parameter alongside a local, which cannot be
a contiguous range — resolve the parameter to a concrete `vN` at patch time instead, from a
register count the patch has asserted:

```kotlin
val registerCount = method.assertRegisterCount(13, "ScrubMotionEventHandler->g")
val receiver = registerCount - 2  // p0 == v11, encodable in 35c
```

and assert the frame rather than adapting to it, so a Gboard change fails loudly instead of
silently writing to the wrong register.

## Why an emitter can avoid this by accident

Two properties keep every emitted call inside 35c's range, and an emitter with both will never see
the fault:

- resolving `pN` to a concrete `vN` before emitting, through a register contract that records the
  mapping explicitly
- delegates that read coordinates from low locals `v0`/`v1` rather than from parameters

Here the margin was exactly one register. Writing an emitter without that contract is what exposed
it.

## Guarding it

There is no global rule banning `pN`: such a ban would reject safe existing emitters. Each emitter
pins its frame with `assertRegisterCount` before using a parameter in 35c, checks scratch ranges,
and the local driver assembles the bundle before installation. `verify.py` then checks the resulting
DEX; the driver is what catches an instruction the smali assembler cannot encode.
