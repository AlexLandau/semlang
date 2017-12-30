package net.semlang.internal.test

import net.semlang.api.Argument
import net.semlang.api.ValidatedFunction
import net.semlang.api.ValidatedModule
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter
import java.util.regex.Pattern

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
                doTest(function, module, annotation.value ?: throw AssertionError("Test annotations shouldn't be empty"))
                testCount++
            }
        }
    }
    return testCount
}


private object Patterns {
    val QP /* "quoted pattern" */: String = "'(([^'\\\\]|\\\\.)*)'" // A literal string in single quotes, with \ for escaping
    val TEST_ANNOTATION_VALUE_PATTERN: Pattern = Pattern.compile("^\\[($QP)?((, *$QP)*)\\]: *$QP$")
    val ADDITIONAL_ARGUMENT_PATTERN: Pattern = Pattern.compile(", *$QP")
}

data class TestAnnotationContents(val argLiterals: List<String>, val outputLiteral: String)

private fun doTest(function: ValidatedFunction, module: ValidatedModule, value: String) {
    val contents = parseTestAnnotationContents(value, function)

    runTest(function, contents, module)
}

fun parseTestAnnotationContents(value: String, function: ValidatedFunction): TestAnnotationContents {
    val matcher = Patterns.TEST_ANNOTATION_VALUE_PATTERN.matcher(value)
    if (!matcher.matches()) {
        fail("Value \"$value\" didn't match pattern")
    }

    val firstArgument: String? = matcher.group(2)
    val additionalArguments = parseAdditionalArguments(matcher.group(4))
    val output = matcher.group(8)
    val allArguments = if (firstArgument == null) {
        listOf()
    } else {
        listOf(firstArgument) + additionalArguments
    }
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

private fun parseAdditionalArguments(group: String?): List<String> {
    if (group == null) {
        return listOf()
    }
    val matcher = Patterns.ADDITIONAL_ARGUMENT_PATTERN.matcher(group)
    val arguments = ArrayList<String>()
    while (matcher.find()) {
        arguments.add(matcher.group(1))
    }
    return arguments
}

private fun fail(text: String): Nothing {
    throw AssertionError(text)
}