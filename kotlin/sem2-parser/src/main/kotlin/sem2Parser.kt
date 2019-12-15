package net.semlang.sem2.parser

import net.semlang.api.parser.*
import net.semlang.sem2.api.*
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import sem2.antlr.Sem2Lexer
import sem2.antlr.Sem2Parser
import sem2.antlr.Sem2ParserBaseListener
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

private class ContextListener(val documentId: String) : Sem2ParserBaseListener() {
    val structs: MutableList<S2Struct> = ArrayList()
    val functions: MutableList<S2Function> = ArrayList()
    val unions: MutableList<S2Union> = ArrayList()

    override fun exitFunction(ctx: Sem2Parser.FunctionContext) {
        if (ctx.exception == null) {
            functions.add(parseFunction(ctx))
        }
    }

    override fun exitStruct(ctx: Sem2Parser.StructContext) {
        if (ctx.exception == null) {
            structs.add(parseStruct(ctx))
        }
    }

    override fun exitUnion(ctx: Sem2Parser.UnionContext) {
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

    private fun parseFunction(function: Sem2Parser.FunctionContext): S2Function {
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
        val arguments: List<S2Argument> = parseFunctionArguments(function.function_arguments())
        if (function.type() == null) {
            throw LocationAwareParsingException("Functions must specify a return type", locationOf(function))
        }
        val returnType: S2Type = parseType(function.type())

        val block: S2Block = parseBlock(function.block())

        val annotations = parseAnnotations(function.annotations())

        return S2Function(id, typeParameters, arguments, returnType, block, annotations, locationOf(function.entity_id()), locationOf(function.type()))
    }

    private fun parseStruct(ctx: Sem2Parser.StructContext): S2Struct {
        val id: EntityId = parseEntityId(ctx.entity_id())

        val typeParameters: List<TypeParameter> = if (ctx.cd_type_parameters() != null) {
            parseTypeParameters(ctx.cd_type_parameters())
        } else {
            listOf()
        }

        val members: List<S2Member> = parseMembers(ctx.members())
        val requires: S2Block? = ctx.maybe_requires().block()?.let {
            parseBlock(it)
        }

        val annotations = parseAnnotations(ctx.annotations())

        return S2Struct(id, typeParameters, members, requires, annotations, locationOf(ctx.entity_id()))
    }

    private fun parseUnion(union: Sem2Parser.UnionContext): S2Union {
        val id = parseEntityId(union.entity_id())
        val typeParameters = if (union.GREATER_THAN() != null) {
            parseTypeParameters(union.cd_type_parameters())
        } else {
            listOf()
        }
        val options = parseOptions(union.disjuncts())

        val annotations = parseAnnotations(union.annotations())

        return S2Union(id, typeParameters, options, annotations, locationOf(union.entity_id()))
    }


    private fun parseAnnotations(annotations: Sem2Parser.AnnotationsContext?): List<S2Annotation> {
        if (annotations == null) {
            return listOf()
        }

        return parseLinkedList(annotations,
                Sem2Parser.AnnotationsContext::annotation,
                Sem2Parser.AnnotationsContext::annotations,
                this::parseAnnotation)
    }

    private fun parseAnnotation(annotation: Sem2Parser.AnnotationContext): S2Annotation {
        val name = parseEntityId(annotation.annotation_name().entity_id())
        val args = if (annotation.annotation_contents_list() != null) {
            parseAnnotationArgumentsList(annotation.annotation_contents_list())
        } else {
            listOf()
        }

        return S2Annotation(name, args)
    }

    private fun parseAnnotationArgumentsList(list: Sem2Parser.Annotation_contents_listContext): List<S2AnnotationArgument> {
        return parseLinkedList(list,
                Sem2Parser.Annotation_contents_listContext::annotation_item,
                Sem2Parser.Annotation_contents_listContext::annotation_contents_list,
                this::parseAnnotationArgument)
    }

    private fun parseAnnotationArgument(annotationArg: Sem2Parser.Annotation_itemContext): S2AnnotationArgument {
        if (annotationArg.LBRACKET() != null) {
            val contents = parseAnnotationArgumentsList(annotationArg.annotation_contents_list())
            return S2AnnotationArgument.List(contents)
        }
        return S2AnnotationArgument.Literal(parseLiteral(annotationArg.LITERAL()))
    }

    private fun parseTypeParameters(cd_type_parameters: Sem2Parser.Cd_type_parametersContext): List<TypeParameter> {
        return parseLinkedList(cd_type_parameters,
                Sem2Parser.Cd_type_parametersContext::type_parameter,
                Sem2Parser.Cd_type_parametersContext::cd_type_parameters,
                this::parseTypeParameter)
    }

    private fun parseTypeParameter(type_parameter: Sem2Parser.Type_parameterContext): TypeParameter {
        val name = type_parameter.ID().text
        val typeClass = parseTypeClass(type_parameter.type_class())
        return TypeParameter(name, typeClass)
    }

    private fun parseTypeClass(type_class: Sem2Parser.Type_classContext?): TypeClass? {
        if (type_class == null) {
            return null
        }
        if (type_class.ID().text == "Data") {
            return TypeClass.Data
        }
        throw IllegalArgumentException("Couldn't parse type class: ${type_class}")
    }

    private fun parseMembers(members: Sem2Parser.MembersContext): List<S2Member> {
        return parseLinkedList(members,
                Sem2Parser.MembersContext::member,
                Sem2Parser.MembersContext::members,
                this::parseMember)
    }

    private fun parseMember(member: Sem2Parser.MemberContext): S2Member {
        val name = member.ID().text
        val type = parseType(member.type())
        return S2Member(name, type)
    }

    private fun parseBlock(block: Sem2Parser.BlockContext): S2Block {
        val statements = parseStatements(block.statements())
        val lastStatement = parseStatement(block.statement())
        return S2Block(statements, lastStatement, locationOf(block))
    }

    private fun parseStatements(statements: Sem2Parser.StatementsContext): List<S2Statement> {
        return parseLinkedList(statements,
                Sem2Parser.StatementsContext::statement,
                Sem2Parser.StatementsContext::statements,
                this::parseStatement)
    }

    private fun parseStatement(statement: Sem2Parser.StatementContext): S2Statement {
        if (statement.WHILE() != null) {
            val conditionExpression = parseExpression(statement.expression())
            val actionBlock = parseBlock(statement.block())
            return S2Statement.WhileLoop(conditionExpression, actionBlock, locationOf(statement))
        } else if (statement.RETURN() != null) {
            val expression = parseExpression(statement.expression())
            return S2Statement.Return(expression, locationOf(statement))
        } else if (statement.assignment() != null) {
            val assignment = statement.assignment()
            val name = assignment.ID().text
            val type = if (assignment.type() != null) parseType(assignment.type()) else null
            val expression = parseExpression(assignment.expression())
            return S2Statement.Assignment(name, type, expression, locationOf(statement), locationOf(assignment.ID().symbol))
        } else {
            val expression = parseExpression(statement.expression())
            return S2Statement.Bare(expression, locationOf(statement))
        }
    }

    private fun parseExpression(expression: Sem2Parser.ExpressionContext): S2Expression {
        try {
            if (expression.IF() != null) {
                val condition = parseExpression(expression.expression(0))
                val thenBlock = parseBlock(expression.block(0))
                val elseBlock = parseBlock(expression.block(1))
                return S2Expression.IfThen(condition, thenBlock, elseBlock, locationOf(expression))
            }

            if (expression.FUNCTION() != null) {
                val arguments = parseFunctionArguments(expression.function_arguments())
                val returnType = expression.type()?.let { parseType(it) }
                val block = parseBlock(expression.block(0))
                return S2Expression.InlineFunction(arguments, returnType, block, locationOf(expression))
            }

            if (expression.LITERAL() != null) {
                if (expression.DOT() != null) {
                    // sem1-style literal with explicit type
                    val type = parseTypeGivenParameters(expression.type_ref(), listOf(), locationOf(expression.type_ref()))
                    val literal = parseLiteral(expression.LITERAL())
                    return S2Expression.Literal(type, literal, locationOf(expression))
                } else {
                    // short String literal
                    val literal = parseLiteral(expression.LITERAL())
                    return S2Expression.Literal(S2Type.NamedType(EntityRef.of("String"), false), literal, locationOf(expression))
                }
            }

            if (expression.INTEGER_LITERAL() != null) {
                return S2Expression.IntegerLiteral(expression.INTEGER_LITERAL().text, locationOf(expression))
            }

            if (expression.LBRACE() != null) {
                val args = parseLambdaOptionalArguments(expression.optional_args())
                val statements = parseStatements(expression.statements())
                val lastStatement = parseStatement(expression.statement())
                val block = S2Block(statements, lastStatement, locationOf(expression))
                return S2Expression.InlineFunction(args, null, block, locationOf(expression))
            }

            if (expression.ARROW() != null) {
                // Follow expression
                val inner = parseExpression(expression.expression(0))
                val name = expression.ID().text
                return S2Expression.Follow(inner, name, locationOf(expression))
            }

            if (expression.LPAREN() != null) {
                val innerExpression = if (expression.expression() != null) {
                    parseExpression(expression.expression(0))
                } else {
                    null
                }

                if (expression.PIPE() != null) {
                    val chosenParameters = if (expression.LESS_THAN() != null) {
                        parseCommaDelimitedTypesOrUnderscores(expression.cd_types_or_underscores_nonempty())
                    } else {
                        listOf()
                    }
                    val bindings = parseBindings(expression.cd_expressions_or_underscores())

                    return S2Expression.FunctionBinding(innerExpression!!, bindings, chosenParameters, locationOf(expression))
                }

                if (expression.cd_expressions() != null) {
                    val chosenParameters = if (expression.LESS_THAN() != null) {
                        parseCommaDelimitedTypes(expression.cd_types_nonempty())
                    } else {
                        listOf()
                    }
                    val arguments = parseCommaDelimitedExpressions(expression.cd_expressions())
                    return S2Expression.FunctionCall(innerExpression!!, arguments, chosenParameters, locationOf(expression))
                }
            }

            if (expression.LBRACKET() != null) {
                if (expression.expression().isNotEmpty()) {
                    // Get operator
                    val subject = parseExpression(expression.expression(0))
                    val getArgs = parseCommaDelimitedExpressions(expression.cd_expressions())
                    return S2Expression.GetOp(subject, getArgs, locationOf(expression), locationOf(expression.LBRACKET().symbol))
                }

                // List literal
                val contents = parseCommaDelimitedExpressions(expression.cd_expressions())
                val chosenParameter = parseType(expression.type())
                return S2Expression.ListLiteral(contents, chosenParameter, locationOf(expression))
            }

            if (expression.DOT() != null) {
                val subexpression = parseExpression(expression.expression(0))
                val name = expression.ID().text

                return S2Expression.DotAccess(subexpression, name, locationOf(expression), locationOf(expression.ID().symbol))
            }

            if (expression.PLUS() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.PlusOp(left, right, locationOf(expression), locationOf(expression.PLUS().symbol))
            }

            if (expression.HYPHEN() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.MinusOp(left, right, locationOf(expression), locationOf(expression.HYPHEN().symbol))
            }

            if (expression.TIMES() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.TimesOp(left, right, locationOf(expression), locationOf(expression.TIMES().symbol))
            }

            if (expression.EQUALS() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.EqualsOp(left, right, locationOf(expression), locationOf(expression.EQUALS().symbol))
            }

            if (expression.NOT_EQUALS() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.NotEqualsOp(left, right, locationOf(expression), locationOf(expression.NOT_EQUALS().symbol))
            }

            if (expression.LESS_THAN() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.LessThanOp(left, right, locationOf(expression), locationOf(expression.LESS_THAN().symbol))
            }

            if (expression.GREATER_THAN() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.GreaterThanOp(left, right, locationOf(expression), locationOf(expression.GREATER_THAN().symbol))
            }

            if (expression.DOT_ASSIGN() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.DotAssignOp(left, right, locationOf(expression), locationOf(expression.DOT_ASSIGN().symbol))
            }

            if (expression.AND() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.AndOp(left, right, locationOf(expression), locationOf(expression.AND().symbol))
            }

            if (expression.OR() != null) {
                val left = parseExpression(expression.expression(0))
                val right = parseExpression(expression.expression(1))
                return S2Expression.OrOp(left, right, locationOf(expression), locationOf(expression.OR().symbol))
            }

            if (expression.ID() != null) {
                return S2Expression.RawId(expression.text, locationOf(expression))
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

    private fun parseLambdaOptionalArguments(optional_args: Sem2Parser.Optional_argsContext): List<S2Argument> {
        val nonemptyFunctionArgs = optional_args.function_arguments_nonempty()
        if (nonemptyFunctionArgs != null) {
            return parseNonemptyFunctionArgs(nonemptyFunctionArgs)
        } else {
            return listOf()
        }
    }

    private fun parseNonemptyFunctionArgs(nonemptyFunctionArgs: Sem2Parser.Function_arguments_nonemptyContext): List<S2Argument> {
        return parseLinkedList(nonemptyFunctionArgs,
                Sem2Parser.Function_arguments_nonemptyContext::function_argument,
                Sem2Parser.Function_arguments_nonemptyContext::function_arguments_nonempty,
                this::parseFunctionArgument)
    }

    private fun parseBindings(cd_expressions_or_underscores: Sem2Parser.Cd_expressions_or_underscoresContext): List<S2Expression?> {
        return parseLinkedList(cd_expressions_or_underscores,
                Sem2Parser.Cd_expressions_or_underscoresContext::expression_or_underscore,
                Sem2Parser.Cd_expressions_or_underscoresContext::cd_expressions_or_underscores,
                this::parseBinding)
    }

    private fun parseBinding(expression_or_underscore: Sem2Parser.Expression_or_underscoreContext): S2Expression? {
        if (expression_or_underscore.UNDERSCORE() != null) {
            return null
        } else {
            return parseExpression(expression_or_underscore.expression())
        }
    }

    private fun parseCommaDelimitedExpressions(cd_expressions: Sem2Parser.Cd_expressionsContext): List<S2Expression> {
        return parseLinkedList(cd_expressions,
                Sem2Parser.Cd_expressionsContext::expression,
                Sem2Parser.Cd_expressionsContext::cd_expressions,
                this::parseExpression)
    }

    private fun parseFunctionArguments(function_arguments: Sem2Parser.Function_argumentsContext): List<S2Argument> {
        return parseLinkedList(function_arguments,
                Sem2Parser.Function_argumentsContext::function_argument,
                Sem2Parser.Function_argumentsContext::function_arguments,
                this::parseFunctionArgument)
    }

    private fun parseFunctionArgument(function_argument: Sem2Parser.Function_argumentContext): S2Argument {
        val name = function_argument.ID().text
        val type = parseType(function_argument.type())
        return S2Argument(name, type, locationOf(function_argument))
    }

    private fun parseTypeRef(type_ref: Sem2Parser.Type_refContext): EntityRef {
        val module_ref = type_ref.module_ref()
        val moduleRef = if (module_ref == null) {
            null
        } else {
            if (module_ref.childCount == 1) {
                S2ModuleRef(null, module_ref.module_id(0).text, null)
            } else if (module_ref.childCount == 3) {
                S2ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, null)
            } else if (module_ref.childCount == 5) {
                val version = module_ref.LITERAL().text.drop(1).dropLast(1)
                S2ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, version)
            } else {
                error("module_ref was $module_ref, childCount was ${module_ref.childCount}")
            }
        }

        return EntityRef(moduleRef, parseEntityId(type_ref.entity_id()))
    }

    private fun parseEntityRef(entity_ref: Sem2Parser.Entity_refContext): EntityRef {
        val module_ref = entity_ref.module_ref()
        val moduleRef = if (module_ref == null) {
            null
        } else {
            if (module_ref.childCount == 1) {
                S2ModuleRef(null, module_ref.module_id(0).text, null)
            } else if (module_ref.childCount == 3) {
                S2ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, null)
            } else if (module_ref.childCount == 5) {
                val version = module_ref.LITERAL().text.drop(1).dropLast(1)
                S2ModuleRef(module_ref.module_id(0).text, module_ref.module_id(1).text, version)
            } else {
                error("module_ref was $module_ref, childCount was ${module_ref.childCount}")
            }
        }

        return EntityRef(moduleRef, parseEntityId(entity_ref.entity_id()))
    }

    private fun parseEntityId(entity_id: Sem2Parser.Entity_idContext): EntityId {
        val namespacedName = if (entity_id.namespace() != null) {
            parseNamespace(entity_id.namespace()) + entity_id.ID().text
        } else {
            listOf(entity_id.ID().text)
        }

        return EntityId(namespacedName)
    }

    private fun parseNamespace(namespace: Sem2Parser.NamespaceContext): List<String> {
        return parseLinkedList(namespace,
                Sem2Parser.NamespaceContext::ID,
                Sem2Parser.NamespaceContext::namespace,
                TerminalNode::getText)
    }

    private fun parseTypeOrUnderscore(typeOrUnderscore: Sem2Parser.Type_or_underscoreContext): S2Type? {
        if (typeOrUnderscore.type() != null) {
            return parseType(typeOrUnderscore.type())
        } else {
            if (typeOrUnderscore.UNDERSCORE() == null) error("Unexpected case")
            return null
        }
    }

    private fun parseType(type: Sem2Parser.TypeContext): S2Type {
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
            return S2Type.FunctionType(isReference, typeParameters, argumentTypes, outputType, locationOf(type))
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

    private fun parseCommaDelimitedTypes(cd_types: Sem2Parser.Cd_typesContext): List<S2Type> {
        return parseLinkedList(cd_types,
                Sem2Parser.Cd_typesContext::type,
                Sem2Parser.Cd_typesContext::cd_types,
                this::parseType)
    }

    private fun parseCommaDelimitedTypes(cd_types: Sem2Parser.Cd_types_nonemptyContext): List<S2Type> {
        return parseLinkedList(cd_types,
                Sem2Parser.Cd_types_nonemptyContext::type,
                Sem2Parser.Cd_types_nonemptyContext::cd_types_nonempty,
                this::parseType)
    }

    private fun parseCommaDelimitedTypesOrUnderscores(cd_types: Sem2Parser.Cd_types_or_underscores_nonemptyContext): List<S2Type?> {
        return parseLinkedList(cd_types,
                Sem2Parser.Cd_types_or_underscores_nonemptyContext::type_or_underscore,
                Sem2Parser.Cd_types_or_underscores_nonemptyContext::cd_types_or_underscores_nonempty,
                this::parseTypeOrUnderscore)
    }

    private fun parseTypeGivenParameters(type_ref: Sem2Parser.Type_refContext, parameters: List<S2Type>, typeLocation: Location): S2Type {
        val isReference = type_ref.AMPERSAND() != null
        if (type_ref.module_ref() != null || type_ref.entity_id().namespace() != null) {
            return S2Type.NamedType(parseTypeRef(type_ref), isReference, parameters, typeLocation)
        }

        val typeId = type_ref.entity_id().ID().text
        return S2Type.NamedType(EntityRef.of(typeId), isReference, parameters, typeLocation)
    }

    private fun parseOptions(options: Sem2Parser.DisjunctsContext): List<S2Option> {
        return parseLinkedList(options,
                Sem2Parser.DisjunctsContext::disjunct,
                Sem2Parser.DisjunctsContext::disjuncts,
                this::parseOption)
    }

    private fun parseOption(option: Sem2Parser.DisjunctContext): S2Option {
        val name = option.ID().text
        val type: S2Type? = option.type()?.let { parseType(it) }

        return S2Option(name, type, locationOf(option.ID().symbol))
    }
}

sealed class ParsingResult {
    abstract fun assumeSuccess(): S2Context
    data class Success(val context: S2Context): ParsingResult() {
        override fun assumeSuccess(): S2Context {
            return context
        }
    }
    data class Failure(val errors: List<Issue>, val partialContext: S2Context): ParsingResult() {
        init {
            if (errors.isEmpty()) {
                error("We are reporting a parsing failure, but no errors were recorded")
            }
        }
        override fun assumeSuccess(): S2Context {
            error("Parsing was not successful. Errors: $errors")
        }
    }
}

fun combineParsingResults(results: Collection<ParsingResult>): ParsingResult {
    val allFunctions = ArrayList<S2Function>()
    val allStructs = ArrayList<S2Struct>()
    val allUnions = ArrayList<S2Union>()
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
    val combinedContext = S2Context(allFunctions, allStructs, allUnions)
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

    override fun reportContextSensitivity(recognizer: Parser, dfa: DFA?, startIndex: Int, stopIndex: Int, prediction: Int, configs: ATNConfigSet?) {
        // Do nothing
    }

    override fun reportAttemptingFullContext(recognizer: Parser?, dfa: DFA?, startIndex: Int, stopIndex: Int, conflictingAlts: BitSet?, configs: ATNConfigSet?) {
        // Do nothing
    }
}

class LocationAwareParsingException(message: String, val location: Location, cause: Exception? = null): Exception(message, cause)

private fun parseANTLRStreamInner(stream: ANTLRInputStream, documentId: String): ParsingResult {
    val lexer = Sem2Lexer(stream)
    val errorListener = ErrorListener(documentId)
    lexer.addErrorListener(errorListener)
    val tokens = CommonTokenStream(lexer)
    val parser = Sem2Parser(tokens)
    parser.addErrorListener(errorListener)
    val tree: Sem2Parser.FileContext = parser.file()

    val extractor = ContextListener(documentId)
    try {
        ParseTreeWalker.DEFAULT.walk(extractor, tree)
    } catch(e: LocationAwareParsingException) {
        val partialContext = S2Context(extractor.functions, extractor.structs, extractor.unions)
        return ParsingResult.Failure(errorListener.errorsFound + listOf(Issue(e.message.orEmpty() + if (e.cause?.message != null) {": " + e.cause.message.orEmpty()} else "", e.location, IssueLevel.ERROR)), partialContext)
    }

    val context = S2Context(extractor.functions, extractor.structs, extractor.unions)
    if (!errorListener.errorsFound.isEmpty()) {
        return ParsingResult.Failure(errorListener.errorsFound, context)
    }

    return ParsingResult.Success(context)
}
