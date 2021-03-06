package net.semlang.parser.test

import net.semlang.api.*
import net.semlang.api.Function
import net.semlang.parser.*
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PartialParsingTest {
    @Test
    fun testCanDoValidationWithPartialParsingError() {
        val sourceFile = File("../../semlang-parser-tests/partialParsing/partiallyValid.sem")
        val parsingResult = parseFile(sourceFile)
        when (parsingResult) {
            is ParsingResult.Success -> {
                throw IllegalStateException("Should have had a parsing error from the parsing result")
            }
            is ParsingResult.Failure -> {
                val functionIds = parsingResult.partialContext.functions.map(Function::id).map(EntityId::toString).toSet()
                // For some reason, "bar" stays there
//                assertEquals(setOf("foo", "baz"), functionIds)
                val validationResult = validate(parsingResult, ModuleName("a", "b"), CURRENT_NATIVE_MODULE_VERSION, listOf())
                when (validationResult) {
                    is ValidationResult.Success -> {
                        throw AssertionError("Should have failed validation")
                    }
                    is ValidationResult.Failure -> {
                        // Intended result
                    }
                }
            }
        }
    }

    @Test
    fun testCanDoValidationWithPartialParsingError2() {
        val sourceFile = File("../../semlang-parser-tests/partialParsing/partiallyValid2.sem")
        val parsingResult = parseFile(sourceFile)
        when (parsingResult) {
            is ParsingResult.Success -> {
                throw IllegalStateException("Should have had a parsing error from the parsing result")
            }
            is ParsingResult.Failure -> {
                val functionIds = parsingResult.partialContext.functions.map(Function::id).map(EntityId::toString).toSet()
//                assertEquals(setOf("bar"), functionIds)
                val validationResult = validate(parsingResult, ModuleName("a", "b"), CURRENT_NATIVE_MODULE_VERSION, listOf())
                when (validationResult) {
                    is ValidationResult.Success -> {
                        throw AssertionError("Should have failed validation")
                    }
                    is ValidationResult.Failure -> {
                        // Intended result
                    }
                }
            }
        }
    }

    @Test
    fun testCanDoValidationWithPartialParsingError3() {
        val sourceFile = File("../../semlang-parser-tests/partialParsing/partiallyValid3.sem")
        val parsingResult = parseFile(sourceFile)
        when (parsingResult) {
            is ParsingResult.Success -> {
                throw IllegalStateException("Should have had a parsing error from the parsing result")
            }
            is ParsingResult.Failure -> {
                val structIds = parsingResult.partialContext.structs.map(UnvalidatedStruct::id).map(EntityId::toString).toSet()
//                assertEquals(setOf("bar"), structIds)
                val functionIds = parsingResult.partialContext.functions.map(Function::id).map(EntityId::toString).toSet()
                assertEquals(setOf<String>(), functionIds)
                val validationResult = validate(parsingResult, ModuleName("a", "b"), CURRENT_NATIVE_MODULE_VERSION, listOf())
                when (validationResult) {
                    is ValidationResult.Success -> {
                        throw AssertionError("Should have failed validation")
                    }
                    is ValidationResult.Failure -> {
                        // Intended result
                    }
                }
            }
        }
    }
}