import com.squareup.javapoet.*
import semlang.api.*
import semlang.internal.test.TestAnnotationContents
import semlang.internal.test.parseTestAnnotationContents
import java.io.File
import java.math.BigInteger
import javax.lang.model.element.Modifier

/**
 * TODO:
 * - Struct support
 * - Interface support
 * - Needed preprocessing step: Put if-then statements only at the top level (assignment or return statement)
 *   - If no existing tests run into this problem, create a test that does so
 */

data class WrittenJavaInfo(val testClassNames: List<String>)

fun writeJavaSourceIntoFolders(unprocessedContext: ValidatedContext, javaPackage: List<String>, newSrcDir: File, newTestSrcDir: File): WrittenJavaInfo {
    if (javaPackage.isEmpty()) {
        error("The Java package must be non-empty.")
    }
    // Pre-processing steps
    val context = constrainVariableNames(unprocessedContext, RenamingStrategies::avoidNumeralAtStartByPrependingUnderscores)

    return JavaCodeWriter(context, javaPackage, newSrcDir, newTestSrcDir).write()
}

private class JavaCodeWriter(val context: ValidatedContext, val javaPackage: List<String>, val newSrcDir: File, val newTestSrcDir: File) {
    val classMap = HashMap<ClassName, TypeSpec.Builder>()

    fun write(): WrittenJavaInfo {
        context.ownStructs.values.forEach { struct ->
            val className = getStructClassName(struct.id)
            val structClassBuilder = writeStructClass(struct, className)

            if (classMap.containsKey(className)) {
                error("Something's wrong here")
            }
            classMap[className] = structClassBuilder
        }
        // Enable calls to struct constructors
        addStructConstructorFunctionCallStrategies(context.getAllStructs().values)

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

                    prepareJUnitTest(function, testContents)
                }
            }
        }

        classMap.forEach { className, classBuilder ->
            val javaFile = JavaFile.builder(className.packageName(), classBuilder.build())
                    .build()
            javaFile.writeTo(newSrcDir)
        }

//        testClassBuilders.forEach()
        writePreparedJUnitTests(newTestSrcDir)
        // Write a JUnit test file
