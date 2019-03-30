package net.semlang.support.standardlibrary

import java.io.File
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.modules.parseAndValidateModuleDirectory

fun main(args: Array<String>) {
    val standardLibraryFolder = File("../../semlang-library/src/main/semlang")
    val standardLibraryModule = parseAndValidateModuleDirectory(standardLibraryFolder, CURRENT_NATIVE_MODULE_VERSION, getDefaultLocalRepository()).assumeSuccess()

    val localRepository = getDefaultLocalRepository()
    localRepository.unpublishIfPresent(standardLibraryModule.id)
    localRepository.publish(standardLibraryModule)
}
