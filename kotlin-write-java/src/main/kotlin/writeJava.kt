import com.squareup.javapoet.*
import semlang.api.*
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
        }

        classMap.forEach { className, classBuilder ->
            val javaFile = JavaFile.builder(className.packageName(), classBuilder.build())
                    .build()
            javaFile.writeTo(newSrcDir)
        }

        // Write a JUnit test file
        writeJUnitTest(newTestSrcDir)

        return WrittenJavaInfo(listOf("com.example.test.TestClass"))
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
        builder.addCode(writeBlock(function.block))

        return builder.build()
    }

    private fun writeBlock(block: TypedBlock): CodeBlock {
        val builder = CodeBlock.builder()

        block.assignments.forEach { assignment ->
            builder.addStatement("final \$T \$L = \$L", getType(assignment.type), assignment.name, writeExpression(assignment.expression))
        }

        builder.addStatement("return \$L", writeExpression(block.returnedExpression))

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
//                CodeBlock.of("\$L(\$L)", getFunctionName(expression.functionId), getArgumentsBlock(expression.arguments))
            }
            is TypedExpression.ExpressionFunctionCall -> TODO()
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

    private fun writeJUnitTest(newTestSrcDir: File) {
        val runFakeTest = MethodSpec.methodBuilder("runFakeTest")
                .addModifiers(Modifier.PUBLIC)
                .returns(Void.TYPE)
                .addAnnotation(ClassName.bestGuess("org.junit.Test"))
                .addStatement("\$T.out.println(\$S)", System::class.java, "I am a fake test!")
                .build()

        val testClass = TypeSpec.classBuilder("TestClass")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(runFakeTest)
                .build()

        val javaFile = JavaFile.builder("com.example.test", testClass).build()
        javaFile.writeTo(newTestSrcDir)
    }

}

private interface FunctionCallStrategy {
    fun apply(arguments: CodeBlock): CodeBlock
}