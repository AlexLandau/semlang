package net.semlang.parser

import net.semlang.api.NativeStruct
import net.semlang.api.Type

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

    object UNICODE_STRING : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            return true
        }
    }
}

fun getTypeValidatorFor(type: Type): LiteralValidator? {
    return when (type) {
        Type.INTEGER -> LiteralValidator.INTEGER
        Type.NATURAL -> LiteralValidator.NATURAL
        Type.BOOLEAN -> LiteralValidator.BOOLEAN
        is Type.List -> null
        is Type.NamedType -> {
            // TODO: Bug; support :lang:Unicode.String."Foo"
            if (type.ref.moduleRef == null && type.ref.id == NativeStruct.UNICODE_STRING.id) {
                return LiteralValidator.UNICODE_STRING
            }
            null
        }
        is Type.FunctionType -> null
        is Type.Try -> null
    }
}