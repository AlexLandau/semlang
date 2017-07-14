import com.squareup.javapoet.*
import semlang.api.*
import semlang.internal.test.TestAnnotationContents
import semlang.internal.test.parseTestAnnotationContents
import java.io.File
import java.math.BigInteger
import javax.lang.model.element.Modifier

/**
 * TODO:
 * - Needed preprocessing step: Put if-then statements only at the top level (assignment or return statement)
 *   - If no existing tests run into this problem, create a test that does so
 * - Use a preprocessing step to change the Adapter types
 * - Preprocess to identify places where Sequence.first() calls should be turned into while loops
 * - Add variables to the scope in more places (and remove when finished)
 * - This definitely has remaining bugs
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
        namedFunctionCallStrategies.putAll(getNativeFunctionCallStrategies())

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

        context.ownInterfaces.values.forEach { interfac ->
            val className = getStructClassName(interfac.id)
            val interfaceBuilder = writeInterface(interfac, className)

            if (classMap.containsKey(className)) {
                error("Something's wrong here")
            }
            classMap[className] = interfaceBuilder
        }
        // Enable calls to instance and adapter constructors
        addInstanceConstructorFunctionCallStrategies(context.getAllInterfaces().values)
        addAdapterConstructorFunctionCallStrategies(context.getAllInterfaces().values)

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

        writePreparedJUnitTests(newTestSrcDir)

        return WrittenJavaInfo(testClassCounts.keys.toList())
    }

    private fun addStructConstructorFunctionCallStrategies(structs: Collection<Struct>) {
        for (struct in structs) {
            val structClass = getStructClassName(struct.id)
            namedFunctionCallStrategies[struct.id] = object: FunctionCallStrategy {
                override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                    if (chosenTypes.isEmpty()) {
                        return CodeBlock.of("new \$T(\$L)", structClass, getArgumentsBlock(arguments))
                    } else {
                        return CodeBlock.of("new \$T<\$L>(\$L)", structClass, getChosenTypesCode(chosenTypes), getArgumentsBlock(arguments))
                    }
                }
            }
        }
    }

    private fun addInstanceConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        interfaces.forEach { interfac ->
            namedFunctionCallStrategies[interfac.id] = object: FunctionCallStrategy {
                override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                    if (arguments.size != 2) {
                        error("Interface constructors should have exactly 2 arguments")
                    }
                    return CodeBlock.of("\$L.from(\$L)", writeExpression(arguments[1]), writeExpression(arguments[0]))
                }
            }
        }
    }

    private fun addAdapterConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        interfaces.forEach { interfac ->
            val instanceClassName = getStructClassName(interfac.id)

            val javaAdapterClassName = ClassName.bestGuess("net.semlang.java.Adapter")

            namedFunctionCallStrategies[interfac.adapterId] = object: FunctionCallStrategy {
                override fun apply(chosenTypes: List<Type>, constructorArgs: List<TypedExpression>): CodeBlock {
                    if (chosenTypes.isEmpty()) { error("") }
                    val dataType = chosenTypes[0]
                    val dataTypeName = getType(dataType)
                    val dataVarName = ensureUnusedVariable("data")
                    val interfaceParameters = chosenTypes.drop(1)
                    val interfaceParameterNames = interfaceParameters.map(this@JavaCodeWriter::getType)

                    val instanceType = if (interfaceParameters.isEmpty()) {
                        instanceClassName
                    } else {
                        ParameterizedTypeName.get(instanceClassName, *interfaceParameterNames.toTypedArray())
                    }

                    val instanceInnerClass = getInstanceInnerClassForAdapter(instanceType, interfac, constructorArgs,
                            dataType, interfaceParameters, dataVarName)

                    val javaAdapterTypeName = ParameterizedTypeName.get(javaAdapterClassName, instanceType, dataTypeName)

                    val adapterInnerClass = TypeSpec.anonymousClassBuilder("").addSuperinterface(javaAdapterTypeName)

                    val fromMethodBuilder = MethodSpec.methodBuilder("from").addModifiers(Modifier.PUBLIC)
                            .addAnnotation(Override::class.java)
                            .addParameter(dataTypeName, dataVarName)
                            .returns(instanceType)
                            .addStatement("return \$L", instanceInnerClass)

                    adapterInnerClass.addMethod(fromMethodBuilder.build())

                    return CodeBlock.of("\$L", adapterInnerClass.build())
                }
            }
        }
    }

    private fun getInstanceInnerClassForAdapter(instanceClassName: TypeName, interfac: Interface, constructorArgs: List<TypedExpression>,
                                                dataType: Type, interfaceParameters: List<Type>, dataVarName: String): TypeSpec {
        val builder = TypeSpec.anonymousClassBuilder("").addSuperinterface(instanceClassName)

        interfac.methods.zip(constructorArgs).forEach { (method, constructorArg) ->

            val typeReplacements = interfac.typeParameters.map{s -> Type.NamedType.forParameter(s) as Type}.zip(interfaceParameters).toMap()
            val methodBuilder = writeInterfaceMethod(method, false, typeReplacements)
            methodBuilder.addAnnotation(Override::class.java)

            when (constructorArg) {
                is TypedExpression.NamedFunctionBinding -> {
                    val callArgs = ArrayList<TypedExpression>()
                    callArgs.add(TypedExpression.Variable(dataType, dataVarName))

                    method.arguments.zip(constructorArg.bindings).forEach { (methodArg, binding) ->
                        if (binding == null) {
                            callArgs.add(TypedExpression.Variable(methodArg.type, methodArg.name))
                        } else {
                            callArgs.add(binding)
                        }
                    }

                    val functionCallStrategy = getNamedFunctionCallStrategy(constructorArg.functionId)
                    val functionCall = functionCallStrategy.apply(constructorArg.chosenParameters, callArgs)
                    methodBuilder.addStatement("return \$L", functionCall)
                }
                is TypedExpression.ExpressionFunctionBinding -> {
                    TODO()
                }
                else -> {
                    //error("Arguments of adapter constructors should be function bindings; instead, we got $constructorArg")
                    // So how do we do expression function calls?
                    val functionCallStrategy = getExpressionFunctionCallStrategy(constructorArg)
                    // In this case, we pass through the args as-is
                    val callArgs = ArrayList<TypedExpression>()
                    callArgs.add(TypedExpression.Variable(dataType, dataVarName))

                    method.arguments.forEach { methodArg ->
                        callArgs.add(TypedExpression.Variable(methodArg.type, methodArg.name))
                    }

                    val functionCall = functionCallStrategy.apply(listOf(), callArgs)

                    methodBuilder.addStatement("return \$L", functionCall)
                }
            }

            builder.addMethod(methodBuilder.build())
        }

        val instanceConstructor = builder.build()
        return instanceConstructor
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

        builder.addMethod(constructor.build())

        removeFromTypeVariableScope(struct.typeParameters)

        return builder
    }

    private fun writeInterface(interfac: Interface, className: ClassName): TypeSpec.Builder {
        // General strategy:
        // Interface types become Java interfaces
        // Adapter types become an Adapter<I, D> library type with a single method replacing the instance constructor
        // Adapter constructors become anonymous inner classes implementing the Adapter type
        val builder = TypeSpec.interfaceBuilder(className).addModifiers(Modifier.PUBLIC)

        builder.addTypeVariables(interfac.typeParameters.map { name -> TypeVariableName.get(name) })
        addToTypeVariableScope(interfac.typeParameters)

        interfac.methods.forEach { method ->
            builder.addMethod(writeInterfaceMethod(method).build())
        }

        removeFromTypeVariableScope(interfac.typeParameters)

        return builder
    }

    private fun writeInterfaceMethod(method: Method, makeAbstract: Boolean = true, typeReplacements: Map<Type, Type> = mapOf()): MethodSpec.Builder {
        val builder = MethodSpec.methodBuilder(method.name).addModifiers(Modifier.PUBLIC)
        if (makeAbstract) {
            builder.addModifiers(Modifier.ABSTRACT)
        }

        if (method.typeParameters.isNotEmpty()) {
            TODO()
        }

        method.arguments.forEach { argument ->
            builder.addParameter(getType(argument.type.replacingParameters(typeReplacements)), argument.name)
        }
        builder.returns(getType(method.returnType.replacingParameters(typeReplacements)))

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
            addToVariableScope(argument.name)
        }

        builder.returns(getType(function.returnType))

        // TODO: Add block here
        builder.addCode(writeBlock(function.block, null))

        removeFromTypeVariableScope(function.typeParameters)
        removeFromVariableScope(function.arguments.map(Argument::name))

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
                functionCallStrategy.apply(expression.chosenParameters, expression.arguments)
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val functionCallStrategy = getExpressionFunctionCallStrategy(expression.functionExpression)
                functionCallStrategy.apply(expression.chosenParameters, expression.arguments)
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
            is TypedExpression.ExpressionFunctionBinding -> {
                writeExpressionFunctionBinding(expression)
            }
        }
    }

    private fun writeExpressionFunctionBinding(expression: TypedExpression.ExpressionFunctionBinding): CodeBlock {
        // TODO: Deduplicate

        val functionCallStrategy = getExpressionFunctionCallStrategy(expression.functionExpression)

        val unboundArgumentNames = ArrayList<String>()
        val arguments = ArrayList<TypedExpression>()
        // Lambda expression
        val outputType = expression.type as? Type.FunctionType ?: error("")
        var unboundArgumentIndex = 0
        expression.bindings.forEachIndexed { index, binding ->
            if (binding == null) {
                // TODO: Pick better names based on types
                val argumentName = ensureUnusedVariable("arg" + index)
                unboundArgumentNames.add(argumentName)

                val argType = outputType.argTypes[unboundArgumentIndex]

                arguments.add(TypedExpression.Variable(argType, argumentName))
                unboundArgumentIndex++
            } else {
                arguments.add(binding)
            }
        }

        val functionCall = functionCallStrategy.apply(expression.chosenParameters, arguments)
        return CodeBlock.of("(\$L) -> \$L", unboundArgumentNames.joinToString(", "), functionCall)
    }

    private fun writeNamedFunctionBinding(expression: TypedExpression.NamedFunctionBinding): CodeBlock {
        val functionId = expression.functionId
        val signature = context.getFunctionSignature(functionId) ?: error("Signature not found for $functionId")
        // TODO: Be able to get this for native functions, as well (put in signatures, probably)
        val referencedFunction = context.getFunctionImplementation(functionId)

        // TODO: More compact references when not binding arguments
        val functionCallStrategy = getNamedFunctionCallStrategy(functionId)

        val unboundArgumentNames = ArrayList<String>()
        val arguments = ArrayList<TypedExpression>()
        // Lambda expression
        expression.bindings.forEachIndexed { index, binding ->
            if (binding == null) {
                val argumentName = ensureUnusedVariable(if (referencedFunction != null) {
                    referencedFunction.arguments[index].name
                } else {
                    // TODO: Pick better names based on types
                    "arg" + index
                })
                unboundArgumentNames.add(argumentName)

                val unparameterizedArgType = signature.argumentTypes[index]
                val argType = unparameterizedArgType.replacingParameters(signature.typeParameters.zip(expression.chosenParameters).toMap())
                arguments.add(TypedExpression.Variable(argType, argumentName))
            } else {
                arguments.add(binding)
            }
        }

        val functionCall = functionCallStrategy.apply(expression.chosenParameters, arguments)
        return CodeBlock.of("(\$L) -> \$L", unboundArgumentNames.joinToString(", "), functionCall)
    }

    val varsInScope = HashSet<String>()
    private fun removeFromVariableScope(varNames: List<String>) {
        varsInScope.removeAll(varNames)
    }
    private fun addToVariableScope(varName: String) {
        varsInScope.add(varName)
    }
    private fun ensureUnusedVariable(initialVarName: String): String {
        //TODO: Fix up to work better, and/or factor out into a utility method in a neutral-ish project
        var varName = initialVarName
        while (varsInScope.contains(varName)) {
            varName = "_" + varName
        }
        return varName
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

    // This gets populated early on in the write() method.
    val namedFunctionCallStrategies = HashMap<FunctionId, FunctionCallStrategy>()

    private fun getNamedFunctionCallStrategy(functionId: FunctionId): FunctionCallStrategy {
        val cached = namedFunctionCallStrategies[functionId]
        if (cached != null) {
            return cached
        }

        val classContainingFunction = getContainingClassName(functionId)
        val strategy = object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                if (chosenTypes.isEmpty()) {
                    return CodeBlock.of("\$T.\$L(\$L)", classContainingFunction, functionId.functionName, getArgumentsBlock(arguments))
                } else {
                    return CodeBlock.of("\$T.<\$L>\$L(\$L)", classContainingFunction, getChosenTypesCode(chosenTypes), functionId.functionName, getArgumentsBlock(arguments))
                }
            }
        }
        namedFunctionCallStrategies[functionId] = strategy
        return strategy
    }

    private fun getExpressionFunctionCallStrategy(expression: TypedExpression): FunctionCallStrategy {

        // Is it an interface follow?
        if (expression is TypedExpression.Follow) {
            val followedExpression = expression.expression
            val followedExpressionType = followedExpression.type
            if (followedExpressionType is Type.NamedType) {
                val followedInterface = context.getInterface(followedExpressionType.id)
                if (followedInterface != null) {
                    return object: FunctionCallStrategy {
                        override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                            return CodeBlock.of("\$L.\$L(\$L)", writeExpression(followedExpression), expression.name, getArgumentsBlock(arguments))
                        }
                    }
                }
            }
        }

        return object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                return CodeBlock.of("\$L.apply(\$L)", writeExpression(expression), getArgumentsBlock(arguments))
            }
        }
    }

    private fun writeLiteralExpression(expression: TypedExpression.Literal): CodeBlock {
        return writeLiteralExpression(expression.type, expression.literal)
    }
    private fun writeLiteralExpression(type: Type, literal: String): CodeBlock {
        return when (type) {
            Type.INTEGER -> {
                CodeBlock.of("new \$T(\$S)", BigInteger::class.java, literal)
            }
            Type.NATURAL -> {
                CodeBlock.of("new \$T(\$S)", BigInteger::class.java, literal)
            }
            Type.BOOLEAN -> {
                CodeBlock.of("\$L", literal)
            }
            is Type.List -> error("List literals not supported")
            is Type.Try -> {
                // We need to support this for unit tests, specifically
                if (literal == "failure") {
                    return CodeBlock.of("\$T.empty()", java.util.Optional::class.java)
                }
                if (literal.startsWith("success(") && literal.endsWith(")")) {
                    val innerType = type.parameter
                    val innerLiteral = literal.substring("success(".length, literal.length - ")".length)
                    return CodeBlock.of("\$T.of(\$L)", java.util.Optional::class.java, writeLiteralExpression(innerType, innerLiteral))
                }
                throw IllegalArgumentException("Unhandled literal \"$literal\" of type $type")
            }
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

        if (semlangType.id.functionName == "Adapter") {
            val parts = semlangType.id.thePackage.strings
            if (parts.isNotEmpty()) {
                val newName = parts.last()
                // TODO: Namespace terminology elsewhere
                val newNamespace = parts.dropLast(1)
                val interfaceId = FunctionId(Package(newNamespace), newName)
                val interfac = context.getInterface(interfaceId)
                if (interfac != null) {
                    val bareAdapterClass = ClassName.bestGuess("net.semlang.java.Adapter")
                    val dataTypeParameter = getType(semlangType.parameters[0])
                    val otherParameters = semlangType.parameters.drop(1).map{t -> getType(t)}
                    val bareInterfaceName = getStructClassName(interfaceId)
                    val interfaceJavaName = if (otherParameters.isEmpty()) {
                        bareInterfaceName
                    } else {
                        ParameterizedTypeName.get(bareInterfaceName, *otherParameters.toTypedArray())
                    }
                    return ParameterizedTypeName.get(bareAdapterClass, interfaceJavaName, dataTypeParameter)
                }
            }
        }

        val predefinedClassName: ClassName? = if (semlangType.id.thePackage.strings.isEmpty()) {
            if (semlangType.id.functionName == "Sequence") {
                ClassName.bestGuess("net.semlang.java.Sequence")
            } else {
                null
            }
        } else {
            null
        }

        // TODO: The right general approach is going to be "find the context containing this type, and use the
        // associated package name for that"

        // TODO: Might end up being more complicated? This is probably not quite right
        val className = predefinedClassName ?: ClassName.bestGuess(javaPackage.joinToString(".") + "." + semlangType.id.toString())

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
        }
        return ClassName.get(packageParts.joinToString("."), className)
    }

    val testClassCounts = LinkedHashMap<String, Int>()
    val testClassBuilders = LinkedHashMap<ClassName, TypeSpec.Builder>()
    private fun prepareJUnitTest(function: ValidatedFunction, testContents: TestAnnotationContents) {
        val className = getContainingClassName(function.id)
        val testClassName = ClassName.bestGuess(className.toString() + "Test")

        val curCount: Int? = testClassCounts[testClassName.toString()]
        val newCount: Int = if (curCount == null) 1 else (curCount + 1)
        testClassCounts[testClassName.toString()] = newCount

        val outputExpression = TypedExpression.Literal(function.returnType, testContents.outputLiteral)
        val outputCode = writeLiteralExpression(outputExpression)
        val argExpressions = function.arguments.map { arg -> arg.type }
                .zip(testContents.argLiterals)
                .map { (type, literal) -> TypedExpression.Literal(type, literal) }

        if (function.typeParameters.isNotEmpty()) {
            TODO()
        }

        val runFakeTest = MethodSpec.methodBuilder("runUnitTest" + newCount)
                .addModifiers(Modifier.PUBLIC)
                .returns(Void.TYPE)
                .addAnnotation(ClassName.bestGuess("org.junit.Test"))
                .addStatement("\$T.assertEquals(\$L, \$L)",
                        ClassName.bestGuess("org.junit.Assert"),
                        outputCode,
                        getNamedFunctionCallStrategy(function.id).apply(listOf(), argExpressions)
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
    }

    private fun writePreparedJUnitTests(newTestSrcDir: File) {
        testClassBuilders.entries.forEach { (testClassName, builder) ->
            val testClass = builder.build()

            val javaFile = JavaFile.builder(testClassName.packageName(), testClass).build()
            javaFile.writeTo(newTestSrcDir)
        }
    }


    private fun getNativeFunctionCallStrategies(): Map<FunctionId, FunctionCallStrategy> {
        val map = HashMap<FunctionId, FunctionCallStrategy>()

        val boolean = Package(listOf("Boolean"))
        val javaBooleans = ClassName.bestGuess("net.semlang.java.Booleans")
        // TODO: Replace these with the appropriate operators (in parentheses)
        map.put(FunctionId(boolean, "and"), StaticFunctionCallStrategy(javaBooleans, "and"))
        map.put(FunctionId(boolean, "or"), StaticFunctionCallStrategy(javaBooleans, "or"))
        map.put(FunctionId(boolean, "not"), StaticFunctionCallStrategy(javaBooleans, "not"))

        val list = Package(listOf("List"))
        val javaLists = ClassName.bestGuess("net.semlang.java.Lists")
        map.put(FunctionId(list, "empty"), StaticFunctionCallStrategy(javaLists, "empty"))
        // TODO: Find an approach to remove most uses of append where we'd be better off with e.g. add
        map.put(FunctionId(list, "append"), StaticFunctionCallStrategy(javaLists, "append"))
        // TODO: Find an approach where we can replace this with a simple .get() call...
        // Harder than it sounds, given the BigInteger input; i.e. we need to intelligently replace with a "Size"/"Index" type
        map.put(FunctionId(list, "get"), StaticFunctionCallStrategy(javaLists, "get"))
        map.put(FunctionId(list, "size"), StaticFunctionCallStrategy(javaLists, "size"))

        val integer = Package(listOf("Integer"))
        // TODO: Add ability to use non-static function calls
        map.put(FunctionId(integer, "plus"), MethodFunctionCallStrategy("add"))
        map.put(FunctionId(integer, "minus"), MethodFunctionCallStrategy("subtract"))
        map.put(FunctionId(integer, "times"), MethodFunctionCallStrategy("multiply"))
        map.put(FunctionId(integer, "equals"), MethodFunctionCallStrategy("equals"))
        map.put(FunctionId(integer, "fromNatural"), PassedThroughVarFunctionCallStrategy)

        val natural = Package(listOf("Natural"))
        val javaNaturals = ClassName.bestGuess("net.semlang.java.Naturals")
        // Share implementations with Integer in some cases
        map.put(FunctionId(natural, "plus"), MethodFunctionCallStrategy("add"))
        map.put(FunctionId(natural, "times"), MethodFunctionCallStrategy("multiply"))
        map.put(FunctionId(natural, "lesser"), MethodFunctionCallStrategy("min"))
        map.put(FunctionId(natural, "equals"), MethodFunctionCallStrategy("equals"))
        map.put(FunctionId(natural, "absoluteDifference"), StaticFunctionCallStrategy(javaNaturals, "absoluteDifference"))

        val tries = Package(listOf("Try"))
        val javaTries = ClassName.bestGuess("net.semlang.java.Tries")
        map.put(FunctionId(tries, "assume"), StaticFunctionCallStrategy(javaTries, "assume"))

        val sequence = Package(listOf("Sequence"))
        val javaSequences = ClassName.bestGuess("net.semlang.java.Sequences")
        map.put(FunctionId(sequence, "create"), StaticFunctionCallStrategy(javaSequences, "create"))

        return map
    }

    val PassedThroughVarFunctionCallStrategy = object: FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
            if (arguments.size != 1) error("")
            return writeExpression(arguments[0])
        }
    }

    inner class StaticFunctionCallStrategy(val className: ClassName, val methodName: String): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
            if (chosenTypes.isNotEmpty()) {
                return CodeBlock.of("\$T.<\$L>\$L(\$L)", className, getChosenTypesCode(chosenTypes), methodName, getArgumentsBlock(arguments))
            }
            return CodeBlock.of("\$T.\$L(\$L)", className, methodName, getArgumentsBlock(arguments))
        }
    }

    inner class MethodFunctionCallStrategy(val methodName: String): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
            return CodeBlock.of("\$L.\$L(\$L)", writeExpression(arguments[0]), methodName, getArgumentsBlock(arguments.drop(1)))
        }
    }

    private fun getChosenTypesCode(chosenSemlangTypes: List<Type>): CodeBlock {
        val chosenTypes = chosenSemlangTypes.map(this::getType)
        val typesCodeBuilder = CodeBlock.builder()
        typesCodeBuilder.add("\$T", chosenTypes[0])
        for (chosenType in chosenTypes.drop(1)) {
            typesCodeBuilder.add(", \$T", chosenType)
        }
        return typesCodeBuilder.build()
    }

}

private interface FunctionCallStrategy {
    fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock
}

private fun getFunctionTypeStrategy(type: Type.FunctionType): FunctionTypeStrategy {
    if (type.argTypes.size == 0) {
        return SupplierFunctionTypeStrategy
    } else if (type.argTypes.size == 1) {
        return FunctionFunctionTypeStrategy
    }

    if (type.argTypes.size < 4) {
        return RuntimeLibraryFunctionTypeStrategy(type.argTypes.size)
    }
    /*
     * Quick/easy workaround: Add a few types for 2, 3, etc.
     * In the general case, at what point do we shift to programmatic generation, and how
     * will that work?
     */
    TODO()
}

object SupplierFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName {
        if (type.argTypes.isNotEmpty()) {
            error("")
        }
        val className = ClassName.get(java.util.function.Supplier::class.java)

        return ParameterizedTypeName.get(className, getType(type.outputType).box())
    }
}

object FunctionFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName {
        if (type.argTypes.size != 1) {
            error("")
        }
        val className = ClassName.get(java.util.function.Function::class.java)

        return ParameterizedTypeName.get(className, getType(type.argTypes[0]).box(),  getType(type.outputType).box())
    }

}

class RuntimeLibraryFunctionTypeStrategy(val numArgs: Int): FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName {
        val className = ClassName.bestGuess("net.semlang.java.function.Function" + numArgs)

        val parameterTypes = ArrayList<TypeName>()
        for (semlangType in type.argTypes) {
            parameterTypes.add(getType(semlangType).box())
        }
        parameterTypes.add(getType(type.outputType).box())

        return ParameterizedTypeName.get(className, *parameterTypes.toTypedArray())
    }
}

private interface FunctionTypeStrategy {
    fun getTypeName(type: Type.FunctionType, getType: (Type) -> TypeName): TypeName
}
