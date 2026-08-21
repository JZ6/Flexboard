package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef

/** The running `InputMethodService` the extension holds onto for its toolbar actions. */
internal const val IME_SERVICE_CLASS = "Ldev/jz6/flexboard/extension/ime/ImeService;"

internal const val SET_SERVICE =
    "$IME_SERVICE_CLASS->setService(Landroid/inputmethodservice/InputMethodService;)V"

private const val INPUT_METHOD_SERVICE = "Landroid/inputmethodservice/InputMethodService;"

private const val ON_CREATE_REGISTER_COUNT = 12

/**
 * Hands the IME service to the extension at Gboard's `onCreate`.
 *
 * Derived as *the* class extending `android.inputmethodservice.InputMethodService`. There is
 * exactly one, and the assertion is what keeps that a fact — a second one appearing would mean
 * the buttons silently wire themselves to whichever came first.
 *
 * All of v0..v10 are dead at entry by backward liveness, so `p0` is read and nothing else is
 * touched.
 */
internal fun BytecodePatchContext.publishInputMethodService() {
    val services = mutableListOf<ClassDef>()
    classDefForEach { if (it.superclass == INPUT_METHOD_SERVICE) services += it }
    check(services.size == 1) {
        "Expected exactly one InputMethodService subclass, found ${services.size}: " +
            "${services.map { it.type }}. The toolbar actions need an unambiguous one."
    }

    val onCreate = services.single().methods.singleOrNull {
        it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: error("${services.single().type} does not declare onCreate()V")

    val method = mutableClassDefBy(services.single().type).methods.single {
        it.toDescriptor() == onCreate.toDescriptor()
    }

    method.assertRegisterCount(ON_CREATE_REGISTER_COUNT, onCreate.toDescriptor())

    method.addInstructions(0, "invoke-static { p0 }, $SET_SERVICE")
}
