package net.semlang.writejava

import com.squareup.javapoet.*
import net.semlang.api.*
import net.semlang.internal.test.TestAnnotationContents
import net.semlang.internal.test.verifyTestAnnotationContents
import net.semlang.interpreter.ComplexLiteralNode
import net.semlang.interpreter.ComplexLiteralParsingResult
import net.semlang.interpreter.evaluateStringLiteral
import net.semlang.interpreter.parseComplexLiteral
import net.semlang.transforms.*
import net.semlang.validator.validateModule
import java.io.File
import java.io.PrintStream
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
    val tempModule1 = extractInlineFunctions(unprocessedModule)
    val tempModule2 = constrainVariableNames(tempModule1, RenamingStrategies.getKeywordAvoidingStrategy(JAVA_KEYWORDS))
    val tempModule3 = hoistMatchingExpressions(tempModule2, { it is Expression.IfThen })
    val tempModule4 = preventDuplicateVariableNames(tempModule3)
    val module = validateModule(tempModule4, unprocessedModule.name, unprocessedModule.nativeModuleVersion, unprocessedModule.upstreamModules.values.toList()).assumeSuccess()

    return JavaCodeWriter(module, javaPackage, newSrcDir, newTestSrcDir).write()
}

private class JavaCodeWriter(val module: ValidatedModule, val javaPackage: List<String>, val newSrcDir: File, val newTestSrcDir: File) {
    // TODO: This might be all we need for the package
    val javaPackageString: String = javaPackage.joinToString(".")

    fun write(): WrittenJavaInfo {
        val classMap = HashMap<ClassName, TypeSpec.Builder>()
        namedFunctionCallStrategies.putAll(getNativeFunctionCallStrategies())

        for (struct in module.ownStructs.values) {
            val className = getOwnTypeClassName(struct.id)
            val structClassBuilder = writeStructClass(struct, className)

            if (classMap.containsKey(className)) {
                error("Duplicate class name $className for struct ${struct.id}")
            }
            classMap[className] = structClassBuilder
        }
        // Enable calls to struct constructors
        addStructConstructorFunctionCallStrategies(module.getAllInternalStructs().values)

        for (interfac in module.ownInterfaces.values) {
            val className = getOwnTypeClassName(interfac.id)
            val interfaceBuilder = writeInterface(interfac, className)

            if (classMap.containsKey(className)) {
                error("Duplicate class name $className for interface ${interfac.id}")
            }
            classMap[className] = interfaceBuilder
        }
        // Enable calls to instance and adapter constructors
        addInstanceConstructorFunctionCallStrategies(module.getAllInternalInterfaces().values)
        addAdapterConstructorFunctionCallStrategies(module.getAllInternalInterfaces().values)

        for (union in module.ownUnions.values) {
            val className = getOwnTypeClassName(union.id)
            val unionBuilder = writeUnion(union, className)

            if (classMap.containsKey(className)) {
                error("Duplicate class name $className for union ${union.id}")
            }
            classMap[className] = unionBuilder
        }
        addWhenFunctionCallStrategies(module.getAllInternalUnions().values)
        addOptionConstructorCallStrategies(module.getAllInternalUnions().values)

        for (function in module.ownFunctions.values) {
            val method = writeMethod(function)

            val className = getContainingClassName(function.id)
            if (!classMap.containsKey(className)) {
                classMap.put(className, TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL))
            }
            classMap[className]!!.addMethod(method)

            // Write unit tests for @Test annotations
            for (annotation in function.annotations) {
                if (annotation.name == EntityId.of("Test")) {
                    val testContents = verifyTestAnnotationContents(annotation.values, function)

                    prepareJUnitTest(function, testContents)
                }
            }
        }

        writeClassesToFiles(classMap)

        writePreparedJUnitTests(newTestSrcDir)

