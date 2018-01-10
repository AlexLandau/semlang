package net.semlang.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function

//TODO: Before trying to write an interpreter in another language, I think we
//actually want to have the sem0 version of this, for which the validation and
//interpreter will be simpler
val LANGUAGE = "sem1"
val FORMAT_VERSION = "0.1.0"

fun toJsonText(module: ValidatedModule): String {
    return toJsonText(toJson(module))
}

private fun toJsonText(node: JsonNode): String {
    val mapper: ObjectMapper = ObjectMapper()
    val writer = mapper.writer()
    return writer.writeValueAsString(node)
}

fun toJson(module: ValidatedModule): JsonNode {
    val mapper: ObjectMapper = ObjectMapper()
    val node = mapper.createObjectNode()

    node.put("semlang", LANGUAGE)
    node.put("version", FORMAT_VERSION)

    // TODO: Put information about upstream contexts
    // TODO: Maybe put identity information about this context?

    addArray(node, "functions", module.ownFunctions.values, ::addFunction)
    addArray(node, "structs", module.ownStructs.values, ::addStruct)
    addArray(node, "interfaces", module.ownInterfaces.values, ::addInterface)
    return node
}

// TODO: "add" naming scheme is pretty bad
private fun <T> addArray(objectNode: ObjectNode, name: String,
                         elements: Collection<T>, addElement: (node: ObjectNode, elem: T) -> Unit) {
    val arrayNode = objectNode.putArray(name)
    elements.forEach { element ->
        addElement(arrayNode.addObject(), element)
    }
}

private fun addStruct(node: ObjectNode, struct: Struct) {
    node.put("id", struct.id.toString())
    if (struct.typeParameters.isNotEmpty()) {
        addTypeParameters(node.putArray("typeParameters"), struct.typeParameters)
    }
    if (struct.annotations.isNotEmpty()) {
        addArray(node, "annotations", struct.annotations, ::addAnnotation)
    }
    addArray(node, "members", struct.members, ::addMember)
    val requires = struct.requires
    if (requires != null) {
        addBlock(node.putArray("requires"), requires)
    }
}

private fun parseStruct(node: JsonNode): UnvalidatedStruct {
    if (!node.isObject()) error("Expected a struct to be an object")

    val id = parseEntityId(node["id"] ?: error("Structs must have an 'id' field"))
    val typeParameters = parseTypeParameters(node["typeParameters"])
    val annotations = parseAnnotations(node["annotations"])
    val members = parseMembers(node["members"])
    val requires = node["requires"]?.let { parseBlock(it) }

    return UnvalidatedStruct(id, typeParameters, members, requires, annotations, null)
}

private fun parseTypeParameters(node: JsonNode?): List<String> {
    if (node == null) {
        //Omitted when there are no type parameters
        return listOf()
    }
    if (!node.isArray()) error("Type parameters should be in an array")
    return node.map { typeParamNode -> typeParamNode.textValue() ?: error("Type parameters should be stored as text") }
}

private fun parseAnnotations(node: JsonNode?): List<Annotation> {
    if (node == null) {
        //Omitted when there are no annotations
        return listOf()
    }
    if (!node.isArray()) error("Annotations should be in an array")
    return node.map { annotationNode -> parseAnnotation(annotationNode) }
}

private fun parseEntityRef(node: JsonNode): EntityRef {
    val text = node.textValue() ?: error("IDs and refs should be strings")
    val parts = text.split(":")
    if (parts.size == 1) {
        return EntityRef(null, parseEntityId(parts[0]))
    } else if (parts.size == 2) {
        return EntityRef(ModuleRef(null, parts[0], null), parseEntityId(parts[1]))
    } else if (parts.size == 3) {
        return EntityRef(ModuleRef(parts[0], parts[1], null), parseEntityId(parts[2]))
    } else if (parts.size == 4) {
        return EntityRef(ModuleRef(parts[0], parts[1], parts[2]), parseEntityId(parts[3]))
    } else {
        error("Too many colon-separated parts in the EntityRef: '$text'")
    }
}

// TODO: Support module refs
private fun parseEntityId(node: JsonNode): EntityId {
    val text = node.textValue() ?: error("IDs should be strings")
    return parseEntityId(text)
}
private fun parseEntityId(text: String): EntityId {
    val parts = text.split(".")
    if (parts.isEmpty()) {
        error("")
    }
    return EntityId(parts)
}

private fun parseMembers(node: JsonNode): List<Member> {
    if (!node.isArray()) error("Members should be in an array")
    return node.map { memberNode -> parseMember(memberNode) }
}

