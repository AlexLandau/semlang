package net.semlang.parser

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import sem1.antlr.Sem1Lexer
import sem1.antlr.Sem1Parser
import sem1.antlr.Sem1ParserBaseListener
import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.api.Annotation
import net.semlang.api.parser.*
import java.io.File
import java.util.*

private fun parseLiteral(literalFromParser: TerminalNode): String {
    val innerString = literalFromParser.text.drop(1).dropLast(1)
    val sb = StringBuilder()
    var i = 0
    while (i < innerString.length) {
        val c = innerString[i]
        if (c == '\\') {
            if (i + 1 >= innerString.length) {
                error("Something went wrong with string literal evaluation")
            }
            sb.append(handleSpecialEscapeCodes(innerString[i + 1]))
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun handleSpecialEscapeCodes(c: Char): Char {
    return when (c) {
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        else -> c
    }
}

private fun rangeOf(context: ParserRuleContext): Range {
    return Range(
            startOf(context.start),
            endOf(context.stop)
    )
}

private fun rangeOf(token: Token): Range {
    return Range(
            startOf(token),
            endOf(token)
    )
}
private fun startOf(token: Token): Position {
    return Position(
            token.line,
            token.charPositionInLine,
            token.startIndex
    )
}
private fun endOf(token: Token): Position {
    val tokenLength = token.stopIndex - token.startIndex
    return Position(
            token.line,
            token.charPositionInLine + tokenLength + 1,
            token.stopIndex
    )
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

/**
 * A common pattern in our parsing is defining a list of things as something like:
 *
 * annotations : | annotation annotations ;
 *
 * This is a utility method for converting such a "linked list" pattern to an ArrayList
 * of the parsed equivalent.
 */
private fun <ThingContext, ThingsContext, Thing, AddedArg> parseLinkedList(linkedListRoot: ThingsContext,
                                                                 getHead: (ThingsContext) -> ThingContext?,
                                                                 getRestOf: (ThingsContext) -> ThingsContext?,
                                                                 parseUnit: (ThingContext, AddedArg) -> Thing,
                                                                 additionalArgument: AddedArg): ArrayList<Thing> {
    val results = ArrayList<Thing>()
    var inputs = linkedListRoot
    while (true) {
        val head = getHead(inputs)
        if (head != null) {
            results.add(parseUnit(head, additionalArgument))
        }
        val rest = getRestOf(inputs)
        if (rest == null) {
            break
        }
        inputs = rest
    }
    return results
}

private class ContextListener(val documentId: String) : Sem1ParserBaseListener() {
    val structs: MutableList<UnvalidatedStruct> = ArrayList()
    val functions: MutableList<Function> = ArrayList()
    val unions: MutableList<UnvalidatedUnion> = ArrayList()

    override fun exitFunction(ctx: Sem1Parser.FunctionContext) {
        if (ctx.exception == null) {
            functions.add(parseFunction(ctx))
        }
    }

    override fun exitStruct(ctx: Sem1Parser.StructContext) {
        if (ctx.exception == null) {
            structs.add(parseStruct(ctx))
        }
    }

    override fun exitUnion(ctx: Sem1Parser.UnionContext) {
        if (ctx.exception == null) {
            unions.add(parseUnion(ctx))
        }
    }

    private fun locationOf(context: ParserRuleContext): Location {
        return Location(documentId, rangeOf(context))
    }

    private fun locationOf(token: Token): Location {
        return Location(documentId, rangeOf(token))
    }

    private fun parseFunction(function: Sem1Parser.FunctionContext): Function {
        // TODO: Frequently get "entity_id() must not be null" here when editing code
        val id: EntityId = parseEntityId(function.entity_id())

        val typeParameters: List<TypeParameter> = if (function.cd_type_parameters() != null) {
            parseTypeParameters(function.cd_type_parameters())
        } else {
            listOf()
        }
        if (function.function_arguments() == null) {
            error("function_arguments() is null: " + function.getText()
                    + "\n entity_id: " + function.entity_id()
                    + "\n cd_type_parameters: " + function.cd_type_parameters()
                    + "\n function_arguments: " + function.function_arguments()
                    + "\n type:" + function.type()
                    + "\n block:" + function.block()
            )
        }
        val arguments: List<UnvalidatedArgument> = parseFunctionArguments(function.function_arguments())
        if (function.type() == null) {
            throw LocationAwareParsingException("Functions must specify a return type", locationOf(function))
        }
        val returnType: UnvalidatedType = parseType(function.type())

        val block: Block = parseBlock(function.block(), arguments.map { it.name }.toSet())

        val annotations = parseAnnotations(function.annotations())

        return Function(id, typeParameters, arguments, returnType, block, annotations, locationOf(function.entity_id()), locationOf(function.type()))
    }

    private fun parseStruct(ctx: Sem1Parser.StructContext): UnvalidatedStruct {
        val id: EntityId = parseEntityId(ctx.entity_id())

        val typeParameters: List<TypeParameter> = if (ctx.cd_type_parameters() != null) {
            parseTypeParameters(ctx.cd_type_parameters())
        } else {
            listOf()
        }

        val members: List<UnvalidatedMember> = parseMembers(ctx.members())
        val requires: Block? = ctx.maybe_requires().block()?.let {
            val externalVarNames = members.map { member -> member.name }.toSet()
            parseBlock(it, externalVarNames)
        }

        val annotations = parseAnnotations(ctx.annotations())

        return UnvalidatedStruct(id, typeParameters, members, requires, annotations, locationOf(ctx.entity_id()))
    }

    private fun parseUnion(union: Sem1Parser.UnionContext): UnvalidatedUnion {
        val id = parseEntityId(union.entity_id())
        val typeParameters = if (union.GREATER_THAN() != null) {
            parseTypeParameters(union.cd_type_parameters())
        } else {
            listOf()
        }
        val options = parseOptions(union.disjuncts())

        val annotations = parseAnnotations(union.annotations())

        return UnvalidatedUnion(id, typeParameters, options, annotations, locationOf(union.entity_id()))
    }


    private fun parseAnnotations(annotations: Sem1Parser.AnnotationsContext?): List<Annotation> {
        if (annotations == null) {
            return listOf()
        }

        return parseLinkedList(annotations,
                Sem1Parser.AnnotationsContext::annotation,
                Sem1Parser.AnnotationsContext::annotations,
                this::parseAnnotation)
    }

    private fun parseAnnotation(annotation: Sem1Parser.AnnotationContext): Annotation {
        val name = parseEntityId(annotation.annotation_name().entity_id())
        val args = if (annotation.annotation_contents_list() != null) {
            parseAnnotationArgumentsList(annotation.annotation_contents_list())
        } else {
            listOf()
        }

        return Annotation(name, args)
    }

    private fun parseAnnotationArgumentsList(list: Sem1Parser.Annotation_contents_listContext): List<AnnotationArgument> {
        return parseLinkedList(list,
                Sem1Parser.Annotation_contents_listContext::annotation_item,
                Sem1Parser.Annotation_contents_listContext::annotation_contents_list,
                this::parseAnnotationArgument)
    }

    private fun parseAnnotationArgument(annotationArg: Sem1Parser.Annotation_itemContext): AnnotationArgument {
        if (annotationArg.LBRACKET() != null) {
            val contents = parseAnnotationArgumentsList(annotationArg.annotation_contents_list())
            return AnnotationArgument.List(contents)
        }
        return AnnotationArgument.Literal(parseLiteral(annotationArg.LITERAL()))
    }

    private fun parseTypeParameters(cd_type_parameters: Sem1Parser.Cd_type_parametersContext): List<TypeParameter> {
        return parseLinkedList(cd_type_parameters,
                Sem1Parser.Cd_type_parametersContext::type_parameter,
                Sem1Parser.Cd_type_parametersContext::cd_type_parameters,
                this::parseTypeParameter)
    }

    private fun parseTypeParameter(type_parameter: Sem1Parser.Type_parameterContext): TypeParameter {
        val name = type_parameter.ID().text
        val typeClass = parseTypeClass(type_parameter.type_class())
        return TypeParameter(name, typeClass)
    }

    private fun parseTypeClass(type_class: Sem1Parser.Type_classContext?): TypeClass? {
        if (type_class == null) {
            return null
        }
        if (type_class.ID().text == "Data") {
            return TypeClass.Data
        }
        throw IllegalArgumentException("Couldn't parse type class: ${type_class}")
    }

    private fun parseMembers(members: Sem1Parser.MembersContext): List<UnvalidatedMember> {
        return parseLinkedList(members,
                Sem1Parser.MembersContext::member,
                Sem1Parser.MembersContext::members,
                this::parseMember)
    }

    private fun parseMember(member: Sem1Parser.MemberContext): UnvalidatedMember {
        val name = member.ID().text
        val type = parseType(member.type())
        return UnvalidatedMember(name, type)
    }

    private fun parseBlock(block: Sem1Parser.BlockContext, externalVars: Set<String>): Block {
        val varsInBlockScope = HashSet(externalVars)

        val statements = ArrayList<Statement>()
        val statementContexts = getStatementContexts(block.statements())
        for (statementContext in statementContexts) {
            val statement = parseStatement(statementContext, varsInBlockScope)
            if (statement is Statement.Assignment) {
                varsInBlockScope.add(statement.name)
            }
            statements.add(statement)
        }

        val lastStatement = parseStatement(block.statement(), varsInBlockScope)
        return Block(statements, lastStatement, locationOf(block))
    }

    private fun getStatementContexts(statements: Sem1Parser.StatementsContext): List<Sem1Parser.StatementContext> {
        return parseLinkedList(statements,
                Sem1Parser.StatementsContext::statement,
                Sem1Parser.StatementsContext::statements,
                { it })
    }

    private fun parseStatement(statement: Sem1Parser.StatementContext, varsInScope: Set<String>): Statement {
        if (statement.assignment() != null) {
            val assignment = statement.assignment()
            val name = assignment.ID().text
            val type = if (assignment.type() != null) parseType(assignment.type()) else null
            val expression = parseExpression(assignment.expression(), varsInScope)
            return Statement.Assignment(name, type, expression, locationOf(statement), locationOf(assignment.ID().symbol))
        } else if (statement.RETURN() != null) {
            val expression = parseExpression(statement.expression(), varsInScope)
            return Statement.Return(expression, locationOf(statement))
        } else {
            val expression = parseExpression(statement.expression(), varsInScope)
            return Statement.Bare(expression)
        }
    }

    private fun parseExpression(expression: Sem1Parser.ExpressionContext, varsInScope: Set<String>): Expression {
        try {
            if (expression.IF() != null) {
                val condition = parseExpression(expression.expression(), varsInScope)
                val thenBlock = parseBlock(expression.block(0), varsInScope)
                val elseBlock = parseBlock(expression.block(1), varsInScope)
                return Expression.IfThen(condition, thenBlock, elseBlock, locationOf(expression))
            }

            if (expression.FUNCTION() != null) {
                val arguments = parseFunctionArguments(expression.function_arguments())
                val returnType = parseType(expression.type())
                val block = parseBlock(expression.block(0), varsInScope + arguments.map { it.name })
                return Expression.InlineFunction(arguments, returnType, block, locationOf(expression))
            }

            if (expression.LITERAL() != null) {
                val type = parseTypeGivenParameters(expression.type_ref(), listOf(), locationOf(expression.type_ref()))
                val literal = parseLiteral(expression.LITERAL())
                return Expression.Literal(type, literal, locationOf(expression))
            }

            if (expression.ARROW() != null) {
                val inner = parseExpression(expression.expression(), varsInScope)
                val name = expression.ID().text
                return Expression.Follow(inner, name, locationOf(expression))
            }

            if (expression.LPAREN() != null) {
                val innerExpression = if (expression.expression() != null) {
                    parseExpression(expression.expression(), varsInScope)
                } else {
                    null
                }
                val functionRefOrVar = if (expression.entity_ref() != null) {
                    parseEntityRef(expression.entity_ref())
                } else {
                    null
                }

                if (expression.PIPE() != null) {
                    val chosenParameters = if (expression.LESS_THAN() != null) {
                        parseCommaDelimitedTypesOrUnderscores(expression.cd_types_or_underscores_nonempty())
                    } else {
                        listOf()
                    }
                    val bindings = parseBindings(expression.cd_expressions_or_underscores(), varsInScope)

                    if (functionRefOrVar != null) {
                        // Check if it's a known variable; otherwise, assume it's a function name
                        if (functionRefOrVar.moduleRef == null
                                && functionRefOrVar.id.namespacedName.size == 1
                                && varsInScope.contains(functionRefOrVar.id.namespacedName[0])) {
                            val variable = Expression.Variable(functionRefOrVar.id.namespacedName[0], locationOf(expression.entity_ref()))
                            return Expression.ExpressionFunctionBinding(variable, bindings, chosenParameters, locationOf(expression))
                        } else {
                            return Expression.NamedFunctionBinding(functionRefOrVar, bindings, chosenParameters, locationOf(expression), locationOf(expression.entity_ref()))
                        }
                    } else {
                        // Named functions are expected to be handled by entityRef
                        return Expression.ExpressionFunctionBinding(innerExpression!!, bindings, chosenParameters, locationOf(expression))
                    }
                }

                val chosenParameters = if (expression.LESS_THAN() != null) {
                    parseCommaDelimitedTypes(expression.cd_types_nonempty())
                } else {
                    listOf()
                }
                val arguments = parseCommaDelimitedExpressions(expression.cd_expressions(), varsInScope)
                if (functionRefOrVar != null) {
                    // Check if it's a known variable; otherwise, assume it's a function name
                    if (functionRefOrVar.moduleRef == null
                            && functionRefOrVar.id.namespacedName.size == 1
                            && varsInScope.contains(functionRefOrVar.id.namespacedName[0])) {
                        val variable = Expression.Variable(functionRefOrVar.id.namespacedName[0], locationOf(expression.entity_ref()))
                        return Expression.ExpressionFunctionCall(variable, arguments, chosenParameters, locationOf(expression))
                    } else {
                        return Expression.NamedFunctionCall(functionRefOrVar, arguments, chosenParameters, locationOf(expression), locationOf(expression.entity_ref()))
                    }
                } else {
                    // Named functions are expected to be handled by entityRef
                    return Expression.ExpressionFunctionCall(innerExpression!!, arguments, chosenParameters, locationOf(expression))
                }
            }

            if (expression.LBRACKET() != null) {
                val contents = parseCommaDelimitedExpressions(expression.cd_expressions(), varsInScope)
                val chosenParameter = parseType(expression.type())
                return Expression.ListLiteral(contents, chosenParameter, locationOf(expression))
            }

            if (expression.ID() != null) {
                return Expression.Variable(expression.ID().text, locationOf(expression))
            }

            throw LocationAwareParsingException("Couldn't parse expression '${expression.text}'", locationOf(expression))
        } catch (e: Exception) {
            if (e is LocationAwareParsingException) {
                throw e
            } else {
                throw LocationAwareParsingException("Couldn't parse expression '${expression.text}'", locationOf(expression), e)
            }
        }
    }


    private fun parseBindings(cd_expressions_or_underscores: Sem1Parser.Cd_expressions_or_underscoresContext, varsInScope: Set<String>): List<Expression?> {
        return parseLinkedList(cd_expressions_or_underscores,
                Sem1Parser.Cd_expressions_or_underscoresContext::expression_or_underscore,
                Sem1Parser.Cd_expressions_or_underscoresContext::cd_expressions_or_underscores,
                this::parseBinding,
                varsInScope)
    }

    private fun parseBinding(expression_or_underscore: Sem1Parser.Expression_or_underscoreContext, varsInScope: Set<String>): Expression? {
        if (expression_or_underscore.UNDERSCORE() != null) {
            return null
        } else {
            return parseExpression(expression_or_underscore.expression(), varsInScope)
        }
    }

    private fun parseCommaDelimitedExpressions(cd_expressions: Sem1Parser.Cd_expressionsContext, varsInScope: Set<String>): List<Expression> {
        return parseLinkedList(cd_expressions,
                Sem1Parser.Cd_expressionsContext::expression,
                Sem1Parser.Cd_expressionsContext::cd_expressions,
                this::parseExpression,
                varsInScope)
    }

    private fun parseFunctionArguments(function_arguments: Sem1Parser.Function_argumentsContext): List<UnvalidatedArgument> {
        return parseLinkedList(function_arguments,
                Sem1Parser.Function_argumentsContext::function_argument,
                Sem1Parser.Function_argumentsContext::function_arguments,
                this::parseFunctionArgument)
    }

    private fun parseFunctionArgument(function_argument: Sem1Parser.Function_argumentContext): UnvalidatedArgument {
        val name = function_argument.ID().text
        val type = parseType(function_argument.type())
        return UnvalidatedArgument(name, type, locationOf(function_argument))
    }

    private fun parseTypeRef(type_ref: Sem1Parser.Type_refContext): EntityRef {
        val module_ref = type_ref.module_ref()
        val moduleRef = if (module_ref == null) {
            null
        } else {
            if (module_ref.childCount == 1) {
                ModuleRef(null, module_ref.module_id(0).text, null)
            } else if (module_ref.childCount == 3) {
                ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, null)
            } else if (module_ref.childCount == 5) {
                val version = module_ref.LITERAL().text.drop(1).dropLast(1)
                ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, version)
            } else {
                error("module_ref was $module_ref, childCount was ${module_ref.childCount}")
            }
        }

        return EntityRef(moduleRef, parseEntityId(type_ref.entity_id()))
    }

    private fun parseEntityRef(entity_ref: Sem1Parser.Entity_refContext): EntityRef {
        val module_ref = entity_ref.module_ref()
        val moduleRef = if (module_ref == null) {
            null
        } else {
            if (module_ref.childCount == 1) {
                ModuleRef(null, module_ref.module_id(0).text, null)
            } else if (module_ref.childCount == 3) {
                ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, null)
            } else if (module_ref.childCount == 5) {
                val version = module_ref.LITERAL().text.drop(1).dropLast(1)
                ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, version)
            } else {
                error("module_ref was $module_ref, childCount was ${module_ref.childCount}")
            }
        }

        return EntityRef(moduleRef, parseEntityId(entity_ref.entity_id()))
    }

    private fun parseEntityId(entity_id: Sem1Parser.Entity_idContext): EntityId {
        val namespacedName = if (entity_id.namespace() != null) {
            parseNamespace(entity_id.namespace()) + entity_id.ID().text
        } else {
            listOf(entity_id.ID().text)
        }

        return EntityId(namespacedName)
    }

    private fun parseNamespace(namespace: Sem1Parser.NamespaceContext): List<String> {
        return parseLinkedList(namespace,
                Sem1Parser.NamespaceContext::ID,
                Sem1Parser.NamespaceContext::namespace,
                TerminalNode::getText)
    }

    private fun parseTypeOrUnderscore(typeOrUnderscore: Sem1Parser.Type_or_underscoreContext): UnvalidatedType? {
        if (typeOrUnderscore.type() != null) {
            return parseType(typeOrUnderscore.type())
        } else {
            if (typeOrUnderscore.UNDERSCORE() == null) error("Unexpected case")
            return null
        }
    }

    private fun parseType(type: Sem1Parser.TypeContext): UnvalidatedType {
        if (type.ARROW() != null) {
            //Function type
            val isReference = type.AMPERSAND() != null
            val typeParameters = if (type.cd_type_parameters() != null) {
                parseTypeParameters(type.cd_type_parameters())
            } else {
                listOf()
            }
            val argumentTypes = parseCommaDelimitedTypes(type.cd_types())
            val outputType = parseType(type.type())
            return UnvalidatedType.FunctionType(isReference, typeParameters, argumentTypes, outputType, locationOf(type))
        }

        if (type.LESS_THAN() != null) {
            val parameterTypes = parseCommaDelimitedTypes(type.cd_types())
            return parseTypeGivenParameters(type.type_ref(), parameterTypes, locationOf(type))
        }

        if (type.type_ref() != null) {
            return parseTypeGivenParameters(type.type_ref(), listOf(), locationOf(type))
        }
        throw IllegalArgumentException("Unparsed type " + type.text)
    }

    private fun parseCommaDelimitedTypes(cd_types: Sem1Parser.Cd_typesContext): List<UnvalidatedType> {
        return parseLinkedList(cd_types,
                Sem1Parser.Cd_typesContext::type,
                Sem1Parser.Cd_typesContext::cd_types,
                this::parseType)
    }

    private fun parseCommaDelimitedTypes(cd_types: Sem1Parser.Cd_types_nonemptyContext): List<UnvalidatedType> {
        return parseLinkedList(cd_types,
                Sem1Parser.Cd_types_nonemptyContext::type,
                Sem1Parser.Cd_types_nonemptyContext::cd_types_nonempty,
                this::parseType)
    }

    private fun parseCommaDelimitedTypesOrUnderscores(cd_types: Sem1Parser.Cd_types_or_underscores_nonemptyContext): List<UnvalidatedType?> {
        return parseLinkedList(cd_types,
                Sem1Parser.Cd_types_or_underscores_nonemptyContext::type_or_underscore,
                Sem1Parser.Cd_types_or_underscores_nonemptyContext::cd_types_or_underscores_nonempty,
                this::parseTypeOrUnderscore)
    }

    private fun parseTypeGivenParameters(type_ref: Sem1Parser.Type_refContext, parameters: List<UnvalidatedType>, typeLocation: Location): UnvalidatedType {
        val isReference = type_ref.AMPERSAND() != null
        if (type_ref.module_ref() != null || type_ref.entity_id().namespace() != null) {
            return UnvalidatedType.NamedType(parseTypeRef(type_ref), isReference, parameters, typeLocation)
        }

        val typeId = type_ref.entity_id().ID().text
        return UnvalidatedType.NamedType(EntityRef.of(typeId), isReference, parameters, typeLocation)
    }

    private fun parseOptions(options: Sem1Parser.DisjunctsContext): List<UnvalidatedOption> {
        return parseLinkedList(options,
                Sem1Parser.DisjunctsContext::disjunct,
                Sem1Parser.DisjunctsContext::disjuncts,
                this::parseOption)
    }

    private fun parseOption(option: Sem1Parser.DisjunctContext): UnvalidatedOption {
        val name = option.ID().text
        val type: UnvalidatedType? = option.type()?.let { parseType(it) }

        return UnvalidatedOption(name, type, locationOf(option.ID().symbol))
    }
}