        return WrittenJavaInfo(testClassCounts.keys.toList())
    }

    private fun addWhenFunctionCallStrategies(unions: Collection<Union>) {
        for (union in unions) {
            val whenId = union.whenId
            if (namedFunctionCallStrategies.containsKey(whenId)) {
                error("Already have a call strategy for $whenId")
            }
            namedFunctionCallStrategies.put(whenId, MethodFunctionCallStrategy("when"))
        }
    }

    private fun addOptionConstructorCallStrategies(unions: Collection<Union>) {
        for (union in unions) {
            val unionClassName = getOwnTypeClassName(union.id)
            for (option in union.options) {
                val optionId = EntityId(union.id.namespacedName + option.name)
                if (namedFunctionCallStrategies.containsKey(optionId)) {
                    error("Already have a call strategy for $optionId")
                }

                namedFunctionCallStrategies.put(optionId, OptionConstructorCallStrategy(unionClassName, option))
            }
        }
    }

    private fun addInstanceConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        for (interfac in interfaces) {
            if (namedFunctionCallStrategies.containsKey(interfac.id)) {
                error("Already have a call strategy for ${interfac.id}")
            }

            namedFunctionCallStrategies.put(interfac.id, InstanceFunctionCallStrategy(interfac))
        }
    }

    private fun addAdapterConstructorFunctionCallStrategies(interfaces: Collection<Interface>) {
        for (interfac in interfaces) {
            if (namedFunctionCallStrategies.containsKey(interfac.adapterId)) {
                error("Already have a call strategy for ${interfac.adapterId}")
            }

            namedFunctionCallStrategies.put(interfac.adapterId, AdapterFunctionCallStrategy(interfac))
        }
    }

    private fun writeClassesToFiles(classMap: MutableMap<ClassName, TypeSpec.Builder>) {
        // Collect classes in such a way that we can deal with needing to embed some classes in others
        val allClassNamesAndEnclosingClassNames = HashSet<ClassName>()
        for (className in classMap.keys) {
            var curClassName = className
            allClassNamesAndEnclosingClassNames.add(className)
            while (curClassName.enclosingClassName() != null) {
                curClassName = curClassName.enclosingClassName()
                allClassNamesAndEnclosingClassNames.add(curClassName)
            }
        }

        // Sort from shortest name size to longest (so each class comes after its enclosing class, if any)
        val classNamesOuterToInner = ArrayList<ClassName>(allClassNamesAndEnclosingClassNames)
        classNamesOuterToInner.sortBy { className -> className.simpleNames().size }

        // Add any needed enclosing classes that don't exist yet
        for (className in classNamesOuterToInner) {
            if (!classMap.containsKey(className)) {
                val newBuilder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                classMap[className] = newBuilder
            }
        }

        // Add classes to their enclosing classes
        // (We build as we go, so this has to be done innermost-to-outermost)
        for (className in classNamesOuterToInner.reversed()) {
            val builder = classMap[className]!!
            val enclosingClassName = className.enclosingClassName()
            if (enclosingClassName != null) {
                val enclosingClassBuilder = classMap[enclosingClassName]!!
                builder.addModifiers(Modifier.STATIC)
                enclosingClassBuilder.addType(builder.build())
            }
        }

        classMap.forEach { className, classBuilder ->
            if (className.enclosingClassName() == null) {
                val javaFile = JavaFile.builder(className.packageName(), classBuilder.build())
                        .build()
                javaFile.writeTo(newSrcDir)
            }
        }
    }

    private fun getOwnTypeClassName(id: EntityId): ClassName {
        return getClassNameForTypeId(ResolvedEntityRef(this.module.id, id))
    }

    private fun addStructConstructorFunctionCallStrategies(structs: Collection<Struct>) {
        for (struct in structs) {
            val structClass = getOwnTypeClassName(struct.id)
            // Check to avoid overriding special cases
            if (!namedFunctionCallStrategies.containsKey(struct.id)) {
                namedFunctionCallStrategies[struct.id] = object : FunctionCallStrategy {
                    override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
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

    private fun writeStructClass(struct: Struct, className: ClassName): TypeSpec.Builder {
        val builder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.FINAL)

        if (struct.typeParameters.isNotEmpty()) {
            builder.addTypeVariables(struct.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) })
        }

        val constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE)
        for (member in struct.members) {
            val javaType = getType(member.type, false)
            builder.addField(javaType, member.name, Modifier.PUBLIC, Modifier.FINAL)

            constructor.addParameter(javaType, member.name)
            constructor.addStatement("this.\$L = \$L", member.name, member.name)
        }
        builder.addMethod(constructor.build())

        val createMethod = MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        if (struct.typeParameters.isNotEmpty()) {
            createMethod.addTypeVariables(struct.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) })
        }
        for (member in struct.members) {
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

        if (isDataStruct(struct, module)) {
            // Write equals() and hashCode()
            val equalsMethod = MethodSpec.methodBuilder("equals").addModifiers(Modifier.PUBLIC)
            equalsMethod.addAnnotation(Override::class.java)
            equalsMethod.addParameter(java.lang.Object::class.java, "o")
            equalsMethod.returns(TypeName.BOOLEAN)
            equalsMethod.beginControlFlow("if (this == o)")
            equalsMethod.addStatement("return true")
            equalsMethod.endControlFlow()
            equalsMethod.beginControlFlow("if (!(o instanceof \$T))", className)
            equalsMethod.addStatement("return false")
            equalsMethod.endControlFlow()
            equalsMethod.addStatement("\$T other = (\$T) o", className, className)

            val equalsReturnStatement = CodeBlock.builder()
            equalsReturnStatement.add("return ")
            var isFirst = true
            for (member in struct.members) {
                if (!isFirst) {
                    equalsReturnStatement.add(" && ")
                }
                equalsReturnStatement.add(CodeBlock.of("\$T.equals(this.\$L, other.\$L)", Objects::class.java, member.name, member.name))
                isFirst = false
            }
            equalsMethod.addStatement("\$L", equalsReturnStatement.build())
            builder.addMethod(equalsMethod.build())

            // TODO: Also write hashCode()
        }

        return builder
    }

    private fun writeInterface(interfac: Interface, className: ClassName): TypeSpec.Builder {
        // General strategy:
        // Interface types become Java interfaces
        val builder = TypeSpec.interfaceBuilder(className).addModifiers(Modifier.PUBLIC)

        builder.addTypeVariables(interfac.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) })

        for (method in interfac.methods) {
            builder.addMethod(writeInterfaceMethod(method).build())
        }

        return builder
    }

    private fun writeInterfaceMethod(method: Method, makeAbstract: Boolean = true): MethodSpec.Builder {
        val builder = MethodSpec.methodBuilder(method.name).addModifiers(Modifier.PUBLIC)
        if (makeAbstract) {
            builder.addModifiers(Modifier.ABSTRACT)
        }

        if (method.typeParameters.isNotEmpty()) {
            TODO()
        }

        for (argument in method.arguments) {
            builder.addParameter(getType(argument.type, false), argument.name)
        }
        builder.returns(getType(method.returnType, false))

        return builder
    }

    private fun writeUnion(union: Union, className: ClassName): TypeSpec.Builder {
        // General strategy:
        // The union becomes a Java abstract class with a when() method and static constructor methods
        // The interface has inner static classes for the options
        val builder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)

        val typeVariables = union.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) }
        builder.addTypeVariables(typeVariables)

        // Make the constructor private
        val constructorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE)
        builder.addMethod(constructorBuilder.build())

        for (option in union.options) {
            builder.addMethod(writeOptionConstructorMethod(option, className, union))
            builder.addType(writeOptionClass(option, className, union))
        }

        val whenBuilder = MethodSpec.methodBuilder("when").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
        val whenTypeVariableName = pickUnusedTypeVariable(typeVariables)
        val whenTypeVariable = TypeVariableName.get(whenTypeVariableName)
        val whenType = Type.ParameterType(TypeParameter(whenTypeVariableName, null))
        whenBuilder.addTypeVariable(whenTypeVariable)
        whenBuilder.returns(whenTypeVariable)
        for (option in union.options) {
            val functionType = if (option.type != null) {
                getFunctionType(Type.FunctionType.create(listOf(), listOf(option.type!!), whenType))
            } else {
                getFunctionType(Type.FunctionType.create(listOf(), listOf(), whenType))
            }
            whenBuilder.addParameter(functionType, "when" + option.name)
        }
        builder.addMethod(whenBuilder.build())

        return builder
    }

    private fun writeOptionClass(option: Option, unionClassName: ClassName, union: Union): TypeSpec {
        val optionClassName = unionClassName.nestedClass(option.name)
        val builder = TypeSpec.classBuilder(optionClassName)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)

        val typeVariables = union.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) }
        builder.addTypeVariables(typeVariables)
        builder.superclass(unionClassName.parameterizedWith(typeVariables))

        if (option.type != null) {
            builder.addField(getType(option.type!!, false), "data", Modifier.PRIVATE, Modifier.FINAL)

            val constructorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE)
            constructorBuilder.addParameter(getType(option.type!!, false), "data")
            constructorBuilder.addStatement("this.data = data")
            builder.addMethod(constructorBuilder.build())

            val equalsBuilder = MethodSpec.methodBuilder("equals").addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override::class.java)
                    .addParameter(Object::class.java, "other")
                    .returns(TypeName.BOOLEAN)
            val equalsCode = CodeBlock.builder()
            equalsCode.beginControlFlow("if (!(other instanceof \$T))", optionClassName)
                    .addStatement("return false")
                    .endControlFlow()
                    .addStatement("return data.equals(((\$T) other).data)", optionClassName)
            equalsBuilder.addCode(equalsCode.build())
            builder.addMethod(equalsBuilder.build())

            val hashCodeBuilder = MethodSpec.methodBuilder("hashCode").addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override::class.java)
                    .returns(TypeName.INT)
                    .addStatement("return \$L + data.hashCode()", optionClassName.toString().hashCode())
            builder.addMethod(hashCodeBuilder.build())
        } else {
            // Make it a singleton
            val instanceBuilder = FieldSpec.builder(optionClassName, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC)
            instanceBuilder.initializer("new \$T()", optionClassName)
            builder.addField(instanceBuilder.build())
        }

        val whenBuilder = MethodSpec.methodBuilder("when").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override::class.java)
        val whenTypeVariableName = pickUnusedTypeVariable(typeVariables)
        val whenTypeVariable = TypeVariableName.get(whenTypeVariableName)
        val whenType = Type.ParameterType(TypeParameter(whenTypeVariableName, null))
        whenBuilder.addTypeVariable(whenTypeVariable)
        whenBuilder.returns(whenTypeVariable)
        for (curOption in union.options) {
            val functionType = if (curOption.type != null) {
                getFunctionType(Type.FunctionType.create(listOf(), listOf(curOption.type!!), whenType))
            } else {
                getFunctionType(Type.FunctionType.create(listOf(), listOf(), whenType))
            }
            whenBuilder.addParameter(functionType, "when" + curOption.name)
        }
        // Figure out the return statement here
        val ourOptionFunctionType = if (option.type != null) {
            Type.FunctionType.create(listOf(), listOf(option.type!!), whenType)
        } else {
            Type.FunctionType.create(listOf(), listOf(), whenType)
        }
        val callStrategy = getExpressionFunctionCallStrategy(TypedExpression.Variable(ourOptionFunctionType, "when" + option.name))
        if (option.type != null) {
            whenBuilder.addStatement("return \$L", callStrategy.apply(listOf(whenType), listOf(TypedExpression.Variable(option.type!!, "data"))))
        } else {
            whenBuilder.addStatement("return \$L", callStrategy.apply(listOf(whenType), listOf()))
        }
        builder.addMethod(whenBuilder.build())

        return builder.build()
    }

    private fun pickUnusedTypeVariable(typeVariables: List<TypeVariableName>): String {
        val usedVariableNamesSet = typeVariables.map { it.name }.toSet()
        if (!usedVariableNamesSet.contains("T")) {
            return "T"
        }
        var index = 2
        while (true) {
            val candidate = "T$index"
            if (!usedVariableNamesSet.contains(candidate)) {
                return candidate
            }
            index++
        }
    }

    private fun writeOptionConstructorMethod(option: Option, unionClassName: ClassName, union: Union): MethodSpec {
        val builder = MethodSpec.methodBuilder("create" + option.name)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)

        val typeVariables = union.typeParameters.map { parameter -> TypeVariableName.get(parameter.name) }
        builder.addTypeVariables(typeVariables)

        if (option.type != null) {
            builder.addParameter(getType(option.type!!, false), "data")
        }
        builder.returns(unionClassName.parameterizedWith(typeVariables)) // Return the union type

        // TODO: Put this in shared code
        val optionClassName = unionClassName.nestedClass(option.name)
        if (option.type != null) {
            builder.addStatement("return new \$T(data)", optionClassName.parameterizedWith(typeVariables))
        } else {
            builder.addStatement("return \$T.INSTANCE", optionClassName)
        }

        return builder.build()
    }

    private fun writeMethod(function: ValidatedFunction): MethodSpec {
        // TODO: Eventually, support non-static methods
        val builder = MethodSpec.methodBuilder(function.id.namespacedName.last())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)

        for (typeParameter in function.typeParameters) {
            builder.addTypeVariable(TypeVariableName.get(typeParameter.name))
        }

        for (argument in function.arguments) {
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

        for ((name, type, expression) in block.statements) {
            // TODO: Test case where a variable within the block has the same name as the variable we're going to assign to
            if (name != null) {
                // Assignment case
                if (expression is TypedExpression.IfThen) {
                    // The variable gets added to our scope early in this case
                    addToVariableScope(name)
                    builder.addStatement("final \$T \$L", getType(type, false), name)
                    builder.beginControlFlow("if (\$L)", writeExpression(expression.condition))
                    builder.add(writeBlock(expression.thenBlock, name))
                    builder.nextControlFlow("else")
                    builder.add(writeBlock(expression.elseBlock, name))
                    builder.endControlFlow()
                } else {
                    builder.addStatement("final \$T \$L = \$L", getType(type, false), name, writeExpression(expression))
                    addToVariableScope(name)
                }
            } else {
                // Non-assignment expression
                builder.addStatement("\$L", writeExpression(expression))
            }
        }

        // TODO: Handle case where returnedExpression is if/then (?) -- or will that get factored out?
        if (varToAssign == null) {
            builder.addStatement("return \$L", writeExpression(block.returnedExpression))
        } else {
            builder.addStatement("\$L = \$L", varToAssign, writeExpression(block.returnedExpression))
        }

        for (assignment in block.statements) {
            assignment.name?.let { removeFromVariableScope(it) }
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
                functionCallStrategy.apply(listOf(), expression.arguments)
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
            if (type.ref.id == NativeStruct.NATURAL.id) {
                if (expression.name != "integer") {
                    error("...")
                }
                // Just reuse the BigInteger as-is
                return writeExpression(expression.structureExpression)
            } else if (type.ref.id == NativeStruct.UNICODE_STRING.id) {
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

                val argType = when (outputType) {
                    is Type.FunctionType.Ground -> outputType.argTypes[unboundArgumentIndex]
                    is Type.FunctionType.Parameterized -> outputType.argTypes[unboundArgumentIndex]
                }

                arguments.add(TypedExpression.Variable(argType, argumentName))
                unboundArgumentIndex++
            } else {
                arguments.add(binding)
            }
        }

        val functionCall = functionCallStrategy.apply(listOf(), arguments)
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
                TODO()
            }
            FunctionLikeType.ADAPTER_CONSTRUCTOR -> {
                TODO()
            }
            FunctionLikeType.OPAQUE_TYPE -> {
                error("$resolved should be a function, not an opaque type")
            }
            FunctionLikeType.UNION_TYPE -> TODO()
            FunctionLikeType.UNION_OPTION_CONSTRUCTOR -> {
                val unionId = EntityId(resolved.entityRef.id.namespacedName.dropLast(1))
                val unionRef = resolved.entityRef.copy(id = unionId)
                module.getInternalUnion(unionRef).union.getOptionConstructorSignatureForId(resolved.entityRef.id)
            }
            FunctionLikeType.UNION_WHEN_FUNCTION -> TODO()
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

                val functionType = signature.getFunctionType().rebindTypeParameters(expression.chosenParameters)
                val argType = when (functionType) {
                    is Type.FunctionType.Ground -> functionType.argTypes[index]
                    is Type.FunctionType.Parameterized -> functionType.argTypes[index]
                }

                arguments.add(TypedExpression.Variable(argType, argumentName))
            } else {
                arguments.add(binding)
            }
        }

        unboundArgumentNames.forEach(this::addToVariableScope)
        val functionCall = functionCallStrategy.apply(expression.chosenParameters, arguments)
        unboundArgumentNames.forEach(this::removeFromVariableScope)
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
        return arguments.map { writeExpression(it) }.joinToArgumentsList()
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
            override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
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
                        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
                            return CodeBlock.of("\$L.\$L(\$L)", writeExpression(structureExpression), expression.name, getArgumentsBlock(arguments))
                        }
                    }
                }
            }
        }

        return object: FunctionCallStrategy {
            override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
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
                val isValidLong = (literal.toLongOrNull() != null)
                if (isValidLong) {
                    CodeBlock.of("\$T.valueOf(\$LL)", BigInteger::class.java, literal)
                } else {
                    CodeBlock.of("new \$T(\$S)", BigInteger::class.java, literal)
                }
            }
            Type.BOOLEAN -> {
                CodeBlock.of("\$L", literal)
            }
            is Type.List -> writeComplexLiteralExpression(type, literal)
            is Type.Maybe -> {
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
                if (isNativeModule(resolvedType.entityRef.module))  {
                    if (resolvedType.entityRef.id == NativeStruct.NATURAL.id) {
                        return writeNaturalLiteral(literal)
                    }
                    if (resolvedType.entityRef.id == NativeStruct.UNICODE_STRING.id) {
                        return writeUnicodeStringLiteral(literal)
                    }
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
            is Type.InternalParameterType -> {
                error("Literals not supported for internal parameter type $type")
            }
        }
    }

    private fun writeUnicodeStringLiteral(literal: String): CodeBlock {
        return CodeBlock.of("\$S", stripUnescapedBackslashes(literal))
    }

    private fun writeNaturalLiteral(literal: String): CodeBlock {
        val isValidLong = (literal.toLongOrNull() != null)
        if (isValidLong) {
            return CodeBlock.of("\$T.valueOf(\$LL)", BigInteger::class.java, literal)
        } else {
            return CodeBlock.of("new \$T(\$S)", BigInteger::class.java, literal)
        }
    }


    private fun writeComplexLiteralExpression(type: Type, literal: String): CodeBlock {
        val parsingResult = parseComplexLiteral(literal)
        return when (parsingResult) {
            is ComplexLiteralParsingResult.Success -> writeComplexLiteralExpression(type, parsingResult.node)
            is ComplexLiteralParsingResult.Failure -> error("Could not parse complex literal $literal: ${parsingResult.errorMessage}")
        }
    }
    private fun writeComplexLiteralExpression(type: Type, node: ComplexLiteralNode): CodeBlock {
        return when (type) {
            Type.INTEGER -> {
                val literal = node as? ComplexLiteralNode.Literal ?: error("Type mismatch")
                writeLiteralExpression(type, literal.contents)
            }
            Type.BOOLEAN -> {
                val literal = node as? ComplexLiteralNode.Literal ?: error("Type mismatch")
                writeLiteralExpression(type, literal.contents)
            }
            is Type.List -> {
                val squareList = node as? ComplexLiteralNode.SquareList ?: error("Type mismatch")
                val contentsCode = squareList.contents.map {
                    writeComplexLiteralExpression(type.parameter, it)
                }.joinToArgumentsList()
                CodeBlock.of("\$T.asList(\$L)", Arrays::class.java, contentsCode)
            }
            is Type.Maybe -> TODO()
            is Type.FunctionType -> TODO()
            is Type.ParameterType -> TODO()
            is Type.NamedType -> {
                if (isNativeModule(type.ref.module) && type.ref.id == NativeStruct.NATURAL.id) {
                    val literal = node as? ComplexLiteralNode.Literal ?: error("Type mismatch")
                    writeNaturalLiteral(literal.contents)
                } else if (isNativeModule(type.ref.module) && type.ref.id == NativeStruct.UNICODE_STRING.id) {
                    val literal = node as? ComplexLiteralNode.Literal ?: error("Type mismatch")
                    writeUnicodeStringLiteral(literal.contents)
                } else {
                    TODO()
                }
            }
            is Type.InternalParameterType -> TODO()
        }
    }

    private fun stripUnescapedBackslashes(literal: String): String {
        return evaluateStringLiteral(literal).contents
    }

    private fun getType(semlangType: Type, isParameter: Boolean): TypeName {
        return when (semlangType) {
            Type.INTEGER -> ClassName.get(BigInteger::class.java)
            Type.BOOLEAN -> if (isParameter) TypeName.get(java.lang.Boolean::class.java) else TypeName.BOOLEAN
            is Type.List -> ParameterizedTypeName.get(ClassName.get(java.util.List::class.java), getType(semlangType.parameter, true))
            is Type.Maybe -> ParameterizedTypeName.get(ClassName.get(java.util.Optional::class.java), getType(semlangType.parameter, true))
            is Type.FunctionType -> getFunctionType(semlangType)
            is Type.NamedType -> getNamedType(semlangType)
            is Type.ParameterType -> TypeVariableName.get(semlangType.parameter.name)
            is Type.InternalParameterType -> WildcardTypeName.subtypeOf(TypeName.OBJECT)
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
            val resolvedInterfaceRef = module.resolve(interfaceRef) ?: error("error")
            val bareAdapterClass = ClassName.bestGuess("net.semlang.java.Adapter")
            val dataTypeParameter = getType(semlangType.parameters[0], true)
            val otherParameters = semlangType.parameters.drop(1).map { getType(it, true) }
            val bareInterfaceName = getClassNameForTypeId(resolvedInterfaceRef.entityRef)
            val interfaceJavaName = if (otherParameters.isEmpty()) {
                bareInterfaceName
            } else {
                ParameterizedTypeName.get(bareInterfaceName, *otherParameters.toTypedArray())
            }
            return ParameterizedTypeName.get(bareAdapterClass, interfaceJavaName, dataTypeParameter)
        }

        val predefinedClassName: ClassName? = when (semlangType.originalRef.id.namespacedName) {
            listOf("Natural") -> ClassName.get(BigInteger::class.java)
            listOf("Sequence") -> ClassName.bestGuess("net.semlang.java.Sequence")
            listOf("Unicode", "String") -> ClassName.get(String::class.java)
            listOf("Unicode", "CodePoint") -> ClassName.get(Integer::class.java)
            listOf("Bit") -> ClassName.bestGuess("net.semlang.java.Bit")
            listOf("BitsBigEndian") -> ClassName.bestGuess("net.semlang.java.BitsBigEndian")
            listOf("TextOut") -> ClassName.get(PrintStream::class.java)
            listOf("ListBuilder") -> ClassName.get(java.util.List::class.java)
            else -> null
        }

        // TODO: The right general approach is going to be "find the context containing this type, and use the
        // associated package name for that"

        // TODO: Might end up being more complicated? This is probably not quite right
        val className = predefinedClassName ?: getClassNameForTypeId(semlangType.ref)

        if (semlangType.parameters.isEmpty()) {
            return className
        } else {
            val parameterTypeNames: List<TypeName> = semlangType.parameters.map { this.getType(it, true) }
            return ParameterizedTypeName.get(className, *parameterTypeNames.toTypedArray())
        }
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
        for ((testClassName, builder) in testClassBuilders.entries) {
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
        map.put(EntityId.of("List", "subList"), StaticFunctionCallStrategy(javaLists, "subList"))
        map.put(EntityId.of("List", "drop"), StaticFunctionCallStrategy(javaLists, "drop"))
        map.put(EntityId.of("List", "lastN"), StaticFunctionCallStrategy(javaLists, "lastN"))
        // TODO: Find an approach where we can replace this with a simple .get() call...
        // Harder than it sounds, given the BigInteger input; i.e. we need to intelligently replace with a "Size"/"Index" type
        map.put(EntityId.of("List", "get"), StaticFunctionCallStrategy(javaLists, "get"))
        map.put(EntityId.of("List", "size"), wrapInBigint(MethodFunctionCallStrategy("size")))
        map.put(EntityId.of("List", "map"), StaticFunctionCallStrategy(javaLists, "map"))
        map.put(EntityId.of("List", "flatMap"), StaticFunctionCallStrategy(javaLists, "flatMap"))
        map.put(EntityId.of("List", "reduce"), StaticFunctionCallStrategy(javaLists, "reduce"))
        map.put(EntityId.of("List", "reduceThreaded"), StaticFunctionCallStrategy(javaLists, "reduce"))

        val javaIntegers = ClassName.bestGuess("net.semlang.java.Integers")
        // TODO: Add ability to use non-static function calls
        map.put(EntityId.of("Integer", "plus"), MethodFunctionCallStrategy("add"))
        map.put(EntityId.of("Integer", "minus"), MethodFunctionCallStrategy("subtract"))
        map.put(EntityId.of("Integer", "times"), MethodFunctionCallStrategy("multiply"))
        map.put(EntityId.of("Integer", "dividedBy"), StaticFunctionCallStrategy(javaIntegers, "dividedBy"))
        map.put(EntityId.of("Integer", "modulo"), StaticFunctionCallStrategy(javaIntegers, "modulo"))
        map.put(EntityId.of("Integer", "equals"), MethodFunctionCallStrategy("equals"))
        map.put(EntityId.of("Integer", "lessThan"), StaticFunctionCallStrategy(javaIntegers, "lessThan"))
        map.put(EntityId.of("Integer", "greaterThan"), StaticFunctionCallStrategy(javaIntegers, "greaterThan"))
        map.put(EntityId.of("Integer", "sum"), StaticFunctionCallStrategy(javaIntegers, "sum"))

        val javaMaybe = ClassName.bestGuess("net.semlang.java.Maybe")
        map.put(EntityId.of("Maybe", "failure"), StaticFunctionCallStrategy(javaMaybe, "failure"))
        map.put(EntityId.of("Maybe", "success"), StaticFunctionCallStrategy(javaMaybe, "success"))
        map.put(EntityId.of("Maybe", "isSuccess"), StaticFunctionCallStrategy(javaMaybe, "isSuccess"))
        map.put(EntityId.of("Maybe", "assume"), StaticFunctionCallStrategy(javaMaybe, "assume"))
        map.put(EntityId.of("Maybe", "map"), MethodFunctionCallStrategy("map"))
        map.put(EntityId.of("Maybe", "flatMap"), MethodFunctionCallStrategy("flatMap"))
        map.put(EntityId.of("Maybe", "orElse"), MethodFunctionCallStrategy("orElse"))

        val javaSequence = ClassName.bestGuess("net.semlang.java.Sequence")
        // Sequence constructor
        map.put(EntityId.of("Sequence"), StaticFunctionCallStrategy(javaSequence, "create"))
        map.put(EntityId.of("Sequence", "get"), MethodFunctionCallStrategy("get"))
        map.put(EntityId.of("Sequence", "first"), MethodFunctionCallStrategy("first"))

        map.put(EntityId.of("Data", "equals"), DataEqualsFunctionCallStrategy)

        val javaUnicodeStrings = ClassName.bestGuess("net.semlang.java.UnicodeStrings")
        map.put(EntityId.of("Unicode", "String"), StaticFunctionCallStrategy(javaUnicodeStrings, "create"))
        map.put(EntityId.of("Unicode", "String", "length"), StaticFunctionCallStrategy(javaUnicodeStrings, "length"))

        val javaTextOut = ClassName.bestGuess("net.semlang.java.TextOut")
        map.put(EntityId.of("TextOut", "print"), StaticFunctionCallStrategy(javaTextOut, "print"))

        val javaListBuilder = ClassName.bestGuess("net.semlang.java.ListBuilders")
        map.put(EntityId.of("ListBuilder", "create"), StaticFunctionCallStrategy(javaListBuilder, "create"))
        map.put(EntityId.of("ListBuilder", "append"), StaticFunctionCallStrategy(javaListBuilder, "append"))
        map.put(EntityId.of("ListBuilder", "appendAll"), StaticFunctionCallStrategy(javaListBuilder, "appendAll"))
        map.put(EntityId.of("ListBuilder", "build"), PassedThroughVarFunctionCallStrategy)

        // Natural constructor
        val javaNaturals = ClassName.bestGuess("net.semlang.java.Naturals")
        map.put(EntityId.of("Natural"), StaticFunctionCallStrategy(javaNaturals, "fromInteger"))

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
            override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
                return CodeBlock.of("BigInteger.valueOf(\$L)", delegate.apply(chosenTypes, arguments))
            }
        }
    }

    val PassedThroughVarFunctionCallStrategy = object: FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            if (arguments.size != 1) error("")
            return writeExpression(arguments[0])
        }
    }

    val DataEqualsFunctionCallStrategy = object: FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            val left = writeExpression(arguments[0])
            val right = writeExpression(arguments[1])
            // TODO: We may need to vary this based on the chosen type in the future
            val type = chosenTypes[0]
            if (type == Type.BOOLEAN) {
                return CodeBlock.of("(\$L == \$L)", left, right)
            } else {
                return CodeBlock.of("\$L.equals(\$L)", left, right)
            }
        }
    }

    inner class StaticFunctionCallStrategy(val className: ClassName, val methodName: String): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            if (chosenTypes.isNotEmpty()) {
                return CodeBlock.of("\$T.<\$L>\$L(\$L)", className, getChosenTypesCode(chosenTypes), methodName, getArgumentsBlock(arguments))
            }
            return CodeBlock.of("\$T.\$L(\$L)", className, methodName, getArgumentsBlock(arguments))
        }
    }

    inner class MethodFunctionCallStrategy(val methodName: String): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            return CodeBlock.of("\$L.\$L(\$L)", writeExpression(arguments[0]), methodName, getArgumentsBlock(arguments.drop(1)))
        }
    }

    inner private class InstanceFunctionCallStrategy(val interfac: Interface): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            val parametersMap = interfac.typeParameters.map { Type.ParameterType(it) }.zip(chosenTypes.map { if (it == null) error("") else it }).toMap()
            val instanceType = interfac.instanceType.replacingExternalParameters(parametersMap)

            val instanceAnonymousClass = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(getType(instanceType, false))
            for ((method, constructorArgument) in interfac.methods.zip(arguments)) {
                val methodSpec = MethodSpec.methodBuilder(method.name)
                        // TODO: This "isParameter" fix is probably needed in a lot of places
                        .returns(getType(method.returnType.replacingExternalParameters(parametersMap), method.returnType in parametersMap.keys))
                        .addModifiers(Modifier.PUBLIC)
                for (arg in method.arguments) {
                    methodSpec.addParameter(getType(arg.type.replacingExternalParameters(parametersMap), false), arg.name)
                }

                // So adapterArgument is something like Function.identity|(_) that we want to replace with Function.identity(data)
                val returnValue = convertBindingToCall(constructorArgument, method.arguments.map { TypedExpression.Variable(it.type, it.name) })

                methodSpec.addStatement("return \$L", writeExpression(returnValue))
                instanceAnonymousClass.addMethod(methodSpec.build())
            }

            return CodeBlock.of("\$L", instanceAnonymousClass.build())
        }
    }

    inner private class AdapterFunctionCallStrategy(val interfac: Interface): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            val dataType = chosenTypes[0]!!
            val parametersMap = interfac.typeParameters.map { Type.ParameterType(it) }.zip(chosenTypes.drop(1).map { if (it == null) error("") else it }).toMap()
            val instanceType = interfac.instanceType.replacingExternalParameters(parametersMap)

            val functionAnonymousClass = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(getType(Type.FunctionType.create(listOf(), listOf(dataType), instanceType), false))

            val applyMethodSpec = MethodSpec.methodBuilder("apply")
                    .addParameter(getType(dataType, true), "data")
                    .returns(getType(instanceType, true))
                    .addModifiers(Modifier.PUBLIC)

            val instanceAnonymousClass = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(getType(instanceType, false))
            for ((method, adapterArgument) in interfac.methods.zip(arguments)) {
                val methodSpec = MethodSpec.methodBuilder(method.name)
                        .returns(getType(method.returnType.replacingExternalParameters(parametersMap), false))
                        .addModifiers(Modifier.PUBLIC)
                for (arg in method.arguments) {
                    methodSpec.addParameter(getType(arg.type.replacingExternalParameters(parametersMap), false), arg.name)
                }

                // So adapterArgument is something like Function.identity|(_) that we want to replace with Function.identity(data)
                val returnValue = convertBindingToCallReplacingOnlyOpenBinding(adapterArgument, TypedExpression.Variable(dataType, "data"), method.arguments.map { TypedExpression.Variable(it.type, it.name) })

                methodSpec.addStatement("return \$L", writeExpression(returnValue))
                instanceAnonymousClass.addMethod(methodSpec.build())
            }

            applyMethodSpec.addStatement("return \$L", instanceAnonymousClass.build())
            functionAnonymousClass.addMethod(applyMethodSpec.build())
            return CodeBlock.of("\$L", functionAnonymousClass.build())
        }
    }

    inner private class OptionConstructorCallStrategy(val unionClassName: ClassName, val option: Option): FunctionCallStrategy {
        override fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock {
            if (option.type != null) {
                return CodeBlock.of("\$T.create\$L(\$L)", unionClassName, option.name, writeExpression(arguments[0]))
            } else {
                return CodeBlock.of("\$T.create\$L()", unionClassName, option.name)
            }
        }
    }

    private fun convertBindingToCall(binding: TypedExpression, methodArguments: List<TypedExpression.Variable>): TypedExpression {
        val outputType = (binding.type as Type.FunctionType.Ground).outputType
        return when (binding) {
            is TypedExpression.Variable -> {
                return TypedExpression.ExpressionFunctionCall(outputType, binding, methodArguments, listOf(), listOf())
            }
            is TypedExpression.IfThen -> TODO()
            is TypedExpression.NamedFunctionCall -> TODO()
            is TypedExpression.ExpressionFunctionCall -> TODO()
            is TypedExpression.Literal -> TODO()
            is TypedExpression.ListLiteral -> TODO()
            is TypedExpression.NamedFunctionBinding -> {
                val arguments = binding.bindings.map { it ?: TODO() }
                val chosenParameters = binding.chosenParameters.map { it ?: error("") }
                return TypedExpression.NamedFunctionCall(outputType, binding.functionRef, binding.resolvedFunctionRef, arguments, chosenParameters, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> TODO()
            is TypedExpression.Follow -> TODO()
            is TypedExpression.InlineFunction -> TODO()
        }
    }

    // TODO: Name no longer fully covers what this is doing, refactor?
    private fun convertBindingToCallReplacingOnlyOpenBinding(binding: TypedExpression, openBindingReplacement: TypedExpression, methodArguments: List<TypedExpression.Variable>): TypedExpression {
        val outputType = (binding.type as Type.FunctionType.Ground).outputType
        return when (binding) {
            is TypedExpression.Variable -> {
                val arguments = listOf(openBindingReplacement) + methodArguments
                return TypedExpression.ExpressionFunctionCall(outputType, binding, arguments, listOf(), listOf())
            }
            is TypedExpression.IfThen -> TODO()
            is TypedExpression.NamedFunctionCall -> TODO()
            is TypedExpression.ExpressionFunctionCall -> TODO()
            is TypedExpression.Literal -> TODO()
            is TypedExpression.ListLiteral -> TODO()
            is TypedExpression.NamedFunctionBinding -> {
                // TODO: Do we need the openBindingReplacement here, too? Add a test for that
                val arguments = binding.bindings.replacingFirst(null, openBindingReplacement)
                        .map { it ?: TODO() }
                val chosenParameters = binding.chosenParameters.map { it ?: error("") }
                return TypedExpression.NamedFunctionCall(outputType, binding.functionRef, binding.resolvedFunctionRef, arguments, chosenParameters, chosenParameters)
            }
            is TypedExpression.ExpressionFunctionBinding -> TODO()
            is TypedExpression.Follow -> TODO()
            is TypedExpression.InlineFunction -> TODO()
        }
    }

    private fun getChosenTypesCode(chosenSemlangTypes: List<Type?>): CodeBlock {
        val chosenTypes = chosenSemlangTypes.map { if (it == null) TypeName.OBJECT else this.getType(it, true) }
        val typesCodeBuilder = CodeBlock.builder()
        typesCodeBuilder.add("\$T", chosenTypes[0])
        for (chosenType in chosenTypes.drop(1)) {
            typesCodeBuilder.add(", \$T", chosenType)
        }
        return typesCodeBuilder.build()
    }

    private fun getClassNameForTypeId(ref: ResolvedEntityRef): ClassName {
        val moduleName = ref.module.name.module
        val javaPackage = javaPackageString + "." + sanitizePackageName(moduleName)
        val names = ref.id.namespacedName.map(this::sanitizeClassName)
        try {
            return ClassName.get(javaPackage, names[0], *names.drop(1).toTypedArray())
        } catch (e: RuntimeException) {
            throw RuntimeException("Problem for reference $ref from entity ID ${ref.id} and module ID ${ref.id}", e)
        }
    }

    private fun sanitizePackageName(moduleName: String): String {
        return moduleName.replace("-", "_")
    }

    private fun sanitizeClassName(className: String): String {
        return className.replace("-", "_")
    }
}

private fun <E> List<E>.replacingFirst(toReplace: E, replacement: E): List<E> {
    val indexToReplace = this.indexOf(toReplace)
    val copy = ArrayList<E>(this)
    copy.set(indexToReplace, replacement)
    return copy
}

private interface FunctionCallStrategy {
    fun apply(chosenTypes: List<Type?>, arguments: List<TypedExpression>): CodeBlock
}

private fun getFunctionTypeStrategy(type: Type.FunctionType): FunctionTypeStrategy {
    if (type.getNumArgTypes() == 0) {
        return SupplierFunctionTypeStrategy
    } else if (type.getNumArgTypes() == 1) {
        return FunctionFunctionTypeStrategy
    }

    if (type.getNumArgTypes() < 4) {
        return RuntimeLibraryFunctionTypeStrategy(type.getNumArgTypes())
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
        if (type.getNumArgTypes() > 0) {
            error("")
        }
        val className = ClassName.get(java.util.function.Supplier::class.java)

        return ParameterizedTypeName.get(className, getTypeForParameter(type.getOutputType()).box())
    }
}

object FunctionFunctionTypeStrategy: FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName {
        if (type.getNumArgTypes() != 1) {
            error("")
        }
        val className = ClassName.get(java.util.function.Function::class.java)

        return ParameterizedTypeName.get(className, getTypeForParameter(type.getArgTypes()[0]).box(), getTypeForParameter(type.getOutputType()).box())
    }

}

