package net.semlang.internal.test

import net.semlang.api.AnnotationArgument
import net.semlang.api.Argument
import net.semlang.api.ValidatedFunction
import net.semlang.api.ValidatedModule
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter

/**
 * Returns the number of annotation tests that were found and run.
 *
 * @throws AssertionError if a test fails.
 */
fun runAnnotationTests(module: ValidatedModule): Int {
    var testCount = 0
    module.ownFunctions.values.forEach { function ->
        function.annotations.forEach { annotation ->
            if (annotation.name == "Test") {
                doTest(function, module, annotation.values)
                testCount++
            }
        }
    }
    return testCount
}

data class TestAnnotationContents(val argLiterals: List<String>, val outputLiteral: String)

private fun doTest(function: ValidatedFunction, module: ValidatedModule, values: List<AnnotationArgument>) {
    val contents = verifyTestAnnotationContents(values, function)

    runTest(function, contents, module)
}

fun verifyTestAnnotationContents(inputs: List<AnnotationArgument>, function: ValidatedFunction): TestAnnotationContents {
    if (inputs.size != 2) {
        fail("Expected 2 arguments to a @Test annotation (arguments list and result literal)")
    }
    val firstInput = inputs[0]
    val secondInput = inputs[1]
    if (firstInput !is AnnotationArgument.List) {
        fail("Expected first input to @Test to be a list of arguments")
    }
    if (secondInput !is AnnotationArgument.Literal) {
        fail("Expected second input to @Test to be a literal")
    }

    val allArguments = firstInput.values.map { argInput: AnnotationArgument -> (argInput as AnnotationArgument.Literal).value }
    val output = secondInput.value

    if (allArguments.size != function.arguments.size) {
        fail("Expected ${function.arguments.size} arguments for ${function.id}, but received ${allArguments.size}: $allArguments")
    }

    return TestAnnotationContents(allArguments, output)
}

private fun runTest(function: ValidatedFunction, contents: TestAnnotationContents, module: ValidatedModule) {
    val interpreter = SemlangForwardInterpreter(module)
    val arguments = instantiateArguments(function.arguments, contents.argLiterals, interpreter)
    val actualOutput = interpreter.interpret(function.id, arguments)
    val desiredOutput = interpreter.evaluateLiteral(function.returnType, contents.outputLiteral)
    if (!interpreter.areEqual(actualOutput, desiredOutput)) {
        fail("For function ${function.id}, expected output with arguments ${contents.argLiterals} to be $desiredOutput, but was $actualOutput")
    }
}

private fun instantiateArguments(argumentSpecs: List<Argument>, argumentLiterals: List<String>, interpreter: SemlangForwardInterpreter): List<SemObject> {
    return argumentSpecs.zip(argumentLiterals).map { (spec, literal) ->
        interpreter.evaluateLiteral(spec.type, literal)
    }
}

private fun fail(text: String): Nothing {
    throw AssertionError(text)
}