sealed class ParsingResult {
    abstract fun assumeSuccess(): RawContext
    data class Success(val context: RawContext): ParsingResult() {
        override fun assumeSuccess(): RawContext {
            return context
        }
    }
    data class Failure(val errors: List<Issue>, val partialContext: RawContext): ParsingResult() {
        init {
            if (errors.isEmpty()) {
                error("We are reporting a parsing failure, but no errors were recorded")
            }
        }
        override fun assumeSuccess(): RawContext {
            error("Parsing was not successful. Errors: $errors")
        }
    }
}

fun combineParsingResults(results: Collection<ParsingResult>): ParsingResult {
    val allFunctions = ArrayList<Function>()
    val allStructs = ArrayList<UnvalidatedStruct>()
    val allUnions = ArrayList<UnvalidatedUnion>()
    val allErrors = ArrayList<Issue>()
    for (parsingResult in results) {
        if (parsingResult is ParsingResult.Success) {
            val rawContext = parsingResult.context
            allFunctions.addAll(rawContext.functions)
            allStructs.addAll(rawContext.structs)
            allUnions.addAll(rawContext.unions)
        } else if (parsingResult is ParsingResult.Failure) {
            allErrors.addAll(parsingResult.errors)
            val context = parsingResult.partialContext
            allFunctions.addAll(context.functions)
            allStructs.addAll(context.structs)
            allUnions.addAll(context.unions)
        }
    }
    val combinedContext = RawContext(allFunctions, allStructs, allUnions)
    if (allErrors.isEmpty()) {
        return ParsingResult.Success(combinedContext)
    } else {
        return ParsingResult.Failure(allErrors, combinedContext)
    }
}