private fun addMember(node: ObjectNode, member: Member) {
    node.put("name", member.name)
    node.set("type", toTypeNode(member.type))
}

private fun toTypeNode(type: Type): JsonNode {
    val factory = JsonNodeFactory.instance
    return when (type) {
        Type.INTEGER -> TextNode(type.toString())
        Type.NATURAL -> TextNode(type.toString())
        Type.BOOLEAN -> TextNode(type.toString())
        is Type.List -> {
            ObjectNode(factory).set("List", toTypeNode(type.parameter))
        }
        is Type.Try -> {
            ObjectNode(factory).set("Try", toTypeNode(type.parameter))
        }
        is Type.FunctionType -> {
            val node = ObjectNode(factory)
            val argsArray = node.putArray("from")
            type.argTypes.forEach { argType ->
                argsArray.add(toTypeNode(argType))
            }
            node.set("to", toTypeNode(type.outputType))
            node
        }
        is Type.NamedType -> {
            val node = ObjectNode(factory)
            node.put("name", type.ref.toString())
            if (type.parameters.isNotEmpty()) {
                val paramsArray = node.putArray("params")
                type.parameters.forEach { parameter ->
                    paramsArray.add(toTypeNode(parameter))
                }
            }
            node
        }
    }
}

private fun parseMember(node: JsonNode): Member {
    val name = node["name"].textValue() ?: error("Member names should be strings")
    val type = parseType(node["type"] ?: error("Members must have types"))
    return Member(name, type)
}

private fun parseType(node: JsonNode): Type {
    if (node.isTextual()) {
        return when (node.textValue()) {
            "Integer" -> Type.INTEGER
            "Boolean" -> Type.BOOLEAN
            "Natural" -> Type.NATURAL
            else -> error("Unrecognized type string: ${node.textValue()}")
        }
    }

    if (!node.isObject()) {
        error("Was expecting a string or object node for a type; was $node")
    }

    if (node.has("name")) {
        val id = parseEntityRef(node["name"])
        val parameters = if (node.has("params")) {
            val paramsArray = node["params"]
            paramsArray.map(::parseType)
        } else {
            listOf()
        }
        return Type.NamedType(id, parameters)
    } else if (node.has("from")) {
        val argTypes = node["from"].map(::parseType)
        val outputType = parseType(node["to"])
        return Type.FunctionType(argTypes, outputType)
    } else if (node.has("Try")) {
        return Type.Try(parseType(node["Try"]))
    } else if (node.has("List")) {
        return Type.List(parseType(node["List"]))
    }
    error("Unrecognized type: $node")
}

private fun addAnnotation(node: ObjectNode, annotation: Annotation) {
    node.put("name", annotation.name)
    if (annotation.values.isNotEmpty()) {
        val valuesArray = node.putArray("values")
        addAnnotationItems(valuesArray, annotation.values)
    }
}

fun addAnnotationItems(array: ArrayNode, values: List<AnnotationArgument>) {
    for (arg in values) {
        val unused: Any = when (arg) {
            is AnnotationArgument.Literal -> {
                array.add(arg.value)
            }
            is AnnotationArgument.List -> {
                val list = array.addArray()
                addAnnotationItems(list, arg.values)
            }
        }
    }
}

private fun parseAnnotation(node: JsonNode): Annotation {
    if (!node.isObject()) error("Expected an annotation to be an object")

    val name = node["name"]?.textValue() ?: error("Annotations must have names that are text")
    val values = node["values"]?.let(::parseAnnotationArgs) ?: listOf()

    return Annotation(name, values)
}

private fun parseAnnotationArgs(node: JsonNode): List<AnnotationArgument> {
    return node.map(::parseAnnotationArg)
}

private fun parseAnnotationArg(node: JsonNode): AnnotationArgument {
    if (node.isTextual) {
        return AnnotationArgument.Literal(node.textValue())
    } else if (node.isArray) {
        return AnnotationArgument.List(parseAnnotationArgs(node))
    }
    error("Unrecognized annotation arg type: ${node}")
}

private fun addInterface(node: ObjectNode, interfac: Interface) {
    node.put("id", interfac.id.toString())
    if (interfac.annotations.isNotEmpty()) {
        addArray(node, "annotations", interfac.annotations, ::addAnnotation)
    }
    if (interfac.typeParameters.isNotEmpty()) {
        addTypeParameters(node.putArray("typeParameters"), interfac.typeParameters)
    }
    addArray(node, "methods", interfac.methods, ::addMethod)
}

