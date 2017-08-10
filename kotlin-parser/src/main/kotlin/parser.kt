package semlang.parser

import indexById
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

    return parseLinkedList(annotations,
            Sem1Parser.AnnotationsContext::annotation,
            Sem1Parser.AnnotationsContext::annotations,
            ::parseAnnotation)
}

private fun parseAnnotation(annotation: Sem1Parser.AnnotationContext): Annotation {
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
private fun scopeExpression(varIds: ArrayList<FunctionId>, expression: AmbiguousExpression): Expression {
    return when (expression) {
        is AmbiguousExpression.Follow -> Expression.Follow(
                scopeExpression(varIds, expression.expression),
                expression.name,
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


private fun parseStruct(ctx: Sem1Parser.StructContext): UnvalidatedStruct {
    val id: FunctionId = parseFunctionId(ctx.function_id())

    val typeParameters: List<String> = if (ctx.cd_ids() != null) {
        parseCommaDelimitedIds(ctx.cd_ids())
    } else {
        listOf()
    }

    val members: List<Member> = parseMembers(ctx.struct_members())
    val requires: Block? = ctx.maybe_requires().block()?.let {
        val externalVarIds = members.map { member -> FunctionId.of(member.name) }
        scopeBlock(externalVarIds, parseBlock(it))
    }

    val annotations = parseAnnotations(ctx.annotations())

    return UnvalidatedStruct(id, typeParameters, members, requires, annotations)
}

private fun parseCommaDelimitedIds(cd_ids: Sem1Parser.Cd_idsContext): List<String> {
    return parseLinkedList(cd_ids,
            Sem1Parser.Cd_idsContext::ID,
            Sem1Parser.Cd_idsContext::cd_ids,
            TerminalNode::getText)
}

private fun parseMembers(members: Sem1Parser.Struct_membersContext): List<Member> {
    return parseLinkedList(members,
            Sem1Parser.Struct_membersContext::struct_member,
            Sem1Parser.Struct_membersContext::struct_members,
            ::parseMember)
}

private fun parseMember(member: Sem1Parser.Struct_memberContext): Member {
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
    return parseLinkedList(assignments,
            Sem1Parser.AssignmentsContext::assignment,
            Sem1Parser.AssignmentsContext::assignments,
            ::parseAssignment)
}

private fun parseAssignment(assignment: Sem1Parser.AssignmentContext): AmbiguousAssignment {
    val name = assignment.ID().text
    val type = if (assignment.type() != null) parseType(assignment.type()) else null
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

private fun positionOf(expression: ParserRuleContext): Position {
    return Position(
            expression.start.line,
            expression.start.charPositionInLine,
            expression.start.startIndex,
            expression.stop.stopIndex
    )
}

private fun parseBindings(cd_expressions_or_underscores: Sem1Parser.Cd_expressions_or_underscoresContext): List<AmbiguousExpression?> {
    return parseLinkedList(cd_expressions_or_underscores,
            Sem1Parser.Cd_expressions_or_underscoresContext::expression_or_underscore,
            Sem1Parser.Cd_expressions_or_underscoresContext::cd_expressions_or_underscores,
            ::parseBinding)
}

private fun parseBinding(expression_or_underscore: Sem1Parser.Expression_or_underscoreContext): AmbiguousExpression? {
    if (expression_or_underscore.UNDERSCORE() != null) {
        return null
    } else {
        return parseExpression(expression_or_underscore.expression())
    }
}

private fun parseCommaDelimitedExpressions(cd_expressions: Sem1Parser.Cd_expressionsContext): List<AmbiguousExpression> {
    return parseLinkedList(cd_expressions,
            Sem1Parser.Cd_expressionsContext::expression,
            Sem1Parser.Cd_expressionsContext::cd_expressions,
            ::parseExpression)
}

private fun parseFunctionArguments(function_arguments: Sem1Parser.Function_argumentsContext): List<Argument> {
    return parseLinkedList(function_arguments,
            Sem1Parser.Function_argumentsContext::function_argument,
            Sem1Parser.Function_argumentsContext::function_arguments,
            ::parseFunctionArgument)
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
    val parts = parseLinkedList(packag,
            Sem1Parser.PackagContext::ID,
            Sem1Parser.PackagContext::packag,
            TerminalNode::getText)
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
    return parseLinkedList(cd_types,
            Sem1Parser.Cd_typesContext::type,
            Sem1Parser.Cd_typesContext::cd_types,
            ::parseType)
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
    val methods = parseMethods(interfac.methods())

    val annotations = parseAnnotations(interfac.annotations())

    return Interface(id, typeParameters, methods, annotations)
}

private fun parseMethods(methods: Sem1Parser.MethodsContext): List<Method> {
    return parseLinkedList(methods,
            Sem1Parser.MethodsContext::method,
            Sem1Parser.MethodsContext::methods,
            ::parseMethod)
}

private fun parseMethod(method: Sem1Parser.MethodContext): Method {
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

/**
 * A common pattern in our parsing is defining a list of things as something like:
 *
 * annotations : | annotation annotations ;
 *
 * This is a utility method for converting such a "linked list" pattern to an ArrayList
 * of the parsed equivalent.
 */
private fun <ThingContext, ThingsContext, Thing> parseLinkedList(linkedListRoot: ThingsContext,
                                                                 getHead: (ThingsContext) -> ThingContext?,
                                                                 getRestOf: (ThingsContext) -> ThingsContext?,
                                                                 parseUnit: (ThingContext) -> Thing): ArrayList<Thing> {
    val results = ArrayList<Thing>()
    var inputs = linkedListRoot
    while (true) {
        val head = getHead(inputs)
        if (head != null) {
            results.add(parseUnit(head))
        }
        val rest = getRestOf(inputs)
        if (rest == null) {
            break
        }
        inputs = rest
    }
    return results
}

private class ContextListener : Sem1ParserBaseListener() {
    val structs: MutableList<UnvalidatedStruct> = ArrayList()
    val functions: MutableList<Function> = ArrayList()
    val interfaces: MutableList<Interface> = ArrayList()

    override fun exitFunction(ctx: Sem1Parser.FunctionContext) {
        functions.add(parseFunction(ctx))
    }

    override fun exitStruct(ctx: Sem1Parser.StructContext) {
        structs.add(parseStruct(ctx))
    }

    override fun exitInterfac(ctx: Sem1Parser.InterfacContext) {
        interfaces.add(parseInterface(ctx))
    }
}

fun parseFile(file: File): RawContext {
    return parseFileNamed(file.absolutePath)
}

//private data class RawContents(val functions: List<Function>, val structs: List<UnvalidatedStruct>, val interfaces: List<Interface>)

fun parseFileNamed(filename: String): RawContext {
    val stream = ANTLRFileStream(filename, "UTF-8")
    val rawContents = parseANTLRStreamInner(stream)

    return rawContents
}

fun parseString(string: String): RawContext {
    val stream = ANTLRInputStream(string)
    val rawContents = parseANTLRStreamInner(stream)
    return rawContents
}

private class ErrorListener(val errorsFound: ArrayList<String> = ArrayList<String>()): ANTLRErrorListener {
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
        if (msg != null) {
            errorsFound.add(msg)
        } else {
            errorsFound.add("Error with no message at line $line and column $charPositionInLine")
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

private fun parseANTLRStreamInner(stream: ANTLRInputStream): RawContext {
    val lexer = Sem1Lexer(stream)
    val errorListener = ErrorListener()
    lexer.addErrorListener(errorListener)
    val tokens = CommonTokenStream(lexer)
    val parser = Sem1Parser(tokens)
    parser.addErrorListener(errorListener)
    val tree: Sem1Parser.FileContext = parser.file()

    val extractor = ContextListener()
    ParseTreeWalker.DEFAULT.walk(extractor, tree)

    if (!errorListener.errorsFound.isEmpty()) {
        error("Found errors: " + errorListener.errorsFound)
    }

    return RawContext(extractor.functions, extractor.structs, extractor.interfaces)
}

fun parseFileAgainstStandardLibrary(filename: String): RawContext {
    // TODO: This is not going to work consistently
    val directory = File("../semlang-library/src/main/semlang")
    // TODO: Will probably want to accept non-flat directory structures at some point
    val sourceFiles = directory.listFiles() ?: error("It didn't like that directory... " + directory.absolutePath)
    val functions = ArrayList<Function>()
    val structs = ArrayList<UnvalidatedStruct>()
    val interfaces = ArrayList<Interface>()
    sourceFiles.forEach { sourceFile ->
        val rawContents = parseANTLRStreamInner(ANTLRFileStream(sourceFile.absolutePath, "UTF-8"))
        functions.addAll(rawContents.functions)
        structs.addAll(rawContents.structs)
        interfaces.addAll(rawContents.interfaces)
    }

    val ourContents = parseANTLRStreamInner(ANTLRFileStream(filename, "UTF-8"))
    functions.addAll(ourContents.functions)
    structs.addAll(ourContents.structs)
    interfaces.addAll(ourContents.interfaces)

    return RawContext(functions, structs, interfaces)
}
