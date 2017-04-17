package semlang.parser

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import sem1.antlr.Sem1Lexer
import sem1.antlr.Sem1Parser
import sem1.antlr.Sem1ParserBaseListener
import semlang.api.*
import semlang.api.Function
import semlang.api.Annotation
import java.io.File
import java.util.*

private fun parseFunction(function: Sem1Parser.FunctionContext): Function {
    val id: FunctionId = parseFunctionId(function.function_id())

    val typeParameters: List<String> = if (function.cd_ids() != null) {
        parseCommaDelimitedIds(function.cd_ids())
    } else {
        listOf()
    }
    if (function.function_arguments() == null) {
        error("function_arguments() is null: " + function.getText()
                + "\n function_id: " + function.function_id()
                + "\n cd_ids: " + function.cd_ids()
                + "\n function_arguments: " + function.function_arguments()
                + "\n type:" + function.type()
                + "\n block:" + function.block()
        )
    }
    val arguments: List<Argument> = parseFunctionArguments(function.function_arguments())
    val returnType: Type = parseType(function.type())

    val ambiguousBlock: AmbiguousBlock = parseBlock(function.block())
    val argumentVariableIds = arguments.map { arg -> FunctionId.of(arg.name) }
    val block = scopeBlock(argumentVariableIds, ambiguousBlock)

    val annotations = parseAnnotations(function.annotations())

    return Function(id, typeParameters, arguments, returnType, block, annotations)
}

private fun parseAnnotations(annotations: Sem1Parser.AnnotationsContext?): List<Annotation> {
    if (annotations == null) {
        return listOf()
    }

    val results = ArrayList<Annotation>()
    var inputs: Sem1Parser.AnnotationsContext = annotations
    while (true) {
        if (inputs.annotation() != null) {
            results.add(parseAnnotation(inputs.annotation()))
        }
        if (inputs.annotations() == null) {
            break
        }
        inputs = inputs.annotations()
    }
    return results
}

fun parseAnnotation(annotation: Sem1Parser.AnnotationContext): Annotation {
    val name = annotation.annotation_name().ID().text
    val value = annotation.LITERAL()?.let(::parseLiteral)

    return Annotation(name, value)
}

private fun scopeBlock(externalVariableIds: List<FunctionId>, ambiguousBlock: AmbiguousBlock): Block {
    val localVariableIds = ArrayList(externalVariableIds)
    val assignments: MutableList<Assignment> = ArrayList()
    for (assignment in ambiguousBlock.assignments) {
        val expression = scopeExpression(localVariableIds, assignment.expression)
        localVariableIds.add(FunctionId.of(assignment.name))
        assignments.add(Assignment(assignment.name, assignment.type, expression))
    }
    val returnedExpression = scopeExpression(localVariableIds, ambiguousBlock.returnedExpression)
    return Block(assignments, returnedExpression)
}