//        writeJUnitTest(newTestSrcDir)

        return WrittenJavaInfo(testClassCounts.keys.toList())
    }

    private fun addStructConstructorFunctionCallStrategies(structs: Collection<Struct>) {
        for (struct in structs) {
            val structClass = getStructClassName(struct.id)
            namedFunctionCallStrategies[struct.id] = object: FunctionCallStrategy {
                override fun apply(chosenTypes: List<TypeName>, arguments: CodeBlock): CodeBlock {
                    if (chosenTypes.isEmpty()) {
                        return CodeBlock.of("new \$T(\$L)", structClass, arguments)
                    } else {
                        return CodeBlock.of("new \$T<\$L>(\$L)", structClass, getChosenTypesCode(chosenTypes), arguments)
                    }
                }
            }
        }
    }

    private fun writeStructClass(struct: Struct, className: ClassName): TypeSpec.Builder {
        val builder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL)

        if (struct.typeParameters.isNotEmpty()) {
            builder.addTypeVariables(struct.typeParameters.map { paramName -> TypeVariableName.get(paramName) })
        }
        addToTypeVariableScope(struct.typeParameters)

        val constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)

        struct.members.forEach { member ->
            val javaType = getType(member.type)
            builder.addField(javaType, member.name, Modifier.PUBLIC, Modifier.FINAL)

            constructor.addParameter(javaType, member.name)
            constructor.addStatement("this.\$L = \$L", member.name, member.name)
        }

        removeFromTypeVariableScope(struct.typeParameters)

        builder.addMethod(constructor.build())

        return builder
    }

    private fun writeMethod(function: ValidatedFunction): MethodSpec {
        // TODO: Eventually, support non-static methods
        val builder = MethodSpec.methodBuilder(function.id.functionName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)

        for (typeParameter in function.typeParameters) {
            builder.addTypeVariable(TypeVariableName.get(typeParameter))
        }
        addToTypeVariableScope(function.typeParameters)

        function.arguments.forEach { argument ->
            builder.addParameter(getType(argument.type), argument.name)
        }

        builder.returns(getType(function.returnType))

        // TODO: Add block here
        builder.addCode(writeBlock(function.block, null))

        removeFromTypeVariableScope(function.typeParameters)

        return builder.build()
    }

    // TODO: Multiset
    val typeVariablesCount = HashMap<String, Int>()

    private fun addToTypeVariableScope(typeParameters: List<String>) {
        typeParameters.forEach(this::addToTypeVariableScope)
    }

    private fun addToTypeVariableScope(typeParameter: String) {
        val existingCount = typeVariablesCount[typeParameter]
        if (existingCount != null) {
            typeVariablesCount[typeParameter] = existingCount + 1
        } else {
            typeVariablesCount[typeParameter] = 1
        }
    }

    private fun removeFromTypeVariableScope(typeParameters: List<String>) {
        typeParameters.forEach(this::removeFromTypeVariableScope)
    }

    private fun removeFromTypeVariableScope(typeParameter: String) {
        val existingCount = typeVariablesCount[typeParameter] ?: error("Error in type parameter count tracking for $typeParameter")
        typeVariablesCount[typeParameter] = existingCount - 1
    }

    private fun isInTypeParameterScope(semlangType: Type.NamedType): Boolean {
        if (semlangType.id.thePackage.strings.isEmpty() && semlangType.parameters.isEmpty()) {
            val count = typeVariablesCount[semlangType.id.functionName]
            return count != null && count > 0
        }
        return false
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
                builder.beginControlFlow("if (\$L)", writeExpression(expression.condition))
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
                functionCallStrategy.apply(expression.chosenParameters.map(this::getType), getArgumentsBlock(expression.arguments))
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
            is TypedExpression.NamedFunctionBinding -> {
                writeNamedFunctionBinding(expression)
            }
            is TypedExpression.ExpressionFunctionBinding -> TODO()
        }
    }

    private fun writeNamedFunctionBinding(expression: TypedExpression.NamedFunctionBinding): CodeBlock {
        val functionId = expression.functionId
        // TODO: Optimize
        val signature = context.getAllFunctionSignatures()[functionId] ?: error("Signature not found for $functionId")
        // TODO: Be able to get this for native functions, as well (put in signatures, probably)
        val referencedFunction = context.getFunctionImplementation(functionId)

        val containingClass = getContainingClassName(functionId)

        // TODO: More compact references when not binding arguments
        val functionCallStrategy = getNamedFunctionCallStrategy(functionId)

        val unboundArgumentNames = ArrayList<String>()
        val arguments = ArrayList<CodeBlock>()
        // Lambda expression
        expression.bindings.forEachIndexed { index, binding ->
            if (binding == null) {
                val argumentName = if (referencedFunction != null) {
                    referencedFunction.arguments[index].name
                } else {
                    "arg" + index
                }
                unboundArgumentNames.add(argumentName)
                arguments.add(CodeBlock.of("\$L", argumentName))
//                val unparameterizedArgType = signature.argumentTypes[index]
//                val argType = unparameterizedArgType.replacingParameters(signature.typeParameters.zip(expression.chosenParameters).toMap())
//                arguments.add(TypedExpression.Variable(argType, argumentName))
            } else {
                arguments.add(writeExpression(binding))
            }
        }

        val chosenTypes = expression.chosenParameters.map(this::getType)

        val functionCall = functionCallStrategy.apply(chosenTypes, joinWithCommas(arguments))
        return CodeBlock.of("(\$L) -> \$L", unboundArgumentNames.joinToString(", "), functionCall)
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
            override fun apply(chosenTypes: List<TypeName>, arguments: CodeBlock): CodeBlock {
                if (chosenTypes.isEmpty()) {
                    return CodeBlock.of("\$T.\$L(\$L)", classContainingFunction, functionId.functionName, arguments)
                } else {
                    return CodeBlock.of("\$T.<\$L>\$L(\$L)", classContainingFunction, getChosenTypesCode(chosenTypes), functionId.functionName, arguments)
                }
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
            is Type.FunctionType -> getFunctionType(semlangType)
            is Type.NamedType -> getNamedType(semlangType)
        }
    }

    private fun getFunctionType(semlangType: Type.FunctionType): TypeName {
        val strategy = getFunctionTypeStrategy(semlangType)

        return strategy.getTypeName(semlangType, this::getType)
    }

    private fun getNamedType(semlangType: Type.NamedType): TypeName {
        if (isInTypeParameterScope(semlangType)) {
            return TypeVariableName.get(semlangType.id.functionName)
        }

        // TODO: The right general approach is going to be "find the context containing this type, and use the
        // associated package name for that"

        // TODO: Might end up being more complicated? This is probably not quite right
        val className = ClassName.bestGuess(javaPackage.joinToString(".") + "." + semlangType.id.toString())

        if (semlangType.parameters.isEmpty()) {
            return className
        } else {
            val parameterTypeNames: List<TypeName> = semlangType.parameters.map(this::getType)
            return ParameterizedTypeName.get(className, *parameterTypeNames.toTypedArray())
        }
    }

    private fun getStructClassName(functionId: FunctionId): ClassName {
        return ClassName.get((javaPackage + functionId.thePackage.strings).joinToString("."), functionId.functionName)
    }

    private fun getContainingClassName(functionId: FunctionId): ClassName {
        //Assume the first capitalized string is the className
        val allParts = (javaPackage + functionId.thePackage.strings) + functionId.functionName
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
            return ClassName.get((javaPackage + functionId.thePackage.strings).joinToString("."), "Functions")
//        error("Function ID without capitalized part: $functionId")
        }
        return ClassName.get(packageParts.joinToString("."), className)
    }

