package net.semlang.parser.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.parser.ParsingResult
import net.semlang.parser.parseString
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import java.io.File

private val TEST_MODULE_NAME = ModuleName("semlang", "validatorTestFile")

fun main(args: Array<String>) {
    var filesCount = 0
    val failValidatorDir = File("src/test/semlang/validatorTests/failValidator")
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
