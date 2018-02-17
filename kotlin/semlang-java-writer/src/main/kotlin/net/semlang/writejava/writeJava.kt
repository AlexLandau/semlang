package net.semlang.writejava

import com.squareup.javapoet.*
import net.semlang.api.*
import net.semlang.internal.test.TestAnnotationContents
import net.semlang.internal.test.verifyTestAnnotationContents
import net.semlang.interpreter.evaluateStringLiteral
import net.semlang.parser.validateModule
import net.semlang.transforms.RenamingStrategies
import net.semlang.transforms.constrainVariableNames
import net.semlang.transforms.extractInlineFunctions
import net.semlang.transforms.hoistMatchingExpressions
import java.io.File
import java.math.BigInteger
import java.util.*
import javax.lang.model.element.Modifier

/**
 * TODO:
 * - Use a preprocessing step to change the Adapter types
 * - Preprocess to identify places where Sequence.first() calls should be turned into while loops
 * - Add variables to the scope in more places (and remove when finished)
 * - This definitely has remaining bugs
 *
 * - Do we support multiple modules or require flattening to a single module?
 */

data class WrittenJavaInfo(val testClassNames: List<String>)

private val JAVA_KEYWORDS = setOf(
        "abstract",
        "assert",
        "boolean",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "class",
        "const",
        "continue",
        "default",
        "do",
        "double",
        "else",
        "enum",
        "extends",
        "false",
        "final",
        "finally",
        "float",
        "for",
        "goto",
        "if",
        "implements",
        "import",
        "instanceof",
        "int",
        "interface",
        "long",
        "native",
        "new",
        "null",
        "package",
        "private",
        "protected",
        "public",
        "return",
        "short",
        "static",
        "strictfp",
        "super",
        "switch",
        "synchronized",
        "this",
        "throw",
        "throws",
        "transient",
        "true",
        "try",
        "void",
        "volatile",
        "while"
)

fun writeJavaSourceIntoFolders(unprocessedModule: ValidatedModule, javaPackage: List<String>, newSrcDir: File, newTestSrcDir: File): WrittenJavaInfo {
    if (javaPackage.isEmpty()) {
        error("The Java package must be non-empty.")
    }
    // Pre-processing steps
    // TODO: Combine variable renaming steps
    val tempModule1 = constrainVariableNames(unprocessedModule, RenamingStrategies::avoidNumeralAtStartByPrependingUnderscores)
    val tempModule2 = constrainVariableNames(tempModule1, RenamingStrategies.getKeywordAvoidingStrategy(JAVA_KEYWORDS))
    val withoutInlineFunctions = extractInlineFunctions(tempModule2)
    val simplified = hoistMatchingExpressions(withoutInlineFunctions, { it is Expression.IfThen })
    val module = validateModule(simplified, unprocessedModule.id, unprocessedModule.nativeModuleVersion, unprocessedModule.upstreamModules.values.toList()).assumeSuccess()

    return JavaCodeWriter(module, javaPackage, newSrcDir, newTestSrcDir).write()
}

private class JavaCodeWriter(val module: ValidatedModule, val javaPackage: List<String>, val newSrcDir: File, val newTestSrcDir: File) {
    val classMap = HashMap<ClassName, TypeSpec.Builder>()