// We want to get these directly, without translating the inner type bits, for now...
private fun Type.FunctionType.getArgTypes(): List<Type> {
    return when (this) {
        is Type.FunctionType.Ground -> this.argTypes
        is Type.FunctionType.Parameterized -> this.argTypes
    }
}
private fun Type.FunctionType.getOutputType(): Type {
    return when (this) {
        is Type.FunctionType.Ground -> this.outputType
        is Type.FunctionType.Parameterized -> this.outputType
    }
}

class RuntimeLibraryFunctionTypeStrategy(val numArgs: Int): FunctionTypeStrategy {
    override fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName {
        val className = ClassName.bestGuess("net.semlang.java.function.Function" + numArgs)

        val parameterTypes = ArrayList<TypeName>()
        for (semlangType in type.getArgTypes()) {
            parameterTypes.add(getTypeForParameter(semlangType).box())
        }
        parameterTypes.add(getTypeForParameter(type.getOutputType()).box())

        return ParameterizedTypeName.get(className, *parameterTypes.toTypedArray())
    }
}

private interface FunctionTypeStrategy {
    fun getTypeName(type: Type.FunctionType, getTypeForParameter: (Type) -> TypeName): TypeName
}

private fun ClassName.sanitize(): ClassName {
    return ClassName.get(this.packageName().replace("-", "_"), this.simpleName())
}