// TODO: Is it inefficient for varIds to be an ArrayList here?
fun scopeExpression(varIds: ArrayList<FunctionId>, expression: AmbiguousExpression): Expression {
    return when (expression) {
        is AmbiguousExpression.Follow -> Expression.Follow(
                scopeExpression(varIds, expression.expression),
                expression.id,
                expression.position)
        is AmbiguousExpression.VarOrNamedFunctionBinding -> {
            if (varIds.contains(expression.functionIdOrVariable)) {
                if (expression.chosenParameters.size > 0) {
                    error("Had explicit parameters in a variable-based function binding")
                }
                // TODO: The position of the variable is incorrect here
                return Expression.ExpressionFunctionBinding(Expression.Variable(expression.functionIdOrVariable.functionName, expression.position),
                        bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                        chosenParameters = expression.chosenParameters,
                        position = expression.position)
            } else {

                return Expression.NamedFunctionBinding(expression.functionIdOrVariable,
                        expression.chosenParameters,
                        bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                        position = expression.position)
            }
        }
        is AmbiguousExpression.ExpressionOrNamedFunctionBinding -> {
            val innerExpression = expression.expression
            if (innerExpression is AmbiguousExpression.Variable) {
                // This is better parsed as a VarOrNamedFunctionBinding, which is easier to deal with.
                error("The parser is not supposed to create this situation")
            }
            return Expression.ExpressionFunctionBinding(
                    functionExpression = scopeExpression(varIds, innerExpression),
                    chosenParameters = expression.chosenParameters,
                    bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                    position = expression.position)
        }
        is AmbiguousExpression.IfThen -> Expression.IfThen(
                scopeExpression(varIds, expression.condition),
                thenBlock = scopeBlock(varIds, expression.thenBlock),
                elseBlock = scopeBlock(varIds, expression.elseBlock),
                position = expression.position
        )
        is AmbiguousExpression.VarOrNamedFunctionCall -> {
            if (varIds.contains(expression.functionIdOrVariable)) {
                if (expression.chosenParameters.size > 0) {
                    error("Had explicit parameters in a variable-based function call")
                }
                return Expression.ExpressionFunctionCall(
                        // TODO: The position of the variable is incorrect here
                        functionExpression = Expression.Variable(expression.functionIdOrVariable.functionName, expression.position),
                        arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                        chosenParameters = expression.chosenParameters,
                        position = expression.position)
            } else {
                return Expression.NamedFunctionCall(
                        functionId = expression.functionIdOrVariable,
                        arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                        chosenParameters = expression.chosenParameters,
                        position = expression.position)
            }
        }
        is AmbiguousExpression.ExpressionOrNamedFunctionCall -> {
            val innerExpression = expression.expression
            if (innerExpression is AmbiguousExpression.Variable) {
                // This is better parsed as a VarOrNamedFunctionCall, which is easier to deal with.
                error("The parser is not supposed to create this situation")
            }
            return Expression.ExpressionFunctionCall(
                    functionExpression = scopeExpression(varIds, innerExpression),
                    arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                    chosenParameters = expression.chosenParameters,
                    position = expression.position)
        }
        is AmbiguousExpression.Literal -> Expression.Literal(expression.type, expression.literal, expression.position)
        is AmbiguousExpression.Variable -> Expression.Variable(expression.name, expression.position)
    }
}


private fun parseStruct(ctx: Sem1Parser.StructContext): Struct {
    val id: FunctionId = parseFunctionId(ctx.function_id())

    val typeParameters: List<String> = if (ctx.cd_ids() != null) {
        parseCommaDelimitedIds(ctx.cd_ids())
    } else {
        listOf()
    }

    val members: List<Member> = parseMembers(ctx.struct_components())

    val annotations = parseAnnotations(ctx.annotations())

    return Struct(id, typeParameters, members, annotations)
}

private fun parseCommaDelimitedIds(cd_ids: Sem1Parser.Cd_idsContext): List<String> {
    val results = ArrayList<String>()
    var inputs = cd_ids
    while (true) {
        if (inputs.ID() != null) {
            results.add(inputs.ID().text)
        }
        if (inputs.cd_ids() == null) {
            break
        }
        inputs = inputs.cd_ids()
    }
    return results
}

private fun parseMembers(members: Sem1Parser.Struct_componentsContext): List<Member> {
    val results = ArrayList<Member>()
    var inputs = members
    while (true) {
        if (inputs.struct_component() != null) {
            results.add(parseMember(inputs.struct_component()))
        }
        if (inputs.struct_components() == null) {
            break
        }
        inputs = inputs.struct_components()
    }
    return results
}

private fun parseMember(member: Sem1Parser.Struct_componentContext): Member {
    val name = member.ID().text
    val type = parseType(member.type())
    return Member(name, type)
}

private fun parseBlock(block: Sem1Parser.BlockContext): AmbiguousBlock {
    val assignments = parseAssignments(block.assignments())
    val returnedExpression = parseExpression(block.return_statement().expression())
    return AmbiguousBlock(assignments, returnedExpression)
}

private fun parseAssignments(assignments: Sem1Parser.AssignmentsContext): List<AmbiguousAssignment> {
    val results = ArrayList<AmbiguousAssignment>()
    var inputs = assignments
    while (true) {
        if (inputs.assignment() != null) {
            results.add(parseAssignment(inputs.assignment()))
        }
        if (inputs.assignments() == null) {
            break
        }
        inputs = inputs.assignments()
    }
    return results
}

private fun parseAssignment(assignment: Sem1Parser.AssignmentContext): AmbiguousAssignment {
    val name = assignment.ID().text
    val type = parseType(assignment.type())
    val expression = parseExpression(assignment.expression())
    return AmbiguousAssignment(name, type, expression)
}

