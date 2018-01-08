package net.semlang.api

/*
 * Sem0 is a subset of the Semlang language, intended to make it easier to write interpreters and program-wide
 * optimizations. It is not intended to be written or easily read by humans in most cases. The following features are
 * explicitly not part of sem0:
 *
 * - Nesting of most expression types (most expressions take only variables as sub-expressions, the exceptions being blocks
 *   for if/then expressions)
 * - The module system and explicit namespacing (all EntityIds and EntityRefs are reduced to strings when converting to sem0)
 *   - Note that this means linking can only be done with sem1 code, not sem0 code.
 * - Interfaces (replaced with equivalent structs)
 * - Inline functions (replaced with bindings of explicit functions)
 */
data class S0Context(val functions: List<S0Function>, val structs: List<S0Struct>)

data class S0Struct(val id: String, val typeParameters: List<String>, val members: List<S0Member>, val requires: S0Block?, val annotations: List<S0Annotation>)

data class S0Member(val name: String, val type: S0Type)

sealed class S0Type {
    object Integer: S0Type()
    object Natural: S0Type()
    object Boolean: S0Type()
    data class List(val parameter: S0Type): S0Type()
    data class Try(val parameter: S0Type): S0Type()
    data class FunctionType(val argTypes: kotlin.collections.List<S0Type>, val outputType: S0Type): S0Type()
    data class NamedType(val id: String, val parameters: kotlin.collections.List<S0Type> = listOf()): S0Type()
}

data class S0Function(val id: String, val typeParameters: List<String>, val arguments: List<S0Argument>, val returnType: S0Type, val block: S0Block, val annotations: List<S0Annotation>)

data class S0Argument(val name: String, val type: S0Type)

data class S0Annotation(val name: String, val value: String?)

data class S0Block(val assignments: List<S0Assignment>, val returnedExpression: S0Expression)

data class S0Assignment(val name: String, val expression: S0Expression)

sealed class S0Expression {
    data class Variable(val name: String): S0Expression()
    data class IfThen(val conditionVarName: String, val thenBlock: S0Block, val elseBlock: S0Block): S0Expression()
    data class NamedFunctionCall(val functionId: String, val argumentVarNames: List<String>, val chosenParameters: List<S0Type>): S0Expression()
    data class ExpressionFunctionCall(val functionVarName: String, val argumentVarNames: List<String>, val chosenParameters: List<S0Type>): S0Expression()
    data class Literal(val type: S0Type, val literal: String): S0Expression()
    data class ListLiteral(val itemVarNames: List<String>, val chosenParameter: S0Type): S0Expression()
    data class NamedFunctionBinding(val functionId: String, val bindingVarNames: List<String?>, val chosenParameters: List<S0Type>): S0Expression()
    data class ExpressionFunctionBinding(val functionVarName: String, val bindingVarNames: List<String?>, val chosenParameters: List<S0Type>): S0Expression()
    data class Follow(val structureVarName: String, val name: String): S0Expression()
}