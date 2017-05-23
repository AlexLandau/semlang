import com.squareup.javapoet.*
import semlang.api.*
import semlang.internal.test.TestAnnotationContents
import semlang.internal.test.parseTestAnnotationContents
import java.io.File
import java.math.BigInteger
import javax.lang.model.element.Modifier

/**
 * TODO:
 * - Needed preprocessing step: Fix numeric variable names that are illegal for Java
 * - Fix references to native functions: List.empty(), List.append, etc.
 *   - Will involve a mix of delegating to real functions and adding a runtime library to reference
 */

data class WrittenJavaInfo(val testClassNames: List<String>)

fun writeJavaSourceIntoFolders(context: ValidatedContext, newSrcDir: File, newTestSrcDir: File): WrittenJavaInfo {
    return JavaCodeWriter(context, newSrcDir, newTestSrcDir).write()
}

private class JavaCodeWriter(val context: ValidatedContext, val newSrcDir: File, val newTestSrcDir: File) {
    val classMap = HashMap<ClassName, TypeSpec.Builder>()

    fun write(): WrittenJavaInfo {
        context.ownFunctionImplementations.values.forEach { function ->

            val method = writeMethod(function)

            val className = getContainingClassName(function.id)
            if (!classMap.containsKey(className)) {
                classMap.put(className, TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL))
            }
            classMap[className]!!.addMethod(method)

            // Write unit tests for @Test annotations
            function.annotations.forEach { annotation ->
                if (annotation.name == "Test") {
                    val testContents = parseTestAnnotationContents(annotation.value ?: error("@Test annotations must have values!"), function)

                    writeJUnitTest(newTestSrcDir, function, testContents)
                }
            }
        }

        classMap.forEach { className, classBuilder ->
            val javaFile = JavaFile.builder(className.packageName(), classBuilder.build())
                    .build()
            javaFile.writeTo(newSrcDir)
        }

        // Write a JUnit test file
//        writeJUnitTest(newTestSrcDir)

