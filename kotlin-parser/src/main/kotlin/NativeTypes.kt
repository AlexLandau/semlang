package semlang.interpreter

import semlang.api.Type

sealed class TypeValidator {
    abstract fun validate(literal: String): Boolean

    object INTEGER : TypeValidator() {
        override fun validate(literal: String): Boolean {
            if (literal.startsWith("-")) {
                return literal != "-0" && NATURAL.validate(literal.substring(1))
            }
            return NATURAL.validate(literal)
        }
    }

    object NATURAL : TypeValidator() {
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

    object BOOLEAN : TypeValidator() {
        override fun validate(literal: String): Boolean {
            return literal == "true" || literal == "false"
        }
    }
}

// TODO: Return Try<TypeValidator>?
fun getTypeValidatorFor(type: Type): TypeValidator {
    return when (type) {
        Type.INTEGER -> TypeValidator.INTEGER
        Type.NATURAL -> TypeValidator.NATURAL
        Type.BOOLEAN -> TypeValidator.BOOLEAN
        is Type.List -> throw IllegalArgumentException("No type validator for List: $type")
        is Type.NamedType -> throw IllegalArgumentException("No type validator for NamedTypes: $type")
        is Type.FunctionType -> throw IllegalArgumentException("No type validator for FunctionTypes: $type")
        is Type.Try -> throw IllegalArgumentException("No type validator for Trys: $type")
    }
}
