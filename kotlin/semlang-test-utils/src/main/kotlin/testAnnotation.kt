package net.semlang.internal.test

import net.semlang.api.*
import net.semlang.interpreter.InterpreterOptions
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter

private val TEST_ANNOTATION_NAME = EntityId.of("Test")
private val MOCK_TEST_ANNOTATION_NAME = EntityId.of("Test", "Mock")
/**
 * Returns the number of annotation tests that were found and run.
 *
 * @throws AssertionError if a test fails.
 */
fun runAnnotationTests(module: ValidatedModule, options: InterpreterOptions = InterpreterOptions()): Int {
    var testCount = 0
    val errorCollector = ArrayList<String>()
    for (function in module.ownFunctions.values) {
        for (annotation in function.annotations) {
            if (annotation.name == TEST_ANNOTATION_NAME) {
                doTest(function, module, annotation.values, errorCollector, options)
                testCount++
            } else if (annotation.name == MOCK_TEST_ANNOTATION_NAME) {
                doMockTest(function, module, annotation.values, errorCollector, options)
                testCount++
            }
        }
    }
    if (errorCollector.isNotEmpty()) {
        if (errorCollector.size == 1) {
            throw AssertionError(errorCollector[0])
        } else {
            throw AssertionError("Multiple tests had failures:\n * " + errorCollector.joinToString("\n * "))
        }
    }
    return testCount
}

// TODO: This is currently unused
private fun doMockTest(function: ValidatedFunction, module: ValidatedModule, values: List<AnnotationArgument>, errorCollector: MutableCollection<String>, options: InterpreterOptions) {
    val contents = verifyMockTestAnnotationContents(values, function)

    runMockTest(function, contents, module, errorCollector, options)
}

sealed class MockArgument {
    data class Literal(val value: String): MockArgument()
    data class MockObjectId(val name: String): MockArgument()
}
data class MockCall(val functionName: EntityId, val expectedArgs: List<MockArgument>, val outputToGive: MockArgument)
data class MockTestAnnotationContents(val args: List<MockArgument>, val output: MockArgument, val expectedCalls: List<MockCall>)

fun verifyMockTestAnnotationContents(inputs: List<AnnotationArgument>, function: ValidatedFunction): MockTestAnnotationContents {
    if (inputs.size != 3) {
        fail("Expected 3 arguments to a @Test.Mock annotation")
    }
    val firstInput = inputs[0] as? AnnotationArgument.List ?: error("Expected first input to @Test.Mock to be a list of arguments")
    val secondInput = inputs[1] as? AnnotationArgument.Literal ?: error("Expected second input to @Test.Mock to be a literal")
    val thirdInput = inputs[2] as? AnnotationArgument.List ?: error("Expected third input to @Test.Mock to be a list of mock function call specifications")

    if (firstInput.values.size != function.arguments.size) {
        error("List of function arguments given to @Test.Mock is not the same length as the function's arguments")
    }
    val arguments = firstInput.values.zip(function.arguments).map { (value, argument) ->
        val valueString = (value as? AnnotationArgument.Literal)?.value ?: error("Expected arguments in the first input to @Test.Mock to be strings")
        if (argument.type.isReference()) {
            MockArgument.MockObjectId(valueString)
        } else {
            MockArgument.Literal(valueString)
        }
    }

    val outputString = if (function.returnType.isReference()) {
        MockArgument.MockObjectId(secondInput.value)
    } else {
        MockArgument.Literal(secondInput.value)
    }

    val expectedCalls = thirdInput.values.map { singleCallAnnotationArgument ->
        if (singleCallAnnotationArgument !is AnnotationArgument.List) {
            error("Expected each element of the third input to @Test.Mock to be a list containing data about a mocked call")
        }
        if (singleCallAnnotationArgument.values.size != 3) {
            error("Expected each list defining a mock call in a @Test.Mock to have three arguments")
        }
        val firstCallElem = singleCallAnnotationArgument.values[0] as? AnnotationArgument.Literal ?: error("Expected first part of a mock call definition in a @Test.Mock to be a function name")
        val secondCallElem = singleCallAnnotationArgument.values[1] as? AnnotationArgument.List ?: error("Expected second part of a mock call definition in a @Test.Mock to be a list of arguments")
        val thirdCallElem = singleCallAnnotationArgument.values[2] as? AnnotationArgument.Literal ?: error("Expected third part of a mock call definition in a @Test.Mock to be a literal")

        val mockedFunctionName = EntityId.parse(firstCallElem.value)
        val signature = getNativeFunctionOnlyDefinitions()[mockedFunctionName] ?: error("Function $mockedFunctionName is not a native function and cannot be mocked")

        if (secondCallElem.values.size != signature.argumentTypes.size) {
            error("Wrong number of arguments for the mock call for $mockedFunctionName")
        }

        val expectedArgs = secondCallElem.values.zip(signature.argumentTypes).map { (callAnnotationArgument, argumentType) ->
            if (callAnnotationArgument !is AnnotationArgument.Literal) {
                error("Expected each input of a mock call definition in a @Test.Mock to be a string")
            }
            if (argumentType.isReference()) {
                MockArgument.MockObjectId(callAnnotationArgument.value)
            } else {
                MockArgument.Literal(callAnnotationArgument.value)
            }
        }

        val outputToGive = if (signature.outputType.isReference()) {
            MockArgument.MockObjectId(thirdCallElem.value)
        } else {
            MockArgument.Literal(thirdCallElem.value)
        }

        MockCall(mockedFunctionName, expectedArgs, outputToGive)
    }

    return MockTestAnnotationContents(arguments, outputString, expectedCalls)
}