private fun parseInterface(node: JsonNode): UnvalidatedInterface {
    if (!node.isObject()) error("Expected an interface to be an object")

    val id = parseEntityId(node["id"] ?: error("Interfaces must have an 'id' field"))
    val typeParameters = parseTypeParameters(node["typeParameters"])
    val methods = parseMethods(node["methods"] ?: error("Interfaces must have a 'methods' array"))
    val annotations = parseAnnotations(node["annotations"])

    return UnvalidatedInterface(id, typeParameters, methods, annotations, null)
}

private fun parseMethods(node: JsonNode): List<UnvalidatedMethod> {
    if (!node.isArray()) error("Methods should be in an array")
    return node.map { methodNode -> parseMethod(methodNode) }
}

private fun addMethod(node: ObjectNode, method: Method) {
    node.put("name", method.name)
    if (method.typeParameters.isNotEmpty()) {
        addTypeParameters(node.putArray("typeParameters"), method.typeParameters)
    }
    addArray(node, "arguments", method.arguments, ::addFunctionArgument)
    node.set("returnType", toTypeNode(method.returnType))
}

private fun parseMethod(node: JsonNode): UnvalidatedMethod {
    if (!node.isObject()) error("Expected a method to be an object")

    val name = node["name"]?.textValue() ?: error("Methods must have a 'name' field")
    val typeParameters = parseTypeParameters(node["typeParameters"])
    val arguments = parseArguments(node["arguments"] ?: error("Methods must have an 'arguments' array"))
    val returnType = parseType(node["returnType"] ?: error("Methods must have a 'returnType' string"))

    return UnvalidatedMethod(name, typeParameters, arguments, returnType)
}

private fun parseArguments(node: JsonNode): List<UnvalidatedArgument> {
    if (!node.isArray()) error("Arguments should be in an array")
    return node.map { argumentNode -> parseArgument(argumentNode) }
}

private fun addFunction(node: ObjectNode, function: ValidatedFunction) {
    node.put("id", function.id.toString())
    if (function.annotations.isNotEmpty()) {
        addArray(node, "annotations", function.annotations, ::addAnnotation)
    }
    if (function.typeParameters.isNotEmpty()) {
        addTypeParameters(node.putArray("typeParameters"), function.typeParameters)
    }
    addArray(node, "arguments", function.arguments, ::addFunctionArgument)
    node.set("returnType", toTypeNode(function.returnType))

    addBlock(node.putArray("block"), function.block)
}

private fun parseFunction(node: JsonNode): Function {
    if (!node.isObject()) error("Expected a function to be an object")

    val id = parseEntityId(node["id"] ?: error("Functions must have an 'id' field"))
    val typeParameters = parseTypeParameters(node["typeParameters"])
    val arguments = parseArguments(node["arguments"] ?: error("Functions must have an 'arguments' array"))
    val returnType = parseType(node["returnType"] ?: error("Functions must have a 'returnType' string"))
    val block = parseBlock(node["block"] ?: error("Functions must have a 'block' array"))
    val annotations = parseAnnotations(node["annotations"])

    return Function(id, typeParameters, arguments, returnType, block, annotations, null, null)
}

private fun addBlock(node: ArrayNode, block: TypedBlock) {
    block.assignments.forEach { assignment ->
        addAssignment(node.addObject(), assignment)
    }
    val returnNode = node.addObject()
    addExpression(returnNode.putObject("return"), block.returnedExpression)
}

private fun parseBlock(node: JsonNode): Block {
    if (!node.isArray()) error("Expected a block to be an array")

    val size = node.size()
    if (size < 1) {
        error("Expected at least one entry in the block array")
    }
    val assignments = ArrayList<Assignment>()
    for (index in 0..(node.size() - 2)) {
        assignments.add(parseAssignment(node[index]))
    }
    val returnedExpression = parseExpression(node.last()["return"])

    return Block(assignments, returnedExpression, null)
}

private fun addAssignment(node: ObjectNode, assignment: ValidatedAssignment) {
    node.put("let", assignment.name)
    addExpression(node.putObject("be"), assignment.expression)
}

private fun parseAssignment(node: JsonNode): Assignment {
    if (!node.isObject()) error("Expected an assignment to be an object")

    val name = node["let"]?.textValue() ?: error("Assignments should have a 'let' field indicating the variable name")
    val expression = parseExpression(node["be"] ?: error("Assignments should have a '=' field indicating the expression"))

    return Assignment(name, null, expression, null)
}