    fun write(): WrittenJavaInfo {
        namedFunctionCallStrategies.putAll(getNativeFunctionCallStrategies())

        module.ownStructs.values.forEach { struct ->
            val className = getStructClassName(struct.id)
            val structClassBuilder = writeStructClass(struct, className)

            if (classMap.containsKey(className)) {
                error("Something's wrong here")
            }
            classMap[className] = structClassBuilder
        }
        // Enable calls to struct constructors
        addStructConstructorFunctionCallStrategies(module.getAllInternalStructs().values)

        module.ownInterfaces.values.forEach { interfac ->
            val className = getStructClassName(interfac.id)
            val interfaceBuilder = writeInterface(interfac, className)

            if (classMap.containsKey(className)) {
                error("Something's wrong here")
            }
            classMap[className] = interfaceBuilder
        }
        // Enable calls to instance and adapter constructors
        addInstanceConstructorFunctionCallStrategies(module.getAllInternalInterfaces().values)
        addAdapterConstructorFunctionCallStrategies(module.getAllInternalInterfaces().values)

        module.ownFunctions.values.forEach { function ->

            val method = writeMethod(function)

            val className = getContainingClassName(function.id)
            if (!classMap.containsKey(className)) {
                classMap.put(className, TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL))
            }
            classMap[className]!!.addMethod(method)

            // Write unit tests for @Test annotations
            function.annotations.forEach { annotation ->
                if (annotation.name == "Test") {
                    val testContents = verifyTestAnnotationContents(annotation.values, function)

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
            // Check to avoid overriding special cases
            if (!namedFunctionCallStrategies.containsKey(struct.id)) {
                namedFunctionCallStrategies[struct.id] = object : FunctionCallStrategy {
                    override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                        if (chosenTypes.isEmpty()) {
                            // TODO: Deconfliction with methods actually named "create"
                            return CodeBlock.of("\$T.create(\$L)", structClass, getArgumentsBlock(arguments))
                        } else {
                            return CodeBlock.of("\$T.<\$L>create(\$L)", structClass, getChosenTypesCode(chosenTypes), getArgumentsBlock(arguments))
                        }
                    }
                }
            }
        }
    }

