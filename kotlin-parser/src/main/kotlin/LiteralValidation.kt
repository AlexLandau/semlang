package semlang.interpreter

import semlang.api.Type

sealed class LiteralValidator {
    abstract fun validate(literal: String): Boolean

    object INTEGER : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            if (literal.startsWith("-")) {
                return literal != "-0" && NATURAL.validate(literal.substring(1))
            }
            return NATURAL.validate(literal)
        }
    }

    object NATURAL : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            if (literal.isEmpty()) {
                return false
            }
            if (literal.startsWith("0")) {
                return literal == "0"
            }
            return literal.all { c -> c >= '0' && c <= '9' }
        }
    }

    object BOOLEAN : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            return literal == "true" || literal == "false"
        }
    }
}

fun getTypeValidatorFor(type: Type): LiteralValidator {
    return when (type) {
        Type.INTEGER -> LiteralValidator.INTEGER
        Type.NATURAL -> LiteralValidator.NATURAL
        Type.BOOLEAN -> LiteralValidator.BOOLEAN
        is Type.List -> throw IllegalArgumentException("No literal validator for List: $type")
        is Type.NamedType -> throw IllegalArgumentException("No literal validator for NamedTypes: $type")
        is Type.FunctionType -> throw IllegalArgumentException("No literal validator for FunctionTypes: $type")
        is Type.Try -> throw IllegalArgumentException("No literal validator for Trys: $type")
    }
}