private fun addExpression(node: ObjectNode, expression: TypedExpression) {
    // ignoreMe forces the compiler to make this "when" expression exhaustive
    val ignoreMe: Unit = when (expression) {
        is TypedExpression.Variable -> {
            node.put("type", "var")
            node.put("var", expression.name)
            return
        }
        is TypedExpression.IfThen -> {
            node.put("type", "ifThen")
            addExpression(node.putObject("if"), expression.condition)
            addBlock(node.putArray("then"), expression.thenBlock)
            addBlock(node.putArray("else"), expression.elseBlock)
            return
        }
        is TypedExpression.NamedFunctionCall -> {
            node.put("type", "namedCall")
            node.put("function", expression.functionRef.toString())
            addChosenParameters(node.putArray("chosenParameters"), expression.chosenParameters)
            addArray(node, "arguments", expression.arguments, ::addExpression)
            return
        }
        is TypedExpression.ExpressionFunctionCall -> {
            node.put("type", "expressionCall")
            addExpression(node.putObject("expression"), expression.functionExpression)
            addChosenParameters(node.putArray("chosenParameters"), expression.chosenParameters)
            addArray(node, "arguments", expression.arguments, ::addExpression)
            return
        }
        is TypedExpression.Literal -> {
            node.put("type", "literal")
            node.set("literalType", toTypeNode(expression.type))
            node.put("value", expression.literal)
            return
        }
        is TypedExpression.ListLiteral -> {
            node.put("type", "list")
            node.set("chosenParameter", toTypeNode(expression.chosenParameter))
            addArray(node, "contents", expression.contents, ::addExpression)
        }
        is TypedExpression.Follow -> {
            node.put("type", "follow")
            addExpression(node.putObject("expression"), expression.structureExpression)
            node.put("name", expression.name)
            return
        }
        is TypedExpression.NamedFunctionBinding -> {
            node.put("type", "namedBinding")
            node.put("function", expression.functionRef.toString())
            addChosenParameters(node.putArray("chosenParameters"), expression.chosenParameters)
            addArray(node, "bindings", expression.bindings, ::addBinding)
            addBindings(node.putArray("bindings"), expression.bindings)
            return
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            node.put("type", "expressionBinding")
            addExpression(node.putObject("expression"), expression.functionExpression)
            addChosenParameters(node.putArray("chosenParameters"), expression.chosenParameters)
            addArray(node, "bindings", expression.bindings, ::addBinding)
            return
        }
        is TypedExpression.InlineFunction -> {
            node.put("type", "inlineFunction")
            addArray(node, "arguments", expression.arguments, ::addFunctionArgument)
            addBlock(node.putArray("body"), expression.block)
            return
        }
    }
}

fun addBindings(bindingsArray: ArrayNode, bindings: List<TypedExpression?>) {
    bindings.forEach { binding ->
        if (binding != null) {
            addBinding(bindingsArray.addObject(), binding)
        } else {
            bindingsArray.addNull()
        }
    }
}

