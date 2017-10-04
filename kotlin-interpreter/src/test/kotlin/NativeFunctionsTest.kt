package net.semlang.interpreter.test

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
            Assert.fail("Some native methods are defined, but not yet implemented in the interpreter: $missingFunctions")
        }
    }
}