fun parseFile(file: File): ParsingResult {
    return parseFileNamed(file.absolutePath)
}

fun parseFiles(files: Collection<File>): ParsingResult {
    val allResults = ArrayList<ParsingResult>()
    // TODO: This could be parallelized
    for (file in files) {
        allResults.add(parseFileNamed(file.absolutePath))
    }
    return combineParsingResults(allResults)
}

fun parseFileNamed(filename: String): ParsingResult {
    val stream = ANTLRFileStream(filename, "UTF-8")
    return parseANTLRStreamInner(stream, filename)
}

fun parseString(text: String, documentUri: String): ParsingResult {
    val stream = ANTLRInputStream(text)
    return parseANTLRStreamInner(stream, documentUri)
}

private class ErrorListener(val documentId: String): ANTLRErrorListener {
    val errorsFound: ArrayList<Issue> = ArrayList<Issue>()
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
        val message = msg ?: "Error with no message"
        val location = if (offendingSymbol is Token) {
            Location(documentId, rangeOf(offendingSymbol))
        } else {
            val range = Range(Position(line, charPositionInLine, 0),
                    Position(line, charPositionInLine + 1, 1))
            Location(documentId, range)
        }
        errorsFound.add(Issue(message, location, IssueLevel.ERROR))
    }

    override fun reportAmbiguity(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, exact: Boolean, ambigAlts: BitSet?, configs: ATNConfigSet?) {
        if (exact) {
            throw RuntimeException("Exact ambiguity found")
        }
        // Do nothing
    }

    override fun reportContextSensitivity(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, prediction: Int, configs: ATNConfigSet?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun reportAttemptingFullContext(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, conflictingAlts: BitSet?, configs: ATNConfigSet?) {
        // Do nothing
    }
}

