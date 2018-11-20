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
import net.semlang.validator.Issue
import net.semlang.validator.IssueLevel
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

private class ContextListener(val documentId: String) : Sem1ParserBaseListener() {
    val structs: MutableList<UnvalidatedStruct> = ArrayList()
    val functions: MutableList<Function> = ArrayList()
    val interfaces: MutableList<UnvalidatedInterface> = ArrayList()
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

    override fun exitInterfac(ctx: Sem1Parser.InterfacContext) {
        if (ctx.exception == null) {
            interfaces.add(parseInterface(ctx))
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

        val ambiguousBlock: AmbiguousBlock = parseBlock(function.block())
        val argumentVariableIds = arguments.map { arg -> EntityRef.of(arg.name) }
        val block = scopeBlock(argumentVariableIds, ambiguousBlock)

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
            val externalVarIds = members.map { member -> EntityRef.of(member.name) }
            scopeBlock(externalVarIds, parseBlock(it))
        }

        val annotations = parseAnnotations(ctx.annotations())

        return UnvalidatedStruct(id, typeParameters, members, requires, annotations, locationOf(ctx.entity_id()))
    }

    private fun parseInterface(interfac: Sem1Parser.InterfacContext): UnvalidatedInterface {
        val id = parseEntityId(interfac.entity_id())
        val typeParameters = if (interfac.GREATER_THAN() != null) {
            parseTypeParameters(interfac.cd_type_parameters())
        } else {
            listOf()
        }
        val methods = parseMethods(interfac.methods())

        val annotations = parseAnnotations(interfac.annotations())

        return UnvalidatedInterface(id, typeParameters, methods, annotations, locationOf(interfac.entity_id()))
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

    private fun scopeBlock(externalVariableIds: List<EntityRef>, ambiguousBlock: AmbiguousBlock): Block {
        val localVariableIds = ArrayList(externalVariableIds)
        val statements: MutableList<Statement> = ArrayList()
        for (statement in ambiguousBlock.statements) {
            val expression = scopeExpression(localVariableIds, statement.expression)
            val name = statement.name
            if (name != null) {
                localVariableIds.add(EntityRef.of(name))
            }
            statements.add(Statement(statement.name, statement.type, expression, statement.nameLocation))
        }
        val returnedExpression = scopeExpression(localVariableIds, ambiguousBlock.returnedExpression)
        return Block(statements, returnedExpression, ambiguousBlock.location)
    }

    // TODO: Is it inefficient for varIds to be an ArrayList here?
// TODO: See if we can pull out the scoping step entirely, or rewrite (has seen too many changes now)
    private fun scopeExpression(varIds: ArrayList<EntityRef>, expression: AmbiguousExpression): Expression {
        return when (expression) {
            is AmbiguousExpression.Follow -> Expression.Follow(
                    scopeExpression(varIds, expression.structureExpression),
                    expression.name,
                    expression.location)
            is AmbiguousExpression.VarOrNamedFunctionBinding -> {
                if (varIds.contains(expression.functionIdOrVariable)) {
                    // TODO: The position of the variable is incorrect here
                    return Expression.ExpressionFunctionBinding(Expression.Variable(expression.functionIdOrVariable.id.namespacedName.last(), expression.location),
                            bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                            chosenParameters = expression.chosenParameters,
                            location = expression.location)
                } else {

                    return Expression.NamedFunctionBinding(expression.functionIdOrVariable,
                            bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                            chosenParameters = expression.chosenParameters,
                            location = expression.location,
                            functionRefLocation = expression.varOrNameLocation)
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
                        bindings = expression.bindings.map { expr -> if (expr != null) scopeExpression(varIds, expr) else null },
                        chosenParameters = expression.chosenParameters,
                        location = expression.location)
            }
            is AmbiguousExpression.IfThen -> Expression.IfThen(
                    scopeExpression(varIds, expression.condition),
                    thenBlock = scopeBlock(varIds, expression.thenBlock),
                    elseBlock = scopeBlock(varIds, expression.elseBlock),
                    location = expression.location
            )
            is AmbiguousExpression.VarOrNamedFunctionCall -> {
                if (varIds.contains(expression.functionIdOrVariable)) {
                    return Expression.ExpressionFunctionCall(
                            // TODO: The position of the variable is incorrect here
                            functionExpression = Expression.Variable(expression.functionIdOrVariable.id.namespacedName.last(), expression.location),
                            arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                            chosenParameters = expression.chosenParameters,
                            location = expression.location)
                } else {
                    return Expression.NamedFunctionCall(
                            functionRef = expression.functionIdOrVariable,
                            arguments = expression.arguments.map { expr -> scopeExpression(varIds, expr) },
                            chosenParameters = expression.chosenParameters,
                            location = expression.location,
                            functionRefLocation = expression.varOrNameLocation)
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
                        location = expression.location)
            }
            is AmbiguousExpression.Literal -> Expression.Literal(expression.type, expression.literal, expression.location)
            is AmbiguousExpression.ListLiteral -> {
                val contents = expression.contents.map { item -> scopeExpression(varIds, item) }
                Expression.ListLiteral(contents, expression.chosenParameter, expression.location)
            }
            is AmbiguousExpression.Variable -> Expression.Variable(expression.name, expression.location)
            is AmbiguousExpression.InlineFunction -> {
                val varIdsOutsideBlock = varIds + expression.arguments.map { arg -> EntityRef.of(arg.name) }
                val scopedBlock = scopeBlock(varIdsOutsideBlock, expression.block)
                Expression.InlineFunction(
                        expression.arguments,
                        expression.returnType,
                        scopedBlock,
                        expression.location)
            }
        }
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

    private fun parseBlock(block: Sem1Parser.BlockContext): AmbiguousBlock {
        val statements = parseStatements(block.statements())
        val returnedExpression = parseExpression(block.return_statement().expression())
        return AmbiguousBlock(statements, returnedExpression, locationOf(block))
    }

    private fun parseStatements(statements: Sem1Parser.StatementsContext): List<AmbiguousStatement> {
        return parseLinkedList(statements,
                Sem1Parser.StatementsContext::statement,
                Sem1Parser.StatementsContext::statements,
                this::parseStatement)
    }

    private fun parseStatement(statement: Sem1Parser.StatementContext): AmbiguousStatement {
        if (statement.assignment() != null) {
            val assignment = statement.assignment()
            val name = assignment.ID().text
            val type = if (assignment.type() != null) parseType(assignment.type()) else null
            val expression = parseExpression(assignment.expression())
            return AmbiguousStatement(name, type, expression, locationOf(assignment.ID().symbol))
        } else {
            val expression = parseExpression(statement.expression())
            return AmbiguousStatement(null, null, expression, locationOf(statement))
        }
    }

    private fun parseExpression(expression: Sem1Parser.ExpressionContext): AmbiguousExpression {
        try {
            if (expression.IF() != null) {
                val condition = parseExpression(expression.expression())
                val thenBlock = parseBlock(expression.block(0))
                val elseBlock = parseBlock(expression.block(1))
                return AmbiguousExpression.IfThen(condition, thenBlock, elseBlock, locationOf(expression))
            }

            if (expression.FUNCTION() != null) {
                val arguments = parseFunctionArguments(expression.function_arguments())
                val returnType = parseType(expression.type())
                val block = parseBlock(expression.block(0))
                return AmbiguousExpression.InlineFunction(arguments, returnType, block, locationOf(expression))
            }

            if (expression.LITERAL() != null) {
                val type = parseTypeGivenParameters(expression.type_ref(), listOf(), locationOf(expression.type_ref()))
                val literal = parseLiteral(expression.LITERAL())
                return AmbiguousExpression.Literal(type, literal, locationOf(expression))
            }

            if (expression.ARROW() != null) {
                val inner = parseExpression(expression.expression())
                val name = expression.ID().text
                return AmbiguousExpression.Follow(inner, name, locationOf(expression))
            }

            if (expression.LPAREN() != null) {
                val innerExpression = if (expression.expression() != null) {
                    parseExpression(expression.expression())
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
                    val bindings = parseBindings(expression.cd_expressions_or_underscores())

                    if (functionRefOrVar != null) {
                        return AmbiguousExpression.VarOrNamedFunctionBinding(functionRefOrVar, bindings, chosenParameters, locationOf(expression), locationOf(expression.entity_ref()))
                    } else {
                        return AmbiguousExpression.ExpressionOrNamedFunctionBinding(innerExpression!!, bindings, chosenParameters, locationOf(expression), locationOf(expression.expression()))
                    }
                }

                val chosenParameters = if (expression.LESS_THAN() != null) {
                    parseCommaDelimitedTypes(expression.cd_types_nonempty())
                } else {
                    listOf()
                }
                val arguments = parseCommaDelimitedExpressions(expression.cd_expressions())
                if (functionRefOrVar != null) {
                    return AmbiguousExpression.VarOrNamedFunctionCall(functionRefOrVar, arguments, chosenParameters, locationOf(expression), locationOf(expression.entity_ref()))
                } else {
                    return AmbiguousExpression.ExpressionOrNamedFunctionCall(innerExpression!!, arguments, chosenParameters, locationOf(expression), locationOf(expression.expression()))
                }
            }

            if (expression.LBRACKET() != null) {
                val contents = parseCommaDelimitedExpressions(expression.cd_expressions())
                val chosenParameter = parseType(expression.type())
                return AmbiguousExpression.ListLiteral(contents, chosenParameter, locationOf(expression))
            }

            if (expression.ID() != null) {
                return AmbiguousExpression.Variable(expression.ID().text, locationOf(expression))
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


    private fun parseBindings(cd_expressions_or_underscores: Sem1Parser.Cd_expressions_or_underscoresContext): List<AmbiguousExpression?> {
        return parseLinkedList(cd_expressions_or_underscores,
                Sem1Parser.Cd_expressions_or_underscoresContext::expression_or_underscore,
                Sem1Parser.Cd_expressions_or_underscoresContext::cd_expressions_or_underscores,
                this::parseBinding)
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
                this::parseExpression)
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
            val typeParameters = if (type.cd_type_parameters() != null) {
                parseTypeParameters(type.cd_type_parameters())
            } else {
                listOf()
            }
            val argumentTypes = parseCommaDelimitedTypes(type.cd_types())
            val outputType = parseType(type.type())
            return UnvalidatedType.FunctionType(typeParameters, argumentTypes, outputType, locationOf(type))
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
        if (typeId == "Integer") {
            if (isReference) {
                return UnvalidatedType.Invalid.ReferenceInteger(typeLocation)
            }
            return UnvalidatedType.Integer(typeLocation)
        } else if (typeId == "Boolean") {
            if (isReference) {
                return UnvalidatedType.Invalid.ReferenceBoolean(typeLocation)
            }
            return UnvalidatedType.Boolean(typeLocation)
        } else if (typeId == "List") {
            if (isReference) {
                throw LocationAwareParsingException("List is not a reference type; remove the &", locationOf(type_ref))
            }
            if (parameters.size != 1) {
                error("List should only accept a single parameter; parameters were: $parameters")
            }
            return UnvalidatedType.List(parameters[0], typeLocation)
        } else if (typeId == "Maybe") {
            if (isReference) {
                throw LocationAwareParsingException("Maybe is not a reference type; remove the &", locationOf(type_ref))
            }
            if (parameters.size != 1) {
                error("Maybe should only accept a single parameter; parameters were: $parameters")
            }
            return UnvalidatedType.Maybe(parameters[0], typeLocation)
        }

        return UnvalidatedType.NamedType(EntityRef.of(typeId), isReference, parameters, typeLocation)
    }

    private fun parseMethods(methods: Sem1Parser.MethodsContext): List<UnvalidatedMethod> {
        return parseLinkedList(methods,
                Sem1Parser.MethodsContext::method,
                Sem1Parser.MethodsContext::methods,
                this::parseMethod)
    }

    private fun parseMethod(method: Sem1Parser.MethodContext): UnvalidatedMethod {
        val name = method.ID().text
        val typeParameters = if (method.GREATER_THAN() != null) {
            parseTypeParameters(method.cd_type_parameters())
        } else {
            listOf()
        }
        val arguments = parseFunctionArguments(method.function_arguments())
        val returnType = parseType(method.type())

        return UnvalidatedMethod(name, typeParameters, arguments, returnType)
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
    val allInterfaces = ArrayList<UnvalidatedInterface>()
    val allUnions = ArrayList<UnvalidatedUnion>()
    val allErrors = ArrayList<Issue>()
    for (parsingResult in results) {
        if (parsingResult is ParsingResult.Success) {
            val rawContext = parsingResult.context
            allFunctions.addAll(rawContext.functions)
            allStructs.addAll(rawContext.structs)
            allInterfaces.addAll(rawContext.interfaces)
            allUnions.addAll(rawContext.unions)
        } else if (parsingResult is ParsingResult.Failure) {
            allErrors.addAll(parsingResult.errors)
            val context = parsingResult.partialContext
            allFunctions.addAll(context.functions)
            allStructs.addAll(context.structs)
            allInterfaces.addAll(context.interfaces)
            allUnions.addAll(context.unions)
        }
    }
    val combinedContext = RawContext(allFunctions, allStructs, allInterfaces, allUnions)
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
        val partialContext = RawContext(extractor.functions, extractor.structs, extractor.interfaces, extractor.unions)
        return ParsingResult.Failure(errorListener.errorsFound + listOf(Issue(e.message.orEmpty() + if (e.cause?.message != null) {": " + e.cause.message.orEmpty()} else "", e.location, IssueLevel.ERROR)), partialContext)
    }

    val context = RawContext(extractor.functions, extractor.structs, extractor.interfaces, extractor.unions)
    if (!errorListener.errorsFound.isEmpty()) {
        return ParsingResult.Failure(errorListener.errorsFound, context)
    }

    return ParsingResult.Success(context)
}