private fun doTest(function: ValidatedFunction, module: ValidatedModule, values: List<AnnotationArgument>, errorCollector: MutableCollection<String>, options: InterpreterOptions) {
    val contents = try {
        verifyTestAnnotationContents(values, function)
    } catch (e: AssertionError) {
        errorCollector.add(e.message!!)
        return
    }

    runTest(function, contents, module, errorCollector, options)
}

data class TestAnnotationContents(val args: List<AnnotationArgument>, val output: AnnotationArgument)

fun verifyTestAnnotationContents(inputs: List<AnnotationArgument>, function: ValidatedFunction): TestAnnotationContents {
    if (inputs.size != 2) {
        fail("Expected 2 arguments to a @Test annotation (arguments list and result literal)")
    }
    val firstInput = inputs[0]
    if (firstInput !is AnnotationArgument.List) {
        fail("Expected first input to @Test to be a list of arguments")
    }

    val allArguments = firstInput.values
    val output = inputs[1]

    if (allArguments.size != function.arguments.size) {
        fail("Expected ${function.arguments.size} arguments for ${function.id}, but received ${allArguments.size}: $allArguments")
    }

    return TestAnnotationContents(allArguments, output)
}

private fun runTest(function: ValidatedFunction, contents: TestAnnotationContents, module: ValidatedModule, errorCollector: MutableCollection<String>, options: InterpreterOptions) {
    val interpreter = SemlangForwardInterpreter(module, options)
    val arguments = instantiateArguments(function.arguments, contents.args, interpreter)
    val actualOutput = interpreter.interpret(function.id, arguments)
    val desiredOutput = evaluateAnnotationArgAsLiteral(function.returnType, contents.output, interpreter)
    if (!interpreter.areEqual(actualOutput, desiredOutput)) {
        errorCollector.add("For function ${function.id}, expected output with arguments ${contents.args} to be $desiredOutput, but was $actualOutput")
    }
}

private fun runMockTest(function: ValidatedFunction, contents: MockTestAnnotationContents, module: ValidatedModule, errorCollector: MutableCollection<String>, options: InterpreterOptions) {
    val bootstrapInterpreter = SemlangForwardInterpreter(module, options)
    val optionsWithMocks = options.copy(mockCalls = translateMockCalls(contents.expectedCalls, bootstrapInterpreter))

    val interpreter = SemlangForwardInterpreter(module, optionsWithMocks)
    val arguments = instantiateMockArguments(function.arguments, contents.args, interpreter)
    val actualOutput = interpreter.interpret(function.id, arguments)
    val desiredOutput = when (contents.output) {
        is MockArgument.MockObjectId -> SemObject.Mock(contents.output.name)
        is MockArgument.Literal -> interpreter.evaluateLiteral(function.returnType, contents.output.value)
    }
    if (!interpreter.areEqual(actualOutput, desiredOutput)) {
        errorCollector.add("For function ${function.id}, expected output with arguments ${contents.args} to be $desiredOutput, but was $actualOutput")
    }
}