//    val testClassNames = ArrayList<String>()
    val testClassCounts = LinkedHashMap<String, Int>()
    val testClassBuilders = LinkedHashMap<ClassName, TypeSpec.Builder>()
    private fun prepareJUnitTest(function: ValidatedFunction, testContents: TestAnnotationContents) {
        val className = getContainingClassName(function.id)
        val testClassName = ClassName.bestGuess(className.toString() + "Test")

        val curCount: Int? = testClassCounts[testClassName.toString()]
        val newCount: Int = if (curCount == null) 1 else (curCount + 1)
        testClassCounts[testClassName.toString()] = newCount
//        testClassNames.add(testClassName.toString())

        val outputExpression = TypedExpression.Literal(function.returnType, testContents.outputLiteral)
        val outputCode = writeLiteralExpression(outputExpression)
        val argExpressions = function.arguments.map { arg -> arg.type }
                .zip(testContents.argLiterals)
                .map { (type, literal) -> TypedExpression.Literal(type, literal) }
        val argsCode = getArgumentsBlock(argExpressions)

        if (function.typeParameters.isNotEmpty()) {
            TODO()
        }

        val runFakeTest = MethodSpec.methodBuilder("runUnitTest" + newCount)
                .addModifiers(Modifier.PUBLIC)
                .returns(Void.TYPE)
                .addAnnotation(ClassName.bestGuess("org.junit.Test"))
//                .addStatement("\$T.out.println(\$S)", System::class.java, "I am a fake test!")
                .addStatement("\$T.assertEquals(\$L, \$L)",
                        ClassName.bestGuess("org.junit.Assert"),
                        outputCode,
                        getNamedFunctionCallStrategy(function.id).apply(listOf(), argsCode)
                )
                .build()

        val existingTestClass = testClassBuilders[testClassName]
        if (existingTestClass == null) {
            val testClass = TypeSpec.classBuilder(testClassName)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addMethod(runFakeTest)
            testClassBuilders[testClassName] = testClass
        } else {
            existingTestClass.addMethod(runFakeTest)
        }
//
//        val testClass = TypeSpec.classBuilder(testClassName)
//                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
//                .addMethod(runFakeTest)
//                .build()

        //TODO: Replace this
//        val javaFile = JavaFile.builder(testClassName.packageName(), testClass).build()
//        javaFile.writeTo(newTestSrcDir)
    }

    private fun writePreparedJUnitTests(newTestSrcDir: File) {
        testClassBuilders.entries.forEach { (testClassName, builder) ->
            val testClass = builder.build()

            val javaFile = JavaFile.builder(testClassName.packageName(), testClass).build()
            javaFile.writeTo(newTestSrcDir)
        }
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

    val integer = Package(listOf("Integer"))
    val javaIntegers = ClassName.bestGuess("net.semlang.java.Integers")
    // TODO: Add ability to use non-static function calls
    map.put(FunctionId(integer, "plus"), getStaticFunctionCall(javaIntegers, "plus"))
    map.put(FunctionId(integer, "minus"), getStaticFunctionCall(javaIntegers, "minus"))
    map.put(FunctionId(integer, "times"), getStaticFunctionCall(javaIntegers, "times"))
    map.put(FunctionId(integer, "equals"), getStaticFunctionCall(javaIntegers, "equals"))
    map.put(FunctionId(integer, "fromNatural"), PassedThroughVarFunctionCallStrategy)

    val natural = Package(listOf("Natural"))
    val javaNaturals = ClassName.bestGuess("net.semlang.java.Naturals")
    // Share implementations with Integer in some cases
    map.put(FunctionId(natural, "plus"), getStaticFunctionCall(javaIntegers, "plus"))
    map.put(FunctionId(natural, "times"), getStaticFunctionCall(javaIntegers, "times"))
    map.put(FunctionId(natural, "lesser"), getStaticFunctionCall(javaIntegers, "lesser"))
    map.put(FunctionId(natural, "equals"), getStaticFunctionCall(javaIntegers, "equals"))

    map.put(FunctionId(natural, "absoluteDifference"), getStaticFunctionCall(javaNaturals, "absoluteDifference"))

    return map
}

