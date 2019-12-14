package net.semlang.validator

import net.semlang.api.NativeOpaqueType
import net.semlang.api.NativeStruct
import net.semlang.api.Type
import net.semlang.api.isNativeModule

sealed class LiteralValidator {
    abstract fun validate(literal: String): Boolean

    object INTEGER : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            if (literal.startsWith("-")) {
                return literal != "-0" && validateNatural(literal.substring(1))
            }
            return validateNatural(literal)
        }

        private fun validateNatural(literal: String): Boolean {
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

    object STRING : LiteralValidator() {
        override fun validate(literal: String): Boolean {
            return true
        }
    }
}
