package net.semlang.transforms

typealias RenamingStrategy = (name: String, allNamesPresent: Set<String>) -> String

object RenamingStrategies {
    fun getKeywordAvoidingStrategy(keywords: Set<String>): RenamingStrategy {
        return fun(varName: String, allVarNamesPresent: Set<String>): String {
            if (!keywords.contains(varName)) {
                return varName
            }
            var suffix = 1
            var newName = varName + suffix
            while (allVarNamesPresent.contains(newName)) {
                suffix += 1
                newName = varName + suffix
            }
            return newName
        }
    }
}