// TODO: This will probably become a language-wide distinction
// TODO: Maybe put this on the ValidatedModule itself?
private fun isDataType(type: Type, containingModule: ValidatedModule?): Boolean {
    return when (type) {
        Type.INTEGER -> true
        Type.BOOLEAN -> true
        is Type.List -> isDataType(type.parameter, containingModule)
        is Type.Maybe -> isDataType(type.parameter, containingModule)
        is Type.FunctionType -> false
        is Type.ParameterType -> false // Might have cases in the future where a parameter can be restricted to be data
        is Type.NamedType -> {
            if (containingModule == null) {
                // TODO: For now we assume these are all data other than Sequence
                return getNativeStructs().containsKey(type.ref.id) && type.ref.id != NativeStruct.SEQUENCE.id
            }
            val entityResolution = containingModule.resolve(type.ref) ?: error("failed entityResolution for ${type.ref}")
            when (entityResolution.type) {
                FunctionLikeType.NATIVE_FUNCTION -> error("Native functions shouldn't be types")
                FunctionLikeType.FUNCTION -> error("Functions shouldn't be types")
                FunctionLikeType.STRUCT_CONSTRUCTOR -> {
                    val struct = containingModule.getInternalStruct(entityResolution.entityRef)
                    isDataStruct(struct.struct, struct.module)
                }
                FunctionLikeType.INSTANCE_CONSTRUCTOR -> false
                FunctionLikeType.ADAPTER_CONSTRUCTOR -> false
                FunctionLikeType.OPAQUE_TYPE -> false
                FunctionLikeType.UNION_TYPE -> TODO()
                FunctionLikeType.UNION_OPTION_CONSTRUCTOR -> TODO()
                FunctionLikeType.UNION_WHEN_FUNCTION -> TODO()
            }
        }
        is Type.InternalParameterType -> TODO()
    }
}

private fun isDataStruct(struct: Struct, containingModule: ValidatedModule?): Boolean {
    for (member in struct.members) {
        val isDataType = isDataType(member.type, containingModule)
        if (!isDataType) {
            return false
        }
    }
    return true
}

private fun List<CodeBlock>.joinToArgumentsList(): CodeBlock {
    if (this.isEmpty()) {
        return CodeBlock.of("")
    }
    val builder = CodeBlock.builder()
    builder.add("\$L", (this[0]))
    for (argument in this.drop(1)) {
        builder.add(", \$L", argument)
    }
    return builder.build()
}

private fun ClassName.parameterizedWith(typeVariables: List<TypeVariableName>): TypeName {
    if (typeVariables.isEmpty()) {
        return this
    } else {
        return ParameterizedTypeName.get(this, *(typeVariables.toTypedArray()))
    }
}
