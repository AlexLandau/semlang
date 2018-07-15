package net.semlang.parser.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.parser.*
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import org.junit.Test
import java.io.File

class PartialParsingTest {
    @Test
    fun testCanDoValidationWithPartialParsingError() {
        val sourceFile = File("src/test/semlang/validatorTests/partialParsing/partiallyValid.sem")
        val parsingResult = parseFile(sourceFile)
        when (parsingResult) {
            is ParsingResult.Success -> {
                throw IllegalStateException("Should have had a parsing error from the parsing result")
            }
            is ParsingResult.Failure -> {
                val validationResult = validate(parsingResult, ModuleId("a", "b", "develop"), CURRENT_NATIVE_MODULE_VERSION, listOf())
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