    private fun addInstanceConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        interfaces.forEach { interfac ->
            // Check to avoid overriding special cases
            if (!namedFunctionCallStrategies.containsKey(interfac.id)) {
                namedFunctionCallStrategies[interfac.id] = object : FunctionCallStrategy {
                    override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                        if (arguments.size != 2) {
                            error("Interface constructors should have exactly 2 arguments")
                        }
                        return CodeBlock.of("\$L.from(\$L)", writeExpression(arguments[1]), writeExpression(arguments[0]))
                    }
                }
            }
        }
    }

    private fun addAdapterConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        interfaces.forEach { interfac ->
            // Check to avoid overriding special cases
            if (!namedFunctionCallStrategies.containsKey(interfac.adapterId)) {
                val instanceClassName = getStructClassName(interfac.id)
                val javaAdapterClassName = ClassName.bestGuess("net.semlang.java.Adapter")

                namedFunctionCallStrategies[interfac.adapterId] = object : FunctionCallStrategy {
                    override fun apply(chosenTypes: List<Type>, constructorArgs: List<TypedExpression>): CodeBlock {
                        if (chosenTypes.isEmpty()) {
                            error("")
                        }
                        val dataType = chosenTypes[0]
                        val dataTypeName = getType(dataType, true)
                        val dataVarName = ensureUnusedVariable("data")
                        val interfaceParameters = chosenTypes.drop(1)
                        val interfaceParameterNames = interfaceParameters.map { this@JavaCodeWriter.getType(it, true) }

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
    }

    private fun getInstanceInnerClassForAdapter(instanceClassName: TypeName, interfac: Interface, constructorArgs: List<TypedExpression>,
                                                dataType: Type, interfaceParameters: List<Type>, dataVarName: String): TypeSpec {
        val builder = TypeSpec.anonymousClassBuilder("").addSuperinterface(instanceClassName)

        interfac.methods.zip(constructorArgs).forEach { (method, constructorArg) ->

            val typeReplacements = interfac.typeParameters.map{s -> Type.ParameterType(s) as Type}.zip(interfaceParameters).toMap()
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

                    val functionCallStrategy = getNamedFunctionCallStrategy(constructorArg.functionRef)
                    val functionCall = functionCallStrategy.apply(constructorArg.chosenParameters, callArgs)
                    methodBuilder.addStatement("return \$L", functionCall)
                }
                is TypedExpression.ExpressionFunctionBinding -> {
                    TODO()
                }
                else -> {
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

        val constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE)
        struct.members.forEach { member ->
            val javaType = getType(member.type, false)
            builder.addField(javaType, member.name, Modifier.PUBLIC, Modifier.FINAL)

            constructor.addParameter(javaType, member.name)
            constructor.addStatement("this.\$L = \$L", member.name, member.name)
        }
        builder.addMethod(constructor.build())

        val createMethod = MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        if (struct.typeParameters.isNotEmpty()) {
            createMethod.addTypeVariables(struct.typeParameters.map { paramName -> TypeVariableName.get(paramName) })
        }
        struct.members.forEach { member ->
            val javaType = getType(member.type, false)
            createMethod.addParameter(javaType, member.name)
        }
        val requires = struct.requires
        val constructorArgs = struct.members.map(Member::name)
        if (requires != null) {
            createMethod.returns(ParameterizedTypeName.get(ClassName.get(Optional::class.java), className))
            createMethod.addStatement("final boolean success")
            createMethod.addCode(writeBlock(requires, "success"))
            createMethod.beginControlFlow("if (success)")
            createMethod.addStatement("return \$T.of(new \$T(\$L))", Optional::class.java, className, constructorArgs.joinToString(", "))
            createMethod.nextControlFlow("else")
            createMethod.addStatement("return \$T.empty()", Optional::class.java)
            createMethod.endControlFlow()
        } else {
            createMethod.returns(className)
            createMethod.addStatement("return new \$T(\$L)", className, constructorArgs.joinToString(", "))
        }
        builder.addMethod(createMethod.build())

        return builder
    }

    private fun writeInterface(interfac: Interface, className: ClassName): TypeSpec.Builder {
        // General strategy:
        // Interface types become Java interfaces
        // Adapter types become an Adapter<I, D> library type with a single method replacing the instance constructor
        // Adapter constructors become anonymous inner classes implementing the Adapter type
        val builder = TypeSpec.interfaceBuilder(className).addModifiers(Modifier.PUBLIC)

        builder.addTypeVariables(interfac.typeParameters.map { name -> TypeVariableName.get(name) })

        interfac.methods.forEach { method ->
            builder.addMethod(writeInterfaceMethod(method).build())
        }

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
            builder.addParameter(getType(argument.type.replacingParameters(typeReplacements), false), argument.name)
        }
        builder.returns(getType(method.returnType.replacingParameters(typeReplacements), false))

        return builder
    }

    private fun writeMethod(function: ValidatedFunction): MethodSpec {
        // TODO: Eventually, support non-static methods
        val builder = MethodSpec.methodBuilder(function.id.namespacedName.last())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)

        for (typeParameter in function.typeParameters) {
            builder.addTypeVariable(TypeVariableName.get(typeParameter))
        }

        function.arguments.forEach { argument ->
            builder.addParameter(getType(argument.type, false), argument.name)
            addToVariableScope(argument.name)
        }

        builder.returns(getType(function.returnType, false))

        // TODO: Add block here
        builder.addCode(writeBlock(function.block, null))

        removeFromVariableScope(function.arguments.map(Argument::name))

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
                builder.addStatement("final \$T \$L", getType(type, false), name)
                builder.beginControlFlow("if (\$L)", writeExpression(expression.condition))
                builder.add(writeBlock(expression.thenBlock, name))
                builder.nextControlFlow("else")
                builder.add(writeBlock(expression.elseBlock, name))
                builder.endControlFlow()
            } else {
                builder.addStatement("final \$T \$L = \$L", getType(type, false), name, writeExpression(expression))
            }
            addToVariableScope(name)
        }

        // TODO: Handle case where returnedExpression is if/then (?) -- or will that get factored out?
        if (varToAssign == null) {
            builder.addStatement("return \$L", writeExpression(block.returnedExpression))
        } else {
            builder.addStatement("\$L = \$L", varToAssign, writeExpression(block.returnedExpression))
        }

        block.assignments.forEach { (name) ->
            removeFromVariableScope(name)
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
                val functionCallStrategy = getNamedFunctionCallStrategy(expression.functionRef)
                functionCallStrategy.apply(expression.chosenParameters, expression.arguments)
            }
            is TypedExpression.ExpressionFunctionCall -> {
                val functionCallStrategy = getExpressionFunctionCallStrategy(expression.functionExpression)
                functionCallStrategy.apply(expression.chosenParameters, expression.arguments)
            }
            is TypedExpression.Literal -> {
                writeLiteralExpression(expression)
            }
            is TypedExpression.ListLiteral -> {
                writeListLiteralExpression(expression)
            }
            is TypedExpression.Follow -> {
                writeFollowExpression(expression)
            }
            is TypedExpression.NamedFunctionBinding -> {
                writeNamedFunctionBinding(expression)
            }
            is TypedExpression.ExpressionFunctionBinding -> {
                writeExpressionFunctionBinding(expression)
            }
            is TypedExpression.InlineFunction -> {
                writeLambdaExpression(expression)
            }
        }
    }

    // TODO: This currently doesn't work, because JavaPoet seems to reject the types of CodeBlocks generated. It would
    // be nice to get this working and express inline functions as lambda expressions instead of splitting them out into
    // explicit functions.
    private fun writeLambdaExpression(expression: TypedExpression.InlineFunction): CodeBlock {
        val argumentsBuilder = CodeBlock.builder()
        expression.arguments.forEachIndexed { index, argument ->
            if (index != 0) {
                argumentsBuilder.add(", ")
            }
            argumentsBuilder.add("\$L", argument.name)
        }
        val arguments = argumentsBuilder.build()
        val block = writeBlock(expression.block, null)
        val code = CodeBlock.builder()
        code.beginControlFlow("(\$L) ->", arguments)
        code.add(block)
        code.endControlFlow()
        return code.build()
    }

    private fun writeFollowExpression(expression: TypedExpression.Follow): CodeBlock {
        // Special sauce...
        val type = expression.structureExpression.type
        if (type is Type.NamedType) {
            if (type.ref.id == NativeStruct.UNICODE_STRING.id) {
                if (expression.name != "codePoints") {
                    error("...")
                }
                // Special handling
                val unicodeStringsJava = ClassName.bestGuess("net.semlang.java.UnicodeStrings")
                return CodeBlock.of("\$T.toCodePoints(\$L)", unicodeStringsJava, writeExpression(expression.structureExpression))
            } else if (type.ref.id == NativeStruct.UNICODE_CODE_POINT.id) {
                if (expression.name != "natural") {
                    error("...")
                }
                // Convert to a BigInteger for now...
                return CodeBlock.of("BigInteger.valueOf(\$L)", writeExpression(expression.structureExpression))
            }
        }

        return CodeBlock.of("\$L.\$L", writeExpression(expression.structureExpression), expression.name)
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
        val functionRef = expression.functionRef

        val resolved = module.resolve(functionRef) ?: error("Could not resolve $functionRef")
        // TODO: Put this in the module itself?
        val signature = when (resolved.type) {
            FunctionLikeType.NATIVE_FUNCTION -> {
                getNativeFunctionOnlyDefinitions()[resolved.entityRef.id] ?: error("Resolution error")
            }
            FunctionLikeType.FUNCTION -> {
                module.getInternalFunction(resolved.entityRef).function.getTypeSignature()
            }
            FunctionLikeType.STRUCT_CONSTRUCTOR -> {
                module.getInternalStruct(resolved.entityRef).struct.getConstructorSignature()
            }
            FunctionLikeType.INSTANCE_CONSTRUCTOR -> {
                module.getInternalInterface(resolved.entityRef).interfac.getInstanceConstructorSignature()
            }
            FunctionLikeType.ADAPTER_CONSTRUCTOR -> {
                module.getInternalInterfaceByAdapterId(resolved.entityRef).interfac.getAdapterConstructorSignature()
            }
        }

        // TODO: More compact references when not binding arguments
        val functionCallStrategy = getNamedFunctionCallStrategy(functionRef)

        val unboundArgumentNames = ArrayList<String>()
        val arguments = ArrayList<TypedExpression>()
        // Lambda expression
        expression.bindings.forEachIndexed { index, binding ->
            if (binding == null) {
                val argumentName = ensureUnusedVariable(if (resolved.type == FunctionLikeType.FUNCTION) {
                    // TODO: Be able to get this for native functions, as well
                    val referencedFunction = module.getInternalFunction(resolved.entityRef)
                    referencedFunction.function.arguments[index].name
                } else {
                    // TODO: Pick better names based on types
                    "arg" + index
                })
                unboundArgumentNames.add(argumentName)

                val unparameterizedArgType = signature.argumentTypes[index]
                val argType = unparameterizedArgType.replacingParameters(signature.typeParameters.map(Type::ParameterType).zip(expression.chosenParameters).toMap())
                arguments.add(TypedExpression.Variable(argType, argumentName))
            } else {
                arguments.add(binding)
            }
        }

        val functionCall = functionCallStrategy.apply(expression.chosenParameters, arguments)
        return CodeBlock.of("(\$L) -> \$L", unboundArgumentNames.joinToString(", "), functionCall)
    }

    val varsInScope = HashSet<String>()
    private fun removeFromVariableScope(varName: String) {
        varsInScope.remove(varName)
    }
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
    val namedFunctionCallStrategies = HashMap<EntityId, FunctionCallStrategy>()

    private fun getNamedFunctionCallStrategy(functionRef: ResolvedEntityRef): FunctionCallStrategy {
        // TODO: Currently we pretend other modules don't exist
        return getNamedFunctionCallStrategy(functionRef.id)
    }
    private fun getNamedFunctionCallStrategy(functionRef: EntityRef): FunctionCallStrategy {
        // TODO: Currently we pretend other modules don't exist
        return getNamedFunctionCallStrategy(functionRef.id)
    }
    private fun getNamedFunctionCallStrategy(functionId: EntityId): FunctionCallStrategy {
        val cached = namedFunctionCallStrategies[functionId]
        if (cached != null) {
            return cached
        }

        val classContainingFunction = getContainingClassName(functionId)
        val strategy = object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                if (chosenTypes.isEmpty()) {
                    return CodeBlock.of("\$T.\$L(\$L)", classContainingFunction, functionId.namespacedName.last(), getArgumentsBlock(arguments))
                } else {
                    return CodeBlock.of("\$T.<\$L>\$L(\$L)", classContainingFunction, getChosenTypesCode(chosenTypes), functionId.namespacedName.last(), getArgumentsBlock(arguments))
                }
            }
        }
        namedFunctionCallStrategies[functionId] = strategy
        return strategy
    }

    private fun getExpressionFunctionCallStrategy(expression: TypedExpression): FunctionCallStrategy {

        // Is it an interface follow?
        if (expression is TypedExpression.Follow) {
            val structureExpression = expression.structureExpression
            val structureExpressionType = structureExpression.type
            if (structureExpressionType is Type.NamedType) {
                val resolvedStructureType = module.resolve(structureExpressionType.ref)
                // TODO: I don't think this handles native interfaces?
                if (resolvedStructureType != null && resolvedStructureType.type == FunctionLikeType.INSTANCE_CONSTRUCTOR) {
                    return object : FunctionCallStrategy {
                        override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                            return CodeBlock.of("\$L.\$L(\$L)", writeExpression(structureExpression), expression.name, getArgumentsBlock(arguments))
                        }
                    }
                }
            }
        }

        return object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                val functionName = if (arguments.size == 0) "get" else "apply"
                return CodeBlock.of("\$L.\$L(\$L)", writeExpression(expression), functionName, getArgumentsBlock(arguments))
            }
        }
    }

    private fun writeListLiteralExpression(expression: TypedExpression.ListLiteral): CodeBlock {
        val contents = getArgumentsBlock(expression.contents)
        return CodeBlock.of("\$T.asList(\$L)", Arrays::class.java, contents)
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
            is Type.NamedType -> {
                val resolvedType = this.module.resolve(type.ref) ?: error("Unresolved type ${type.ref}")
                if (isNativeModule(resolvedType.entityRef.module) &&
                    resolvedType.entityRef.id == NativeStruct.UNICODE_STRING.id) {
                    return CodeBlock.of("\$S", stripUnescapedBackslashes(literal))
                }

                // TODO: We need to know what the structs are here...
                if (resolvedType.type == FunctionLikeType.STRUCT_CONSTRUCTOR) {
                    val struct = module.getInternalStruct(resolvedType.entityRef).struct
                    if (struct.members.size == 1) {
                        val delegateType = struct.members[0].type
                        val constructorStrategy = getNamedFunctionCallStrategy(type.ref)
                        val constructorCall = constructorStrategy.apply(listOf(), listOf(TypedExpression.Literal(delegateType, literal)))
                        if (struct.requires != null) {
                            return CodeBlock.of("\$L.get()", constructorCall)
                        } else {
                            return constructorCall
                        }
                    }
                }

                error("Literals not supported for type $resolvedType")
            }
            is Type.ParameterType -> {
                error("Literals not supported for parameter type $type")
            }
        }
    }

    private fun stripUnescapedBackslashes(literal: String): String {
        return evaluateStringLiteral(literal).contents
    }

    private fun getType(semlangType: Type, isParameter: Boolean): TypeName {
        return when (semlangType) {
            Type.INTEGER -> TypeName.get(BigInteger::class.java)
            Type.NATURAL -> TypeName.get(BigInteger::class.java)
            Type.BOOLEAN -> if (isParameter) TypeName.get(java.lang.Boolean::class.java) else TypeName.BOOLEAN
            is Type.List -> ParameterizedTypeName.get(ClassName.get(java.util.List::class.java), getType(semlangType.parameter, true))
            is Type.Try -> ParameterizedTypeName.get(ClassName.get(java.util.Optional::class.java), getType(semlangType.parameter, true))
            is Type.FunctionType -> getFunctionType(semlangType)
            is Type.NamedType -> getNamedType(semlangType)
            is Type.ParameterType -> TypeVariableName.get(semlangType.name)
        }
    }

    private fun getFunctionType(semlangType: Type.FunctionType): TypeName {
        val strategy = getFunctionTypeStrategy(semlangType)

        return strategy.getTypeName(semlangType, { this.getType(it, true) })
    }

    private fun getNamedType(semlangType: Type.NamedType): TypeName {

        //TODO: Resolve beforehand, not after (part of multi-module support (?))
        val interfaceRef = getInterfaceRefForAdapterRef(semlangType.originalRef)
        if (interfaceRef != null) {
            val interfaceId = module.resolve(interfaceRef)?.entityRef?.id ?: error("error")
            val bareAdapterClass = ClassName.bestGuess("net.semlang.java.Adapter")
            val dataTypeParameter = getType(semlangType.parameters[0], true)
            val otherParameters = semlangType.parameters.drop(1).map { getType(it, true) }
            val bareInterfaceName = getStructClassName(interfaceId)
            val interfaceJavaName = if (otherParameters.isEmpty()) {
                bareInterfaceName
            } else {
                ParameterizedTypeName.get(bareInterfaceName, *otherParameters.toTypedArray())
            }
            return ParameterizedTypeName.get(bareAdapterClass, interfaceJavaName, dataTypeParameter)
        }

        val predefinedClassName: ClassName? = when (semlangType.originalRef.id.namespacedName) {
            listOf("Sequence") -> ClassName.bestGuess("net.semlang.java.Sequence")
            listOf("Unicode", "String") -> ClassName.get(String::class.java)
            listOf("Unicode", "CodePoint") -> ClassName.get(Integer::class.java)
            listOf("Bit") -> ClassName.bestGuess("net.semlang.java.Bit")
            listOf("BitsBigEndian") -> ClassName.bestGuess("net.semlang.java.BitsBigEndian")
            else -> null
        }

        // TODO: The right general approach is going to be "find the context containing this type, and use the
        // associated package name for that"

        // TODO: Might end up being more complicated? This is probably not quite right
        val className = predefinedClassName ?: ClassName.bestGuess(javaPackage.joinToString(".") + "." + semlangType.originalRef.toString()).sanitize()

        if (semlangType.parameters.isEmpty()) {
            return className
        } else {
            val parameterTypeNames: List<TypeName> = semlangType.parameters.map { this.getType(it, true) }
            return ParameterizedTypeName.get(className, *parameterTypeNames.toTypedArray())
        }
    }

    private fun getStructClassName(functionId: EntityId): ClassName {
        return ClassName.get((javaPackage + functionId.namespacedName.dropLast(1)).joinToString("."), functionId.namespacedName.last()).sanitize()
    }

    private fun getContainingClassName(functionId: EntityId): ClassName {
        //Assume the first capitalized string is the className
        val allParts = javaPackage + functionId.namespacedName
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
            return ClassName.get((javaPackage + functionId.namespacedName.dropLast(1)).joinToString("."), "Functions").sanitize()
        }
        return ClassName.get(packageParts.joinToString("."), className).sanitize()
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


    private fun getNativeFunctionCallStrategies(): Map<EntityId, FunctionCallStrategy> {
        val map = HashMap<EntityId, FunctionCallStrategy>()

        val javaBooleans = ClassName.bestGuess("net.semlang.java.Booleans")
        // TODO: Replace these with the appropriate operators (in parentheses)
        map.put(EntityId.of("Boolean", "and"), StaticFunctionCallStrategy(javaBooleans, "and"))
        map.put(EntityId.of("Boolean", "or"), StaticFunctionCallStrategy(javaBooleans, "or"))
        map.put(EntityId.of("Boolean", "not"), StaticFunctionCallStrategy(javaBooleans, "not"))

        val javaLists = ClassName.bestGuess("net.semlang.java.Lists")
        map.put(EntityId.of("List", "empty"), StaticFunctionCallStrategy(javaLists, "empty"))
        // TODO: Find an approach to remove most uses of append where we'd be better off with e.g. add
        map.put(EntityId.of("List", "append"), StaticFunctionCallStrategy(javaLists, "append"))
        map.put(EntityId.of("List", "appendFront"), StaticFunctionCallStrategy(javaLists, "appendFront"))
        map.put(EntityId.of("List", "concatenate"), StaticFunctionCallStrategy(javaLists, "concatenate"))
        map.put(EntityId.of("List", "drop"), StaticFunctionCallStrategy(javaLists, "drop"))
        map.put(EntityId.of("List", "lastN"), StaticFunctionCallStrategy(javaLists, "lastN"))
        // TODO: Find an approach where we can replace this with a simple .get() call...
        // Harder than it sounds, given the BigInteger input; i.e. we need to intelligently replace with a "Size"/"Index" type
        map.put(EntityId.of("List", "get"), StaticFunctionCallStrategy(javaLists, "get"))
        map.put(EntityId.of("List", "size"), wrapInBigint(MethodFunctionCallStrategy("size")))
        map.put(EntityId.of("List", "map"), StaticFunctionCallStrategy(javaLists, "map"))
        map.put(EntityId.of("List", "reduce"), StaticFunctionCallStrategy(javaLists, "reduce"))

        val javaIntegers = ClassName.bestGuess("net.semlang.java.Integers")
        // TODO: Add ability to use non-static function calls
        map.put(EntityId.of("Integer", "plus"), MethodFunctionCallStrategy("add"))
        map.put(EntityId.of("Integer", "minus"), MethodFunctionCallStrategy("subtract"))
        map.put(EntityId.of("Integer", "times"), MethodFunctionCallStrategy("multiply"))
        map.put(EntityId.of("Integer", "equals"), MethodFunctionCallStrategy("equals"))
        map.put(EntityId.of("Integer", "lessThan"), StaticFunctionCallStrategy(javaIntegers, "lessThan"))
        map.put(EntityId.of("Integer", "greaterThan"), StaticFunctionCallStrategy(javaIntegers, "greaterThan"))
        map.put(EntityId.of("Integer", "fromNatural"), PassedThroughVarFunctionCallStrategy)
        map.put(EntityId.of("Integer", "sum"), StaticFunctionCallStrategy(javaIntegers, "sum"))

        val javaNaturals = ClassName.bestGuess("net.semlang.java.Naturals")
        // Share implementations with Integer in some cases
        map.put(EntityId.of("Natural", "plus"), MethodFunctionCallStrategy("add"))
        map.put(EntityId.of("Natural", "times"), MethodFunctionCallStrategy("multiply"))
        map.put(EntityId.of("Natural", "remainder"), MethodFunctionCallStrategy("remainder"))
        map.put(EntityId.of("Natural", "lesser"), MethodFunctionCallStrategy("min"))
        map.put(EntityId.of("Natural", "equals"), MethodFunctionCallStrategy("equals"))
        map.put(EntityId.of("Natural", "lessThan"), StaticFunctionCallStrategy(javaNaturals, "lessThan"))
        map.put(EntityId.of("Natural", "greaterThan"), StaticFunctionCallStrategy(javaNaturals, "greaterThan"))
        map.put(EntityId.of("Natural", "absoluteDifference"), StaticFunctionCallStrategy(javaNaturals, "absoluteDifference"))
        map.put(EntityId.of("Natural", "fromInteger"), StaticFunctionCallStrategy(javaNaturals, "fromInteger"))
        map.put(EntityId.of("Natural", "fromBits"), StaticFunctionCallStrategy(javaNaturals, "fromBits"))
        map.put(EntityId.of("Natural", "toBits"), StaticFunctionCallStrategy(javaNaturals, "toBits"))

        val javaTries = ClassName.bestGuess("net.semlang.java.Tries")
        map.put(EntityId.of("Try", "failure"), StaticFunctionCallStrategy(javaTries, "failure"))
        map.put(EntityId.of("Try", "success"), StaticFunctionCallStrategy(javaTries, "success"))
        map.put(EntityId.of("Try", "isSuccess"), StaticFunctionCallStrategy(javaTries, "isSuccess"))
        map.put(EntityId.of("Try", "assume"), StaticFunctionCallStrategy(javaTries, "assume"))
        map.put(EntityId.of("Try", "map"), StaticFunctionCallStrategy(javaTries, "map"))

        val javaSequences = ClassName.bestGuess("net.semlang.java.Sequences")
        map.put(EntityId.of("Sequence", "create"), StaticFunctionCallStrategy(javaSequences, "create"))

        val javaUnicodeStrings = ClassName.bestGuess("net.semlang.java.UnicodeStrings")
        map.put(EntityId.of("Unicode", "String"), StaticFunctionCallStrategy(javaUnicodeStrings, "create"))
        map.put(EntityId.of("Unicode", "String", "length"), StaticFunctionCallStrategy(javaUnicodeStrings, "length"))

        // Unicode.CodePoint constructor
        map.put(EntityId.of("Unicode", "CodePoint"), StaticFunctionCallStrategy(javaUnicodeStrings, "asCodePoint"))

        // Bit constructor
        map.put(EntityId.of("Bit"), StaticFunctionCallStrategy(ClassName.bestGuess("net.semlang.java.Bit"), "create"))

        // BitsBigEndian constructor
        map.put(EntityId.of("BitsBigEndian"), StaticFunctionCallStrategy(ClassName.bestGuess("net.semlang.java.BitsBigEndian"), "create"))

        return map
    }

    private fun wrapInBigint(delegate: FunctionCallStrategy): FunctionCallStrategy {
        return object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type>, arguments: List<TypedExpression>): CodeBlock {
                return CodeBlock.of("BigInteger.valueOf(\$L)", delegate.apply(chosenTypes, arguments))
            }
        }
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
        val chosenTypes = chosenSemlangTypes.map { this.getType(it, true) }
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
    override fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName {
        if (type.argTypes.isNotEmpty()) {
            error("")
        }
        val className = ClassName.get(java.util.function.Supplier::class.java)

        return ParameterizedTypeName.get(className, getTypeForParameter(type.outputType).box())
    }
}

object FunctionFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName {
        if (type.argTypes.size != 1) {
            error("")
        }
        val className = ClassName.get(java.util.function.Function::class.java)

        return ParameterizedTypeName.get(className, getTypeForParameter(type.argTypes[0]).box(), getTypeForParameter(type.outputType).box())
    }

}

class RuntimeLibraryFunctionTypeStrategy(val numArgs: Int): FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName {
        val className = ClassName.bestGuess("net.semlang.java.function.Function" + numArgs)

        val parameterTypes = ArrayList<TypeName>()
        for (semlangType in type.argTypes) {
            parameterTypes.add(getTypeForParameter(semlangType).box())
        }
        parameterTypes.add(getTypeForParameter(type.outputType).box())

        return ParameterizedTypeName.get(className, *parameterTypes.toTypedArray())
    }
}

private interface FunctionTypeStrategy {
    fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName
}

fun ClassName.sanitize(): ClassName {
    return ClassName.get(this.packageName().replace("-", "_"), this.simpleName())
}