private fun parseExpression(expression: Sem1Parser.ExpressionContext): AmbiguousExpression {
    if (expression.IF() != null) {
        val condition = parseExpression(expression.expression())
        val thenBlock = parseBlock(expression.block(0))
        val elseBlock = parseBlock(expression.block(1))
        return AmbiguousExpression.IfThen(condition, thenBlock, elseBlock, positionOf(expression))
    }

    if (expression.LITERAL() != null) {
        val type = parseTypeGivenParameters(expression.simple_type_id(), listOf())
        val literal = parseLiteral(expression.LITERAL())
        return AmbiguousExpression.Literal(type, literal, positionOf(expression))
    }

    if (expression.ARROW() != null) {
        val inner = parseExpression(expression.expression())
        val name = expression.ID().text
        return AmbiguousExpression.Follow(inner, name, positionOf(expression))
    }

    if (expression.LPAREN() != null) {
        val innerExpression = if (expression.expression() != null) {
            parseExpression(expression.expression())
        } else {
            null
        }
        val functionIdOrVar = if (expression.function_id() != null) {
            parseFunctionId(expression.function_id())
        } else {
            null
        }

        val chosenParameters = if (expression.LESS_THAN() != null) {
            parseCommaDelimitedTypes(expression.cd_types())
        } else {
            listOf()
        }
        if (expression.PIPE() != null) {
            val bindings = parseBindings(expression.cd_expressions_or_underscores())

            if (functionIdOrVar != null) {
                return AmbiguousExpression.VarOrNamedFunctionBinding(functionIdOrVar, chosenParameters, bindings, positionOf(expression))
            } else {
                return AmbiguousExpression.ExpressionOrNamedFunctionBinding(innerExpression!!, chosenParameters, bindings, positionOf(expression))
            }
        }

        val arguments = parseCommaDelimitedExpressions(expression.cd_expressions())
        if (functionIdOrVar != null) {
            return AmbiguousExpression.VarOrNamedFunctionCall(functionIdOrVar, arguments, chosenParameters, positionOf(expression))
        } else {
            return AmbiguousExpression.ExpressionOrNamedFunctionCall(innerExpression!!, arguments, chosenParameters, positionOf(expression))
        }
    }

    if (expression.ID() != null) {
        return AmbiguousExpression.Variable(expression.ID().text, positionOf(expression))
    }
    throw IllegalArgumentException("Couldn't parseFunction $expression")
}

private fun parseLiteral(literalFromParser: TerminalNode) = literalFromParser.text.drop(1).dropLast(1)

fun positionOf(expression: ParserRuleContext): Position {
    return Position(
            expression.start.line,
            expression.start.charPositionInLine,
            expression.start.startIndex,
            expression.stop.stopIndex
    )
}

fun parseBindings(cd_expressions_or_underscores: Sem1Parser.Cd_expressions_or_underscoresContext): List<AmbiguousExpression?> {
    val bindings = ArrayList<AmbiguousExpression?>()
    var inputs = cd_expressions_or_underscores
    while (true) {
        if (inputs.expression() != null) {
            bindings.add(parseExpression(inputs.expression()))
        } else if (inputs.UNDERSCORE() != null) {
            bindings.add(null)
        }
        if (inputs.cd_expressions_or_underscores() == null) {
            break
        }
        inputs = inputs.cd_expressions_or_underscores()
    }
    return bindings
}

private fun parseCommaDelimitedExpressions(cd_expressions: Sem1Parser.Cd_expressionsContext): List<AmbiguousExpression> {
    val expressions = ArrayList<AmbiguousExpression>()
    var inputs = cd_expressions
    while (true) {
        if (inputs.expression() != null) {
            expressions.add(parseExpression(inputs.expression()))
        }
        if (inputs.cd_expressions() == null) {
            break
        }
        inputs = inputs.cd_expressions()
    }
    return expressions
}

private fun parseFunctionArguments(function_arguments: Sem1Parser.Function_argumentsContext): List<Argument> {
    val arguments = ArrayList<Argument>()
    var inputs = function_arguments
    while (true) {
        if (inputs.function_argument() != null) {
            arguments.add(parseFunctionArgument(inputs.function_argument()))
        }
        if (inputs.function_arguments() == null) {
            break
        }
        inputs = inputs.function_arguments()
    }
    return arguments
}