private fun parseExpression(node: JsonNode): Expression {
    if (!node.isObject()) error("Expected an expression to be an object")

    val type = node["type"]?.textValue() ?: error("Expressions must have a 'type' text field")
    return when (type) {
        "var" -> {
            val name = node["var"]?.textValue() ?: error("Variable expressions must have a 'var' text field")
            return Expression.Variable(name, location = null)
        }
        "ifThen" -> {
            val condition = parseExpression(node["if"])
            val thenBlock = parseBlock(node["then"])
            val elseBlock = parseBlock(node["else"])
            return Expression.IfThen(condition, thenBlock, elseBlock, location = null)
        }
        "namedCall" -> {
            val functionRef = parseEntityRef(node["function"])
            val arguments = parseExpressionsArray(node["arguments"])
            val chosenParameters = parseChosenParameters(node["chosenParameters"])
            return Expression.NamedFunctionCall(functionRef, arguments, chosenParameters, location = null, functionRefLocation = null)
        }
        "expressionCall" -> {
            val functionExpression = parseExpression(node["expression"])
            val arguments = parseExpressionsArray(node["arguments"])
            val chosenParameters = parseChosenParameters(node["chosenParameters"])
            return Expression.ExpressionFunctionCall(functionExpression, arguments, chosenParameters, location = null)
        }
        "literal" -> {
            val literalType = parseType(node["literalType"])
            val literal = node["value"]?.textValue() ?: error("Expected a literal expression to have a 'value' text field")
            return Expression.Literal(literalType, literal, location = null)
        }
        "list" -> {
            val contents = parseExpressionsArray(node["contents"])
            val chosenParameter = parseType(node["chosenParameter"])
            return Expression.ListLiteral(contents, chosenParameter, location = null)
        }
        "follow" -> {
            val innerExpression = parseExpression(node["expression"])
            val name = node["name"]?.textValue() ?: error("Expected a follow expression to have a 'name' text field")
            return Expression.Follow(innerExpression, name, location = null)
        }
        "namedBinding" -> {
            val functionRef = parseEntityRef(node["function"])
            val bindings = parseBindingsArray(node["bindings"])
            val chosenParameters = parseChosenParameters(node["chosenParameters"])
            return Expression.NamedFunctionBinding(functionRef, bindings, chosenParameters, location = null)
        }
        "expressionBinding" -> {
            val functionExpression = parseExpression(node["expression"])
            val bindings = parseBindingsArray(node["bindings"])
            val chosenParameters = parseChosenParameters(node["chosenParameters"])
            return Expression.ExpressionFunctionBinding(functionExpression, bindings, chosenParameters, location = null)
        }
        "inlineFunction" -> {
            val arguments = parseArguments(node["arguments"])
            val block = parseBlock(node["body"])
            return Expression.InlineFunction(arguments, block, location = null)
        }
        else -> {
            error("Unknown expression type '$type'")
        }
    }
}

private fun parseChosenParameters(node: JsonNode): List<Type> {
    if (!node.isArray()) error("Expected an array of expressions")
    return node.map { typeNode -> parseType(typeNode) }
}

private fun parseExpressionsArray(node: JsonNode): List<Expression> {
    if (!node.isArray()) error("Expected an array of expressions")
    return node.map { expressionNode -> parseExpression(expressionNode) }
}

private fun parseBindingsArray(node: JsonNode): List<Expression?> {
    if (!node.isArray()) error("Expected an array of bindings")
    return node.map { expressionNode -> parseBinding(expressionNode) }
}

private fun parseBinding(node: JsonNode): Expression? {
    if (node["type"] != null) {
        return parseExpression(node)
    }
    return null
}

private fun addBinding(node: ObjectNode, binding: TypedExpression?) {
    // Just leave the object empty if the binding is null
    if (binding != null) {
        addExpression(node, binding)
    }
}

private fun addChosenParameters(node: ArrayNode, chosenParameters: List<Type>) {
    chosenParameters.forEach { parameter ->
        node.add(toTypeNode(parameter))
    }
}

private fun addFunctionArgument(node: ObjectNode, argument: Argument) {
    node.put("name", argument.name)
    node.set("type", toTypeNode(argument.type))
}

private fun parseArgument(node: JsonNode): UnvalidatedArgument {
    if (!node.isObject()) error("Arguments should be objects")
    val name = node["name"].textValue() ?: error("Arguments should have a 'name' textual value")
    val type = parseType(node["type"] ?: error("Arguments should have a 'type' value"))
    return UnvalidatedArgument(name, type, null)
}

private fun addTypeParameters(node: ArrayNode, typeParameters: List<String>) {
    typeParameters.forEach { typeParameter ->
        node.add(typeParameter)
    }
}

fun fromJson(node: JsonNode): RawContext {
    if (!node.isObject()) {
        error("Expected an object node")
    }

    if (node.get("semlang").asText() != LANGUAGE) {
        error("Can't currently parse dialects other than sem1")
    }
    if (node.get("version").asText() != FORMAT_VERSION) {
        error("Currently no backwards-compatibility support")
    }

    val functions = parseFunctions(node.get("functions"))
    val structs = parseStructs(node.get("structs"))
    val interfaces = parseInterfaces(node.get("interfaces"))

    return RawContext(functions, structs, interfaces)
}

private fun parseFunctions(node: JsonNode): List<Function> {
    if (!node.isArray()) error("Expected functions to be in an array")
    return node.map { functionNode -> parseFunction(functionNode) }
}

private fun parseStructs(node: JsonNode): List<UnvalidatedStruct> {
    if (!node.isArray()) error("Expected structs to be in an array")
    return node.map { structNode -> parseStruct(structNode) }
}

private fun parseInterfaces(node: JsonNode): List<UnvalidatedInterface> {
    if (!node.isArray()) error("Expected interfaces to be in an array")
    return node.map { interfaceNode -> parseInterface(interfaceNode) }
}
