# Phenotype flags — what forcing one on can and cannot do

Gboard ships most of its features behind Phenotype flags. A patched build never receives any of
them, so the compiled-in defaults are the whole story. That sounds like an opportunity — flip the
defaults, get the features — and for a small number of flags it is. For most it is not, and the
difference is not visible from the flag name.

This is the record of finding that out on 18.0.3, including the parts that went wrong.

## Why a patched build gets nothing

Phenotype registers configuration against a package name **and a signing identity**. A Morphe build
is resigned, so GMS never attributes Gboard's flags to it, the sync never lands, and every flag
holds whatever value was compiled into the APK. There is no error and no setting; a feature that
Google enables server-side simply is not there, and the row that used to carry it is gone.

That applies to *all* flag types, which matters more than it first appears — see
[Configuration is delivered the same way](#configuration-is-delivered-the-same-way).

## The inventory

18.0.3 declares **884 boolean flags** through the boolean factory
(`Lnxs;->a(Ljava/lang/String;Z)Lnxp;`) in a `<clinit>`. **717 of them ship `false`.**

Of those 717:

| | count | meaning |
|---|---|---|
| provably inert | 46 | the flag object is stored in a field no instruction anywhere reads |
| companions look server-delivered | 16 | download groups, locale allowlists, version allowlists nearby |
| neither | 655 | nothing static to say about them |

`tools/apk/flagscan.py` produces this. Read its docstring before trusting any of it — the middle
row is a weak signal and is scored against known outcomes in the tool's own output.

The "provably inert" row is the only hard result. If nothing reads the field the flag object is
stored in, nothing can consume the value, so changing the default cannot change behaviour. The only
escape is reflection, which R8-shrunk Gboard does not use to reach its own flag fields.

## What we tested, and what happened

Seven flags were selected by hand for Hidden Features and shipped default-on in v2.3.0-dev.0.
**Gboard would not start.** Splitting them into seven independently selectable patches (dev.2)
turned the search from one release per bisection step into one install per step, and produced this:

| flag | outcome on a device |
|---|---|
| `enable_grammar_checker` | works |
| `enable_close_proactive_suggestions_access_point` | works |
| `enable_on_device_proofread` | **Gboard does not start at all** |
| `enable_emoji_kitchen_browse` | no effect seen |
| `enable_custom_sticker_tab` | no effect seen |
| `offline_translate` | no effect seen |
| `enable_settings_search` | no effect seen |

Two of seven. That ratio is the single most useful number in this document, because the seven were
not picked carelessly — each was chosen because Google ships the feature publicly, so the code
behind the flag was known to be finished.

The two that work were then recombined into one default-on patch and shipped as v2.3.0-dev.4,
which was confirmed on a device: the keyboard starts and both features are present. That is the loop
the incident was about. v2.3.0-dev.0 shipped default-on on the strength of the emission being
correct — which it was, and which turned out not to be the question. The same two flags are
default-on again, on the strength of having been watched working. Nothing else changed about how
they are applied.

"No effect seen" is deliberately weaker than "no effect". Each of those four surfaces somewhere a
tester has to navigate to — inside the emoji picker, in the sticker picker's tab strip, in the
translate bar with the network off, behind a search affordance in Gboard's settings. Not having
spotted one is not proof it is absent. They are kept, opt-in, so a retest costs a tick.

## The rule

The selection criterion — *Google ships this publicly, so the code is finished* — was satisfied by
all seven, including the one that bricked the keyboard. A test that everything passes is not a test.

The property that actually separates them is whether the flag is **self-contained**:

> A boolean that reveals finished **local** code is safe to force. A boolean that is the entry point
> to server-**configured** machinery is not, however public the feature, because forcing it on skips
> the configuration rather than supplying it.

The two that work are UI gates in front of code already sitting in the APK. The five that do not are
each in front of something that has to arrive from elsewhere:

- `enable_on_device_proofread` fronts the Writing Tools / SAPI stack — an on-device LLM reached
  through AICore (`ON_DEVICE_LLM_INFERENCE_PROOFREAD`), a downloaded model
  (`Proofreader.downloadFeature`), and a version gate comparing `sapi_proofreader_version` against
  `sapi_proofreader_allowed_versions`. Roughly 170 `writing_tools_*` parameters sit behind that one
  boolean. `Failed to create SAPI proofreader` is a string in the APK.
- `enable_emoji_kitchen_browse` needs Mobile Data Download groups
  (`emoji_kitchen_mdd_data_file_group`, `emoji_kitchen_scam_index_data_file_group`).
- `enable_custom_sticker_tab` is filtered by `custom_sticker_tab_locales`, an allowlist that arrives
  empty, so no locale ever qualifies.
- `offline_translate` needs downloaded language packs.
- `enable_settings_search` has no companion parameters in the dex at all, which is its own kind of
  answer: there is nothing here for it to switch on.

## Configuration is delivered the same way

The reason the crash was a crash rather than a shrug is worth stating separately.

Forcing a parent boolean does not disable the machinery behind it. It tells Gboard the feature is
available and lets it proceed to set the feature up — using configuration values that, on a resigned
build, are all still at their compiled defaults. So Gboard asked for an inference engine with an
empty version allowlist and no model, and died during startup rather than declining.

The generalisable form: **opening a gate is not the same as supplying what is behind it.** Where a
feature is genuinely wanted, the parent flag and its `config_*` companions have to move together —
which for proofread means a model-config identifier, model versions, language allowlists and a
backend selector, several of which are strings and longs rather than booleans. Those values cannot
be derived from the APK; they are observations from a provisioned device.

Two consequences:

1. `forceFlagsOn` is boolean-only and structurally cannot do this. Setting a group needs a typed
   override covering the long and string factories as well. `ToolbarCapacityPatch` already flips a
   long-valued flag (`config_max_access_points`) through its own `raiseFlagDefault`, so the same job
   is currently written twice in two shapes. That duplication is a refactor, **not a missing
   capability**, and reading it as one is what produced the wrong Rambler answer recorded below.
   Long and string flags can be written today by rewriting the literal, exactly as
   `raiseFlagDefault` does; what `forceFlagsOn` lacks is a *shared* way to do it.
2. A flag whose safety depends on what the *device* can provide should be a user choice with a
   conservative default, not a compile-time constant. There is no way to detect AICore provisioning
   from inside a patch.

## A worked mistake: Rambler

Asked to enable "rambler mode" from the roadmap, the answer came out as *no*. **The answer was
wrong**, and it is kept here in full because the way it was wrong is more instructive than the seven
earlier results. The gate analysis below is correct; the conclusion drawn from it was not.

**Rambler is agentic dictation** — `libs/agenticdictation/`, 51 classes, "Push to Ramble". It rewrites
speech into composed text. Nothing in the dex spells `rambler mode`; the marketing name and the
internal one (`jetson`) differ, which is why the first search for it has to be for the feature rather
than the phrase.

The eligibility check is `Lmev;->B(Landroid/content/Context;)Z`, and it requires all six of:

| | Condition | Ships as |
|---|---|---|
| 1 | `enable_agentic_dictation` | boolean `0` — forceable |
| 2 | `config_agentic_dictation` | boolean `1` — already on |
| 3 | `enable_jetson_in_toolbar` | boolean — forceable |
| 4 | `ModuleManager` reports `Lmql;` enabled | module registration |
| 5 | `Lmqk;->b(Context)` | reads the user preference `enable_jetson`, default off |
| 6 | `Lmqk;->c()` | **`ad_activation_type == 2`** |

Condition 6 ends it:

```smali
# Lmqk;->c()
sget-object v0, Lmql;->D:Lnxp;        # ad_activation_type
...
const/4 v2, #4
invoke-static {v2}, La;->ad(I)I       # 4 != 1, so 4 - 2 = 2
cmp-long v0, v0, v2
```

```smali
# Lmql;-><clinit>
const-string  v0, 'ad_activation_type'
const-wide/16 v1, #1
invoke-static {v0, v1, v2}, Lnxs;->c(Ljava/lang/String;J)Lnxp;
```

**The gate needs 2 and the flag ships 1**, so `c()` is false and `B()` is false no matter what any
boolean does.

### Why the conclusion was wrong

From that, the original write-up concluded: *it is also a `long`, which `forceFlagsOn` structurally
cannot write, so the feature is out of reach.* The first clause is true. The second does not follow,
and the counter-example was already in this repository:

```kotlin
// ToolbarCapacityPatch.raiseFlagDefault — a long-valued flag, rewritten in place
replaceInstruction(defaultIndex, "const-wide/16 v$register, 0x${TOOLBAR_CAPACITY.toString(16)}")
```

`forceFlagsOn` is boolean-only. Rewriting a `const-wide/16` literal is not `forceFlagsOn`, and this
project has done it since the toolbar work. The paragraph two sections above even says so — filed
there as a *tidiness* complaint about the same job being written twice, and read as one.

The error is worth naming precisely, because it is not a missing fact. Every fact needed was present
and correct. The step that failed was concluding **"the capability does not exist"** from **"the
helper I reached for does not have it"**. A second implementation was thirty lines away in a sibling
patch.

The fix is one instruction:

```kotlin
replaceInstruction(valueIndex, "const-wide/16 v$valueRegister, 0x2")
```

The server-configured argument was overstated too. The quota, consent and compliance machinery is
real, and a resigned build cannot obtain any of it — but a working implementation exists that routes
the flag *reads* through a runtime policy rather than trying to supply the configuration, which
sidesteps the problem entirely. "Cannot be configured" and "cannot be enabled" are different claims,
and only the first was ever established.

Everything behind the gate is the server-configured pattern in its clearest form:
`agentic_dictation_backend_type` (`..._BACKEND_TYPE_S3`), `..._max_server_retries`,
`..._quota_refresh_hour_pt`, `..._server_quota_drained_error_code` with an
`AgenticDictationQuotaDrainedDialog`, an `agenticdictation/compliance/` package, seventeen onboarding
strings including `AGENTIC_DICTATION_ONBOARDING_ACCEPTED`, and `agentic_dictation_excluded_language_tags`.
An activation mode, a quota and a consent record are three things a resigned build cannot obtain.

Condition 5 is the one part that *is* reachable: `enable_jetson` is an ordinary preference key, and
`GboardSettings` already writes Gboard preferences from the extension. It sits downstream of a gate
that is not reachable, which is the whole lesson in one line.

**A note on method.** The first pass at this reported zero dex strings containing `rambl`, because
the scan looped over `d.h['string_ids_size']` and `Dex` has no `h` attribute — so it iterated nothing
and returned a confident empty answer. The correct attribute is `str_n`; the real count is 56 across
120,537 strings. A search that finds nothing and a search that never ran look identical, which is the
same failure this document's own classifier section is about. The second thing found was that
`show_rambler_dict_settings` has **zero readers in the dex** — it had been proposed as the "safest
first probe" precisely because it sounded harmless, and it is inert.

## Trying to automate the rule, and failing

Since the self-contained rule is stated in terms of the dex, it looked automatable. It is not, at
least not by anything tried here, and the attempt is recorded because the failure is more useful
than another untested idea.

The first version scored 3 of 5 duds and cleared both working flags, which looked promising enough
to build on. **That result was an artifact.** The search token for each flag had been chosen by
hand, after the outcomes were already known: searching companions of `enable_grammar_checker` under
the token `grammar` finds nothing alarming, while the token a program would derive,
`grammar_checker`, finds `grammar_checker_manifest_uri` and condemns a flag that works. Same flag,
same dex, opposite verdict, decided by a parameter picked with hindsight.

Automated over all 717, scored against the seven known outcomes, the classifier gets **3 of 7**:

| | scan says | |
|---|---|---|
| `enable_close_proactive_...` (works) | no objection | correct |
| `enable_custom_sticker_tab` (inert) | gated | correct |
| `enable_emoji_kitchen_browse` (inert) | gated | correct |
| `enable_grammar_checker` (**works**) | gated | false alarm |
| `enable_on_device_proofread` (**fatal**) | no objection | **clears the one that bricks it** |
| `enable_settings_search` (inert) | no objection | miss |
| `offline_translate` (inert) | no objection | miss |

It catches 2 of the 5 duds, wrongly condemns 1 of the 2 that work, and clears the fatal one. A
shortlist of 655 is not a shortlist. This is not close to gate quality and it is not being used as
one.

A bug found along the way is worth keeping in mind for anything similar: the field descriptor from
the write side carried its `:Lnxp;` type suffix and the read side did not, so nothing matched and
**all 717 flags looked provably inert** — including the two known to work. The only reason that was
caught in seconds is that the tool prints its verdicts against known outcomes every run. Any
classifier built here should keep a scorecard of cases whose answers are already known, and print it
whether or not anyone asked.

## Mechanism: rewriting defaults versus hooking the read

Two ways to change what a flag reads as.

**Rewriting the compiled default** — what Flexboard does. `forceFlagsOn` finds the
`const-string` + `const/4` + factory triple in a `<clinit>` and flips the zero. Zero runtime cost,
nothing to maintain at execution time, and it is verifiable at patch time. Three limits: it is
boolean-only as written; it cannot touch a flag whose default is hoisted and shared without the
isolating emission; and it changes the *fallback*, so a Phenotype push would override it. (The last
is theoretical for a resigned build, which never receives a push — but it is the reason
`docs/toolbar-capacity.md` lists a read hook as the more robust option for the capacity flag.)

**Hooking the flag read** — intercepting the accessor and substituting a value. One insertion covers
every flag regardless of type, shared defaults stop mattering because nothing in the `<clinit>` is
touched, and it beats a push. The costs are a lookup on a hot path, resolving which flag is being
read at runtime rather than at patch time, and an insertion point that is obfuscated and therefore
version-specific.

The read hook is the more capable design and the reason the flag list here is short. It has not been
built because the two flags that work do not need it.

## What this means in practice

- **Device-verify before default-on.** Nothing in CI can tell whether a forced flag works; there is
  no device in the pipeline and no log access from a released build. Every flag ships opt-in until
  it has been watched working.
- **One patch per flag while a flag set is unproven.** Bisecting a compiled-in list costs a release
  per step. Bisecting checkboxes costs an install per step. That change alone turned a four-release
  hunt into a three-install one.
- **Expect roughly two in seven.** Most flags shipping `false` are off for everyone — experiments,
  staged rollouts, dead code — and forcing one of those on enables an unfinished path rather than
  restoring a feature.
- **Do not chase proofread.** It needs configuration that cannot be derived from the APK, and no
  patch can detect whether a given phone has the AICore provisioning it depends on.