private fun parseFunctionArgument(function_argument: Sem1Parser.Function_argumentContext): Argument {
    val name = function_argument.ID().text
    val type = parseType(function_argument.type())
    return Argument(name, type)
}

private fun parseFunctionId(function_id: Sem1Parser.Function_idContext): FunctionId {
    if (function_id.packag() != null) {
        val packag = parsePackage(function_id.packag())
        return FunctionId(packag, function_id.ID().text)
    } else {
        return FunctionId.of(function_id.ID().text)
    }
}

private fun parsePackage(packag: Sem1Parser.PackagContext): Package {
    val parts = ArrayList<String>()
    var inputs = packag
    while (true) {
        if (inputs.ID() != null) {
            parts.add(inputs.ID().text)
        }
        if (inputs.packag() == null) {
            break
        }
        inputs = inputs.packag()
    }
    return Package(parts)
}

private fun parseType(type: Sem1Parser.TypeContext): Type {
    if (type.ARROW() != null) {
        //Function type
        val argumentTypes = parseCommaDelimitedTypes(type.cd_types())
        val outputType = parseType(type.type())
        return Type.FunctionType(argumentTypes, outputType)
    }

    if (type.LESS_THAN() != null) {
        val parameterTypes = parseCommaDelimitedTypes(type.cd_types())
        return parseTypeGivenParameters(type.simple_type_id(), parameterTypes)
    }

    if (type.simple_type_id() != null) {
        return parseTypeGivenParameters(type.simple_type_id(), listOf())
    }
    throw IllegalArgumentException("Unparsed type " + type)
}

private fun parseCommaDelimitedTypes(cd_types: Sem1Parser.Cd_typesContext): List<Type> {
    val results = ArrayList<Type>()
    var inputs = cd_types
    while (true) {
        if (inputs.type() != null) {
            results.add(parseType(inputs.type()))
        }
        if (inputs.cd_types() == null) {
            break
        }
        inputs = inputs.cd_types()
    }
    return results
}

private fun parseTypeGivenParameters(simple_type_id: Sem1Parser.Simple_type_idContext, parameters: List<Type>): Type {
    if (simple_type_id.packag() != null) {
        return Type.NamedType(FunctionId(parsePackage(simple_type_id.packag()), simple_type_id.ID().text), parameters)
    }

    val typeId = simple_type_id.ID().text
    if (typeId == "Natural") {
        return Type.NATURAL
    } else if (typeId == "Integer") {
        return Type.INTEGER
    } else if (typeId == "Boolean") {
        return Type.BOOLEAN
    } else if (typeId == "List") {
        if (parameters.size != 1) {
            error("List should only accept a single parameter; parameters were: $parameters")
        }
        return Type.List(parameters[0])
    } else if (typeId == "Try") {
        if (parameters.size != 1) {
            error("Try should only accept a single parameter; parameters were: $parameters")
        }
        return Type.Try(parameters[0])
    }

    return Type.NamedType(FunctionId.of(typeId), parameters)
}

private fun parseInterface(interfac: Sem1Parser.InterfacContext): Interface {
    val id = parseFunctionId(interfac.function_id())
    val typeParameters = if (interfac.GREATER_THAN() != null) {
        parseCommaDelimitedIds(interfac.cd_ids())
    } else {
        listOf()
    }
    val methods = parseMethods(interfac.interface_components())

    val annotations = parseAnnotations(interfac.annotations())

    return Interface(id, typeParameters, methods, annotations)
}

private fun parseMethods(methods: Sem1Parser.Interface_componentsContext): List<Method> {
    val results = ArrayList<Method>()
    var inputs = methods
    while (true) {
        if (inputs.interface_component() != null) {
            results.add(parseMethod(inputs.interface_component()))
        }
        if (inputs.interface_components() == null) {
            break
        }
        inputs = inputs.interface_components()
    }
    return results
}

private fun parseMethod(method: Sem1Parser.Interface_componentContext): Method {
    val name = method.ID().text
    val typeParameters = if (method.GREATER_THAN() != null) {
        parseCommaDelimitedIds(method.cd_ids())
    } else {
        listOf()
    }
    val arguments = parseFunctionArguments(method.function_arguments())
    val returnType = parseType(method.type())

    return Method(name, typeParameters, arguments, returnType)
}

