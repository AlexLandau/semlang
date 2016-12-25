package semlang.interpreter

import semlang.api.Type
import java.math.BigInteger

// These are Semlang objects that are stored and handled by the interpreter.
// These should know their type.
sealed class SemObject {
    abstract fun getType(): Type

    // TODO: Someday, these should become data classes...
    // See https://youtrack.jetbrains.com/issue/KT-10330 (waiting on Kotlin 1.1)
    class Integer(val value: BigInteger) : SemObject() {
        override fun getType(): Type {
            return Type.INTEGER
        }

        override fun equals(other: Any?): kotlin.Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Integer

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "Integer(value=$value)"
        }
    }
    class Boolean(val value: kotlin.Boolean) : SemObject() {
        override fun getType(): Type {
            return Type.BOOLEAN
        }
        override fun equals(other: Any?): kotlin.Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Boolean

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "Boolean(value=$value)"
        }
    }
}
