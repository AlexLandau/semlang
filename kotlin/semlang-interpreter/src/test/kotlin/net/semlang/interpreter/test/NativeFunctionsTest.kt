package net.semlang.interpreter.test

import net.semlang.api.EntityId
import net.semlang.api.getNativeFunctionOnlyDefinitions
import net.semlang.interpreter.getNativeFunctions
import org.junit.Assert
import org.junit.Test

class NativeFunctionsTest {
    @Test
    fun testAllNativeFunctionsDefined() {
        val implementedNativeFunctions = getNativeFunctions()
        val definedNativeFunctions = getNativeFunctionOnlyDefinitions()
        val missingFunctions = definedNativeFunctions.keys.minus(implementedNativeFunctions.keys)

        if (missingFunctions.isNotEmpty()) {
            Assert.fail("Some native functions are defined, but not yet implemented in the interpreter: $missingFunctions")
        }
    }

    @Test
    fun testAllDefinedFunctionsReal() {
        val implementedNativeFunctions = getNativeFunctions()
        val definedNativeFunctions = getNativeFunctionOnlyDefinitions()
        val exceptions = listOf(
                EntityId.of("BasicSequence", "first"),
                EntityId.of("BasicSequence", "get")
        )
        val illegitimateFunctions = implementedNativeFunctions.keys.minus(definedNativeFunctions.keys).minus(exceptions)


        if (illegitimateFunctions.isNotEmpty()) {
            Assert.fail("Some functions are implemented in the interpreter as native functions, but " +
                    "are not in the native function definitions: $illegitimateFunctions")
        }
    }
}
