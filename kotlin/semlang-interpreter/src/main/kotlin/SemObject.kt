package net.semlang.interpreter

import net.semlang.api.*
import java.io.PrintStream
import java.math.BigInteger

// These are Semlang objects that are stored and handled by the interpreter.
// These should know their type.
sealed class SemObject {
    companion object {
        val Void = Struct(NativeStruct.VOID, listOf())
    }

    data class Integer(val value: BigInteger) : SemObject()
    data class Natural(val value: BigInteger) : SemObject() {
        init {
            if (value < BigInteger.ZERO) {
                throw IllegalArgumentException("Naturals can't be less than zero; was $value")
            }
        }
    }
    data class Boolean(val value: kotlin.Boolean) : SemObject()
    data class Struct(val struct: net.semlang.api.Struct, val objects: List<SemObject>): SemObject() {
        override fun toString(): String {
            return "${struct.id}[${
              struct.members.mapIndexed { index, member ->
                  "${member.name}: ${objects[index].toString()}"
              }.joinToString(", ")
            }]"
        }
    }
    // An instance of a union.
    data class Union(val union: net.semlang.api.Union, val optionIndex: Int, val contents: SemObject?): SemObject()
    sealed class Maybe: SemObject() {
        data class Success(val contents: SemObject): Maybe()
        object Failure: Maybe() {
            override fun toString(): String {
                return "Failure"
            }
        }
    }
    data class SemList(val contents: List<SemObject>): SemObject() {
        override fun toString(): String {
            return contents.toString()
        }
    }
    // Special case for the String type
    data class SemString(val contents: String): SemObject()
    // Note: The module here is the module that defines the bound function. It can be used to evaluate the function.
    // Absence of a containing module indicates the native module.
    data class FunctionBinding(val target: FunctionBindingTarget, val containingModule: ValidatedModule?, val bindings: List<SemObject?>): SemObject()

    // Types for native reference types
    data class TextOut(val out: PrintStream): SemObject()
    data class ListBuilder(val listSoFar: ArrayList<SemObject>): SemObject()
    data class Var(var value: SemObject): SemObject()

    // Special cases for standard library optimizations
    data class Int64(val value: Long): SemObject()

    // Used by mock tests to represent mocked references
    data class Mock(val name: String) : SemObject()

}

sealed class FunctionBindingTarget {
    data class Named(val functionRef: ResolvedEntityRef): FunctionBindingTarget()
    data class Inline(val functionDef: TypedExpression.InlineFunction): FunctionBindingTarget()
}
