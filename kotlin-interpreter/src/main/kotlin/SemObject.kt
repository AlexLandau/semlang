package semlang.interpreter

import semlang.api.FunctionId
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
    data class Struct(val struct: semlang.api.Struct, val objects: List<SemObject>): SemObject()
    sealed class Try: SemObject() {
        data class Success(val contents: SemObject): Try()
        object Failure: Try()
    }
    data class SemList(val contents: List<SemObject>): SemObject()
    data class FunctionBinding(val functionId: FunctionId, val bindings: List<SemObject?>): SemObject()
}
