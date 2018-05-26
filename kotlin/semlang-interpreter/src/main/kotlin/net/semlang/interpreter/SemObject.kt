package net.semlang.interpreter

import net.semlang.api.*
import java.io.PrintStream
import java.math.BigInteger

// These are Semlang objects that are stored and handled by the interpreter.
// These should know their type.
sealed class SemObject {
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
    // An instance of an interface.
    data class Instance(val interfaceDef: net.semlang.api.Interface, val methods: List<SemObject.FunctionBinding>): SemObject()
    sealed class Try: SemObject() {
        data class Success(val contents: SemObject): Try()
        object Failure: Try() {
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
    // Special case for the Unicode.String type
    data class UnicodeString(val contents: String): SemObject()
    // Note: The module here is the module that defines the bound function. It can be used to evaluate the function.
    // Absence of a containing module indicates the native module.
    data class FunctionBinding(val target: FunctionBindingTarget, val containingModule: ValidatedModule?, val bindings: List<SemObject?>): SemObject()

    // Types for threaded object types
    data class TextOut(val out: PrintStream): SemObject()
    data class ListBuilder(val listSoFar: ArrayList<SemObject>): SemObject()

    // Special cases for standard library optimizations
    data class Int64(val value: Long): SemObject()

    // Used by mock tests to represent mocked objects of threaded types
    data class Mock(val name: String) : SemObject()
}

sealed class FunctionBindingTarget {
    data class Named(val functionRef: ResolvedEntityRef): FunctionBindingTarget()
    data class Inline(val functionDef: TypedExpression.InlineFunction): FunctionBindingTarget()
    data class InterfaceAdapter(val interfac: Interface): FunctionBindingTarget()
}