private fun translateMockCalls(expectedCalls: List<MockCall>, interpreter: SemlangForwardInterpreter): Map<EntityId, Map<List<SemObject>, SemObject>> {
    val results = HashMap<EntityId, MutableMap<List<SemObject>, SemObject>>()

    for (expectedCall in expectedCalls) {
        val functionId = expectedCall.functionName
        val signature = getNativeFunctionOnlyDefinitions()[functionId] ?: error("Mocked functions must be native functions")
        if (expectedCall.expectedArgs.size != signature.argumentTypes.size) {
            error("Argument length mismatch in mocking for $functionId")
        }

        val argObjects = signature.argumentTypes.zip(expectedCall.expectedArgs).map { (type, argument) ->
            when (argument) {
                is MockArgument.MockObjectId -> {
                    SemObject.Mock(argument.name)
                }
                is MockArgument.Literal -> {
                    interpreter.evaluateLiteral(type, argument.value)
                }
            }
        }

        val outputObject = when (expectedCall.outputToGive) {
            is MockArgument.MockObjectId -> SemObject.Mock(expectedCall.outputToGive.name)
            is MockArgument.Literal -> interpreter.evaluateLiteral(signature.outputType, expectedCall.outputToGive.value)
        }

        if (!results.containsKey(functionId)) {
            results.put(functionId, HashMap())
        }
        results[functionId]!!.put(argObjects, outputObject)
    }
    return results
}

private fun instantiateArguments(argumentSpecs: List<Argument>, argumentLiterals: List<AnnotationArgument>, interpreter: SemlangForwardInterpreter): List<SemObject> {
    return argumentSpecs.zip(argumentLiterals).map { (spec, literal) ->
        evaluateAnnotationArgAsLiteral(spec.type, literal, interpreter)
    }
}

fun evaluateAnnotationArgAsLiteral(type: Type, annotationArg: AnnotationArgument, interpreter: SemlangForwardInterpreter): SemObject {
    return when (type) {
        is Type.FunctionType.Ground -> error("The @Test annotation cannot (currently) test a function that outputs a function.")
        is Type.FunctionType.Parameterized -> TODO()
        is Type.InternalParameterType -> TODO()
        is Type.ParameterType -> TODO()
        is Type.NamedType -> {
            if (type.ref == NativeOpaqueType.LIST.resolvedRef) {
                when (annotationArg) {
                    is AnnotationArgument.Literal -> error("List types should be expressed as an annotation argument list, but was: $annotationArg")
                    is AnnotationArgument.List -> {
                        val itemType = type.parameters[0]
                        val semObjects =
                            annotationArg.values.map { evaluateAnnotationArgAsLiteral(itemType, it, interpreter) }
                        SemObject.SemList(semObjects)
                    }
                }
            } else if (type.ref == NativeOpaqueType.MAYBE.resolvedRef) {
                when (annotationArg) {
                    is AnnotationArgument.Literal -> interpreter.evaluateLiteral(type, (annotationArg as AnnotationArgument.Literal).value)
                    is AnnotationArgument.List -> {
                        if (annotationArg.values.isEmpty()) {
                            SemObject.Maybe.Failure
                        } else {
                            val semObject = evaluateAnnotationArgAsLiteral(type.parameters[0], annotationArg.values.single(), interpreter)
                            SemObject.Maybe.Success(semObject)
                        }
                    }
                }
            } else {
                interpreter.evaluateLiteral(type, (annotationArg as AnnotationArgument.Literal).value)
            }
        }
    }
}

private fun instantiateMockArguments(argumentSpecs: List<Argument>, arguments: List<MockArgument>, interpreter: SemlangForwardInterpreter): List<SemObject> {
    return argumentSpecs.zip(arguments).map { (spec, argument) ->
        when (argument) {
            is MockArgument.MockObjectId -> SemObject.Mock(argument.name)
            is MockArgument.Literal -> interpreter.evaluateLiteral(spec.type, argument.value)
        }
    }
}

// TODO: Any reason this isn't just error?
private fun fail(text: String): Nothing {
    throw AssertionError(text)
}

enum class TestsType {
    ALL_TESTS,
    NON_MOCK_TESTS,
}

/**
 * Returns the number of tests that we would expect to run for this module.
 */
fun getExpectedTestCount(module: ValidatedModule, testsType: TestsType): Int {
    var count = 0
    for (function in module.ownFunctions.values) {
        for (annotation in function.annotations) {
            if (annotation.name == TEST_ANNOTATION_NAME) {
                count++
            } else if (testsType == TestsType.ALL_TESTS && annotation.name == MOCK_TEST_ANNOTATION_NAME) {
                count++
            }
        }
    }
    return count
}
