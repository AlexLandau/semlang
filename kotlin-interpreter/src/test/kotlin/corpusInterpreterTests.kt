package semlang.interpreter.test

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.*
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.parseFile
import semlang.parser.validateContext
import java.io.File
import java.util.regex.Pattern

@RunWith(Parameterized::class)
class CorpusInterpreterTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("../semlang-corpus/src/main/semlang")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        var foundAnyTests = false
        val validatedContext = parseAndValidateFile(file)
        validatedContext.ownFunctionImplementations.values.forEach { function ->
            function.annotations.forEach { annotation ->
                if (annotation.name == "Test") {
                    doTest(function, validatedContext, annotation.value ?: throw AssertionError("Tests can't have null values"))
                    foundAnyTests = true
                }
            }
        }
        if (!foundAnyTests) {
            fail()
        }
    }

    private object Patterns {
        // TODO: Allow the ' character in strings via escaping
        val TEST_ANNOTATION_VALUE_PATTERN: Pattern = Pattern.compile("^\\[('([^']*)')((, *'[^']*')*)\\]: '([^']*)'$")
        val ADDITIONAL_ARGUMENT_PATTERN: Pattern = Pattern.compile(", *'([^']*)'")
    }

    private fun doTest(function: ValidatedFunction, context: ValidatedContext, value: String) {
        val matcher = Patterns.TEST_ANNOTATION_VALUE_PATTERN.matcher(value)
        if (!matcher.matches()) {
            fail("Value \"$value\" didn't match pattern")
        }

        val firstArgument: String? = matcher.group(2)
        val additionalArguments = parseAdditionalArguments(matcher.group(3))
        val output = matcher.group(5)
        val allArguments = if (firstArgument == null) {
            listOf()
        } else {
            listOf(firstArgument) + additionalArguments
        }
        if (allArguments.size != function.arguments.size) {
            fail("Expected ${allArguments.size} arguments for ${function.id}, but received ${allArguments.size}: $allArguments")
        }

        runTest(function, allArguments, output, context)
    }

    private fun runTest(function: ValidatedFunction, argumentLiterals: List<String>,
                        outputLiteral: String, context: ValidatedContext) {
        val interpreter = SemlangForwardInterpreter(context)
        val arguments = instantiateArguments(function.arguments, argumentLiterals, interpreter)
        val actualOutput = interpreter.interpret(function.id, arguments)
        val desiredOutput = interpreter.evaluateLiteral(function.returnType, outputLiteral)
        if (!interpreter.areEqual(actualOutput, desiredOutput)) {
            fail("For function ${function.id}, expected output with arguments $argumentLiterals to be $desiredOutput, but was $actualOutput")
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

    private fun parseAndValidateFile(file: File): ValidatedContext {
        val functionsMap2 = parseFile(file)
        return validateContext(functionsMap2, listOf(getNativeContext()))
    }
}