private class MyListener : Sem1ParserBaseListener() {
    val structs: MutableList<Struct> = ArrayList()
    val functions: MutableList<Function> = ArrayList()
    val interfaces: MutableList<Interface> = ArrayList()

    override fun enterFunction(ctx: Sem1Parser.FunctionContext?) {
        super.enterFunction(ctx)
        if (ctx != null) {
            functions.add(parseFunction(ctx))
        }
    }

    override fun enterStruct(ctx: Sem1Parser.StructContext?) {
        super.enterStruct(ctx)
        if (ctx != null) {
            structs.add(parseStruct(ctx))
        }
    }

    override fun enterInterfac(ctx: Sem1Parser.InterfacContext?) {
        super.enterInterfac(ctx)
        if (ctx != null) {
            interfaces.add(parseInterface(ctx))
        }
    }
}

fun parseFile(file: File): InterpreterContext {
    return parseFileNamed(file.absolutePath)
}

private data class RawContents(val functions: List<Function>, val structs: List<Struct>, val interfaces: List<Interface>)

fun parseFileNamed(filename: String): InterpreterContext {
    val stream = ANTLRFileStream(filename)
    val rawContents = parseANTLRStreamInner(stream)

    return toInterpreterContext(rawContents)
}

private fun toInterpreterContext(rawContents: RawContents): InterpreterContext {
    return InterpreterContext(indexById(rawContents.functions), indexById(rawContents.structs), indexById(rawContents.interfaces))
}

fun parseString(string: String): InterpreterContext {
    val stream = ANTLRInputStream(string)
    val rawContents = parseANTLRStreamInner(stream)
    return toInterpreterContext(rawContents)
}

private fun parseANTLRStreamInner(stream: ANTLRInputStream): RawContents {
    val errors = ArrayList<String>()

    val lexer = Sem1Lexer(stream)
    val errorListener = object : ANTLRErrorListener {
        override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
            if (msg != null) {
                errors.add(msg)
            } else {
                errors.add("Error with no message at line $line and column $charPositionInLine")
            }
        }

        override fun reportAmbiguity(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, exact: Boolean, ambigAlts: BitSet?, configs: ATNConfigSet?) {
            // Do nothing
        }

        override fun reportContextSensitivity(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, prediction: Int, configs: ATNConfigSet?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun reportAttemptingFullContext(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, conflictingAlts: BitSet?, configs: ATNConfigSet?) {
            // Do nothing
        }

    }
    lexer.addErrorListener(errorListener)
    val tokens = CommonTokenStream(lexer)
    val parser = Sem1Parser(tokens)
    parser.addErrorListener(errorListener)
    val tree: Sem1Parser.FileContext = parser.file()

    val extractor = MyListener()
    ParseTreeWalker.DEFAULT.walk(extractor, tree)

    if (!errors.isEmpty()) {
        error("Found errors: " + errors)
    }

    return RawContents(extractor.functions, extractor.structs, extractor.interfaces)
}

fun parseFileAgainstStandardLibrary(filename: String): InterpreterContext {
    // TODO: This is not going to work consistently
    val directory = File("../semlang-library/src/main/semlang")
    // TODO: Will probably want to accept non-flat directory structures at some point
    val sourceFiles = directory.listFiles() ?: error("It didn't like that directory... " + directory.absolutePath)
    val functions = ArrayList<Function>()
    val structs = ArrayList<Struct>()
    val interfaces = ArrayList<Interface>()
    sourceFiles.forEach { sourceFile ->
        val rawContents = parseANTLRStreamInner(ANTLRFileStream(sourceFile.absolutePath))
        functions.addAll(rawContents.functions)
        structs.addAll(rawContents.structs)
        interfaces.addAll(rawContents.interfaces)
    }

    val ourContents = parseANTLRStreamInner(ANTLRFileStream(filename))
    functions.addAll(ourContents.functions)
    structs.addAll(ourContents.structs)
    interfaces.addAll(ourContents.interfaces)

    return InterpreterContext(indexById(functions), indexById(structs), indexById(interfaces))
}

private fun <T: HasFunctionId> indexById(indexables: List<T>): Map<FunctionId, T> {
    return indexables.associateBy(HasFunctionId::id)
}