//private fun getStaticFunctionCall(className: String, methodName: String): StaticFunctionCallStrategy {
//    return getStaticFunctionCall(ClassName.bestGuess(className), methodName)
//}
private fun getStaticFunctionCall(className: ClassName, methodName: String): StaticFunctionCallStrategy {
    return StaticFunctionCallStrategy(className, methodName)
}

private interface FunctionCallStrategy {
    fun apply(chosenTypes: List<TypeName>, arguments: CodeBlock): CodeBlock
}

class StaticFunctionCallStrategy(val className: ClassName, val methodName: String): FunctionCallStrategy {
    override fun apply(chosenTypes: List<TypeName>, arguments: CodeBlock): CodeBlock {
        if (chosenTypes.isNotEmpty()) {
            return CodeBlock.of("\$T.<\$L>\$L(\$L)", className, getChosenTypesCode(chosenTypes), methodName, arguments)
        }
        return CodeBlock.of("\$T.\$L(\$L)", className, methodName, arguments)
    }
}

object PassedThroughVarFunctionCallStrategy: FunctionCallStrategy {
    override fun apply(chosenTypes: List<TypeName>, arguments: CodeBlock): CodeBlock {
        return arguments
    }
}

private fun getFunctionTypeStrategy(type: Type.FunctionType): FunctionTypeStrategy {
    if (type.argTypes.size == 0) {
        return SupplierFunctionTypeStrategy
    } else if (type.argTypes.size == 1) {
        return FunctionFunctionTypeStrategy
    }

    TODO()
}

object SupplierFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName {
        if (type.argTypes.isNotEmpty()) {
            error("")
        }
        val className = ClassName.get(java.util.function.Supplier::class.java)

        return ParameterizedTypeName.get(className, getType(type.outputType))
    }
}

object FunctionFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName {
        if (type.argTypes.size != 1) {
            error("")
        }
        val className = ClassName.get(java.util.function.Function::class.java)

        return ParameterizedTypeName.get(className, getType(type.argTypes[0]),  getType(type.outputType))
    }

}

private interface FunctionTypeStrategy {
    fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName
}

private fun joinWithCommas(blocks: ArrayList<CodeBlock>): CodeBlock {
    if (blocks.isEmpty()) {
        return CodeBlock.of("")
    }

    val builder = CodeBlock.builder()
    builder.add(blocks[0])
    blocks.drop(1).forEach { block ->
        builder.add(", \$L", block)
    }
    return builder.build()
}

private fun getChosenTypesCode(chosenTypes: List<TypeName>): CodeBlock {
    val typesCodeBuilder = CodeBlock.builder()
    typesCodeBuilder.add("\$T", chosenTypes[0])
    for (chosenType in chosenTypes.drop(1)) {
        typesCodeBuilder.add(", \$T", chosenType)
    }
    return typesCodeBuilder.build()
}