class LocationAwareParsingException(message: String, val location: Location, cause: Exception? = null): Exception(message, cause)

private fun parseANTLRStreamInner(stream: ANTLRInputStream, documentId: String): ParsingResult {
    val lexer = Sem1Lexer(stream)
    val errorListener = ErrorListener(documentId)
    lexer.addErrorListener(errorListener)
    val tokens = CommonTokenStream(lexer)
    val parser = Sem1Parser(tokens)
    parser.addErrorListener(errorListener)
    val tree: Sem1Parser.FileContext = parser.file()

    val extractor = ContextListener(documentId)
    try {
        ParseTreeWalker.DEFAULT.walk(extractor, tree)
    } catch(e: LocationAwareParsingException) {
        val partialContext = RawContext(extractor.functions, extractor.structs, extractor.unions)
        return ParsingResult.Failure(errorListener.errorsFound + listOf(Issue(e.message.orEmpty() + if (e.cause?.message != null) {": " + e.cause.message.orEmpty()} else "", e.location, IssueLevel.ERROR)), partialContext)
    }

    val context = RawContext(extractor.functions, extractor.structs, extractor.unions)
    if (!errorListener.errorsFound.isEmpty()) {
        return ParsingResult.Failure(errorListener.errorsFound, context)
    }

    return ParsingResult.Success(context)
}
