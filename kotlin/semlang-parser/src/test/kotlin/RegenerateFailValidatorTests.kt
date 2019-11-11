package net.semlang.parser.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.internal.test.ErrorFile
import net.semlang.internal.test.loadErrorFile
import net.semlang.internal.test.writeErrorFileText
import net.semlang.parser.ParsingResult
import net.semlang.parser.parseString
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import java.io.File

private val TEST_MODULE_NAME = ModuleName("semlang", "validatorTestFile")

/**
 * This goes through the .errors files in the failValidator directory and replaces the errors with the
 * current set of errors found through validation.
 *
 * This can be used to more easily generate a new test, for example, by writing the code by itself and
 * running this to add the errors.
 */
fun main(args: Array<String>) {
    var filesCount = 0
    val failValidatorDir = File("../../semlang-parser-tests/failValidator")
    for (file in failValidatorDir.listFiles()) {
        if (!file.name.endsWith(".errors")) {
            System.out.println("Skipping non-.errors file $file")
            continue
        }

        val errorFile = loadErrorFile(file)
        val text = errorFile.getText()
        val parsingResult = parseString(text, file.absolutePath)
        if (parsingResult !is ParsingResult.Success) {
            error("Cannot regenerate the failValidator files: File $file has a parsing error rather than a validation error")
        }
        val validationResult = validate(parsingResult, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION, listOf())
        if (validationResult !is ValidationResult.Failure) {
            error("Cannot regenerate the failValidator files: File $file has no reported validation errors")
        }
        val regeneratedErrorFile = ErrorFile(errorFile.lines, validationResult.errors.toSet())
        file.writeText(writeErrorFileText(regeneratedErrorFile))
        filesCount++
    }
    System.out.println("Regenerated $filesCount files.")
}