        return WrittenJavaInfo(testClassNames)
    }

    private fun writeMethod(function: ValidatedFunction): MethodSpec {
        // TODO: Eventually, support non-static methods
        val builder = MethodSpec.methodBuilder(function.id.functionName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)

        function.arguments.forEach { argument ->
            builder.addParameter(getType(argument.type), argument.name)
        }

        builder.returns(getType(function.returnType))

        // TODO: Add block here
        builder.addCode(writeBlock(function.block, null))

        return builder.build()
    }

    /**
     * @param varToAssign The variable to assign the output of this block to if this block is part of an if/then/else;
     * if this is null, instead "return" the result.
     */
    private fun writeBlock(block: TypedBlock, varToAssign: String?): CodeBlock {
        val builder = CodeBlock.builder()

        block.assignments.forEach { (name, type, expression) ->
            // TODO: Test case where a variable within the block has the same name as the variable we're going to assign to
            if (expression is TypedExpression.IfThen) {
                builder.addStatement("final \$T \$L", getType(type), name)
                builder.beginControlFlow("if (\$L)", writeExpression(expression))
                builder.add(writeBlock(expression.thenBlock, name))
                builder.nextControlFlow("else")
                builder.add(writeBlock(expression.elseBlock, name))
                builder.endControlFlow()
            } else {
                builder.addStatement("final \$T \$L = \$L", getType(type), name, writeExpression(expression))
            }
        }

        // TODO: Handle case where returnedExpression is if/then (?) -- or will that get factored out?
        if (varToAssign == null) {
            builder.addStatement("return \$L", writeExpression(block.returnedExpression))
        } else {
            builder.addStatement("\$L = \$L", varToAssign, writeExpression(block.returnedExpression))
        }

        return builder.build()
    }

    private fun writeExpression(expression: TypedExpression): CodeBlock {
        return when (expression) {
            is TypedExpression.Variable -> {
                CodeBlock.of("\$L", expression.name)
            }
            is TypedExpression.IfThen -> TODO()
            is TypedExpression.NamedFunctionCall -> {
                val functionCallStrategy = getNamedFunctionCallStrategy(expression.functionId)
                functionCallStrategy.apply(getArgumentsBlock(expression.arguments))
            }
            is TypedExpression.ExpressionFunctionCall -> {
                CodeBlock.of("\$L.apply(\$L)", writeExpression(expression.functionExpression), getArgumentsBlock(expression.arguments))
            }
            is TypedExpression.Literal -> {
                writeLiteralExpression(expression)
            }
            is TypedExpression.Follow -> {
                CodeBlock.of("\$L.\$L", writeExpression(expression.expression), expression.name)
            }
            is TypedExpression.NamedFunctionBinding -> TODO()
            is TypedExpression.ExpressionFunctionBinding -> TODO()
        }
    }

    private fun getArgumentsBlock(arguments: List<TypedExpression>): CodeBlock {
        if (arguments.isEmpty()) {
            return CodeBlock.of("")
        }
        val builder = CodeBlock.builder()
        builder.add("\$L", writeExpression(arguments[0]))
        arguments.drop(1).forEach { argument ->
            builder.add(", \$L", writeExpression(argument))
        }
        return builder.build()
    }

    // TODO: Prepopulate for native methods
    val namedFunctionCallStrategies = HashMap<FunctionId, FunctionCallStrategy>()
    init {
        namedFunctionCallStrategies.putAll(getNativeFunctionCallStrategies())
    }
    private fun getNamedFunctionCallStrategy(functionId: FunctionId): FunctionCallStrategy {
        val cached = namedFunctionCallStrategies[functionId]
        if (cached != null) {
            return cached
        }

        val classContainingFunction = getContainingClassName(functionId)
        val strategy = object: FunctionCallStrategy {
            override fun apply(arguments: CodeBlock): CodeBlock {
                return CodeBlock.of("\$T.\$L(\$L)", classContainingFunction, functionId.functionName, arguments)
            }
        }
        namedFunctionCallStrategies[functionId] = strategy
        return strategy
    }
    companion object {
    }

    private fun writeLiteralExpression(expression: TypedExpression.Literal): CodeBlock {
        return when (expression.type) {
            Type.INTEGER -> {
                CodeBlock.of("new \$T(\$S)", BigInteger::class.java, expression.literal)
            }
            Type.NATURAL -> {
                CodeBlock.of("new \$T(\$S)", BigInteger::class.java, expression.literal)
            }
            Type.BOOLEAN -> {
                CodeBlock.of("\$L", expression.literal)
            }
            is Type.List -> error("List literals not supported")
            is Type.Try -> error("Try literals not supported")
            is Type.FunctionType -> error("Function type literals not supported")
            is Type.NamedType -> error("Named type literals not supported")
        }
    }

    private fun getType(semlangType: Type): TypeName {
        return when (semlangType) {
            Type.INTEGER -> TypeName.get(BigInteger::class.java)
            Type.NATURAL -> TypeName.get(BigInteger::class.java)
            Type.BOOLEAN -> TypeName.BOOLEAN
            is Type.List -> ParameterizedTypeName.get(ClassName.get(java.util.List::class.java), getType(semlangType.parameter))
            is Type.Try -> ParameterizedTypeName.get(ClassName.get(java.util.Optional::class.java), getType(semlangType.parameter))
            is Type.FunctionType -> TODO()
            is Type.NamedType -> getNamedType(semlangType)
        }
    }

    private fun getNamedType(semlangType: Type.NamedType): TypeName {
        // TODO: Might end up being more complicated? This is probably not quite right
        return ClassName.bestGuess(semlangType.id.toString())
    }

    private fun getContainingClassName(functionId: FunctionId): ClassName {
        //Assume the first capitalized string is the className
        val allParts = functionId.thePackage.strings + functionId.functionName
        val packageParts = ArrayList<String>()
        var className: String? = null
        for (part in allParts) {
            if (part[0].isUpperCase()) {
                className = part
                break
            }
            packageParts.add(part)
        }
        if (className == null) {
            return ClassName.get(functionId.thePackage.strings.joinToString("."), "Functions")
//        error("Function ID without capitalized part: $functionId")
        }
        return ClassName.get(packageParts.joinToString("."), className)
    }

    val testClassNames = ArrayList<String>()
    private fun writeJUnitTest(newTestSrcDir: File, function: ValidatedFunction, testContents: TestAnnotationContents) {
        val className = getContainingClassName(function.id)
        val testClassName = ClassName.bestGuess(className.toString() + "Test")

        val outputExpression = TypedExpression.Literal(function.returnType, testContents.outputLiteral)
        val outputCode = writeLiteralExpression(outputExpression)
        val argExpressions = function.arguments.map { arg -> arg.type }
                .zip(testContents.argLiterals)
                .map { (type, literal) -> TypedExpression.Literal(type, literal) }
        val argsCode = getArgumentsBlock(argExpressions)

        val runFakeTest = MethodSpec.methodBuilder("runUnitTest")
                .addModifiers(Modifier.PUBLIC)
                .returns(Void.TYPE)
                .addAnnotation(ClassName.bestGuess("org.junit.Test"))
//                .addStatement("\$T.out.println(\$S)", System::class.java, "I am a fake test!")
                .addStatement("\$T.assertEquals(\$L, \$L)",
                        ClassName.bestGuess("org.junit.Assert"),
                        outputCode,
                        getNamedFunctionCallStrategy(function.id).apply(argsCode)
                )
                .build()

        val testClass = TypeSpec.classBuilder(testClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(runFakeTest)
                .build()

        //TODO: Replace this
        val javaFile = JavaFile.builder(testClassName.packageName(), testClass).build()
        javaFile.writeTo(newTestSrcDir)
        testClassNames.add(testClassName.toString())
    }

}

private fun getNativeFunctionCallStrategies(): Map<FunctionId, FunctionCallStrategy> {
    val map = HashMap<FunctionId, FunctionCallStrategy>()

    val list = Package(listOf("List"))
    val javaLists = ClassName.bestGuess("net.semlang.java.Lists")
    map.put(FunctionId(list, "empty"), getStaticFunctionCall(javaLists, "empty"))
    // TODO: Find an approach to remove most uses of append where we'd be better off with e.g. add
    map.put(FunctionId(list, "append"), getStaticFunctionCall(javaLists, "append"))
    // TODO: Find an approach where we can replace this with a simple .get() call...
    // Harder than it sounds, given the BigInteger input; i.e. we need to intelligently replace with a "Size"/"Index" type
    map.put(FunctionId(list, "get"), getStaticFunctionCall(javaLists, "get"))
    map.put(FunctionId(list, "size"), getStaticFunctionCall(javaLists, "size"))

    return map
}

private fun getStaticFunctionCall(className: String, methodName: String): StaticFunctionCallStrategy {
    return getStaticFunctionCall(ClassName.bestGuess(className), methodName)
}
private fun getStaticFunctionCall(className: ClassName, methodName: String): StaticFunctionCallStrategy {
    return StaticFunctionCallStrategy(CodeBlock.of("\$T.\$L", className, methodName))
}

private interface FunctionCallStrategy {
    fun apply(arguments: CodeBlock): CodeBlock
}

class StaticFunctionCallStrategy(val functionName: CodeBlock): FunctionCallStrategy {
    override fun apply(arguments: CodeBlock): CodeBlock {
        return CodeBlock.of("\$L(\$L)", functionName, arguments)
    }
}
