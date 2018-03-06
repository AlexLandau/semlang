package net.semlang.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.EntityId
import net.semlang.api.ModuleId
import net.semlang.interpreter.InterpreterOptions
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter
import org.junit.Test
import net.semlang.parser.*
import java.io.File

class HelloWorldTest {

    @Test
    fun test() {
        val moduleId = ModuleId("semlang-demo", "hello-world", "develop")
        val module = parseAndValidateFile(File("../../semlang-hello-world/src/main/semlang/helloWorld.sem"), moduleId, CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()

        val interpreter = SemlangForwardInterpreter(module, InterpreterOptions())
        val mockedTextOut = SemObject.Boolean(true) //TODO: Fix this to be an actual mock object
        interpreter.interpret(EntityId.of("sayHello"), listOf(mockedTextOut))
    }

}
