package net.semlang.helloworld

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.EntityId
import net.semlang.api.ModuleName
import net.semlang.interpreter.InterpreterOptions
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter
import org.junit.Test
import net.semlang.validator.parseAndValidateFile
import org.junit.Assert
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class HelloWorldTest {

    @Test
    fun test() {
        val moduleName = ModuleName("semlang-demo", "hello-world")
        val module = parseAndValidateFile(File("../../semlang-hello-world/src/main/semlang/helloWorld.sem"), moduleName, CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()

        val interpreter = SemlangForwardInterpreter(module, InterpreterOptions())
        val outputCollector = ByteArrayOutputStream()
        val mockedTextOut = SemObject.TextOut(PrintStream(outputCollector))
        interpreter.interpret(EntityId.of("sayHello"), listOf(mockedTextOut))
        Assert.assertEquals("Hello, world!\n", outputCollector.toString())
    }

}
