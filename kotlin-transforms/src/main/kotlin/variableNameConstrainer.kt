import semlang.api.*
import semlang.transforms.getAllDeclaredVarNames

/**
 * Replaces the names of any variables or arguments in the context in a consistent way.
 */
fun constrainVariableNames(context: ValidatedContext, renamingStrategy: VariableRenamingStrategy): ValidatedContext {
    val validatingStrategy = getValidatingStrategy(renamingStrategy)
    return ValidatedContext.create(renameWithinFunctions(context.ownFunctionImplementations, validatingStrategy), context.ownStructs,
            renameInterfaceArguments(context.ownInterfaces, validatingStrategy), context.upstreamContexts)
}

private fun getValidatingStrategy(delegate: VariableRenamingStrategy): VariableRenamingStrategy {
    return fun (varName: String, allVarNamesPresent: Set<String>): String {
        if (varName.isBlank()) {
            error("Variable names should not be blank; incoming variable name was $varName")
        }
        val newName = delegate(varName, allVarNamesPresent)
        if (newName != varName && allVarNamesPresent.contains(newName)) {
            error("A variable renaming strategy resulted in a name that is already in use: '$varName' to '$newName'")
        }
        if (newName.isBlank()) {
            error("A variable renaming strategy resulted in a blank variable name: '$varName' to '$newName'")
        }
        //TODO: Further validation
        return newName
    }
}

private fun renameInterfaceArguments(ownInterfaces: Map<FunctionId, Interface>, rename: VariableRenamingStrategy): Map<FunctionId, Interface> {
    return ownInterfaces.mapValues { (_, interfac) -> renameInterfaceArguments(interfac, rename) }
}

private fun renameInterfaceArguments(interfac: Interface, rename: VariableRenamingStrategy): Interface {
    val methods = interfac.methods.map { method -> renameMethodArguments(method, rename) }
    // TODO: Start using this pattern elsewhere, probably
    return interfac.copy(methods = methods)
}

private fun renameMethodArguments(method: Method, rename: VariableRenamingStrategy): Method {
    val argumentNames = method.arguments.map { argument -> argument.name }.toSet()
    val arguments = method.arguments.map { argument -> renameArgument(argument, argumentNames, rename) }
    return method.copy(arguments = arguments)
}

private fun renameArgument(argument: Argument, otherVariables: Set<String>, rename: VariableRenamingStrategy): Argument {
    return argument.copy(name = rename(argument.name, otherVariables))
}

private fun renameWithinFunctions(ownFunctionImplementations: Map<FunctionId, ValidatedFunction>, rename: VariableRenamingStrategy): Map<FunctionId, ValidatedFunction> {
    return ownFunctionImplementations.mapValues { (_, function) -> renameWithinFunction(function, rename) }
}

private fun renameWithinFunction(function: ValidatedFunction, rename: VariableRenamingStrategy): ValidatedFunction {
    val originalVarsInFunction = getAllDeclaredVarNames(function)
    val allVarsInFunction = HashSet<String>(originalVarsInFunction)
    val renamingMap = HashMap<String, String>()
    originalVarsInFunction.forEach { varName ->
        val newName = rename(varName, allVarsInFunction)
        renamingMap.put(varName, newName)
        allVarsInFunction.add(newName)
    }
    return renameWithinFunction(function, renamingMap)
}

private fun renameWithinFunction(function: ValidatedFunction, renamingMap: Map<String, String>): ValidatedFunction {
    val arguments = function.arguments.map { argument -> renameArgument(argument, renamingMap) }
    val block = renameBlock(function.block, renamingMap)
    return function.copy(arguments = arguments, block = block)
}

private fun renameBlock(block: TypedBlock, renamingMap: Map<String, String>): TypedBlock {
    val assignments = block.assignments.map { assignment -> renameWithinAssignment(assignment, renamingMap) }
    val returnedExpression = renameWithinExpression(block.returnedExpression, renamingMap)
    return TypedBlock(block.type, assignments, returnedExpression)
}

private fun renameWithinAssignment(assignment: ValidatedAssignment, renamingMap: Map<String, String>): ValidatedAssignment {
    val newName = renamingMap[assignment.name] ?: error("Bug in renaming")
    val expression = renameWithinExpression(assignment.expression, renamingMap)
    return ValidatedAssignment(newName, assignment.type, expression)
}

private fun renameWithinExpression(expression: TypedExpression, renamingMap: Map<String, String>): TypedExpression {
    return when (expression) {
        is TypedExpression.Variable -> {
            val newName = renamingMap[expression.name] ?: error("Bug in renaming")
            TypedExpression.Variable(expression.type, newName)
        }
        is TypedExpression.IfThen -> {
            val condition = renameWithinExpression(expression.condition, renamingMap)
            val thenBlock = renameBlock(expression.thenBlock, renamingMap)
            val elseBlock = renameBlock(expression.elseBlock, renamingMap)
            TypedExpression.IfThen(expression.type, condition, thenBlock, elseBlock)
        }
        is TypedExpression.NamedFunctionCall -> {
            val arguments = expression.arguments.map { argument -> renameWithinExpression(argument, renamingMap) }
            TypedExpression.NamedFunctionCall(expression.type, expression.functionId, arguments, expression.chosenParameters)
        }
        is TypedExpression.ExpressionFunctionCall -> {
            val functionExpression = renameWithinExpression(expression.functionExpression, renamingMap)
            val arguments = expression.arguments.map { argument -> renameWithinExpression(argument, renamingMap) }
            TypedExpression.ExpressionFunctionCall(expression.type, functionExpression, arguments, expression.chosenParameters)
        }
        is TypedExpression.Literal -> {
            expression
        }
        is TypedExpression.Follow -> {
            val innerExpression = renameWithinExpression(expression.expression, renamingMap)
            TypedExpression.Follow(expression.type, innerExpression, expression.name)
        }
        is TypedExpression.NamedFunctionBinding -> {
            val bindings = expression.bindings.map { binding -> if (binding == null) null else renameWithinExpression(binding, renamingMap) }
            TypedExpression.NamedFunctionBinding(expression.type, expression.functionId, bindings, expression.chosenParameters)
        }
        is TypedExpression.ExpressionFunctionBinding -> {
            val functionExpression = renameWithinExpression(expression.functionExpression, renamingMap)
            val bindings = expression.bindings.map { binding -> if (binding == null) null else renameWithinExpression(binding, renamingMap) }
            TypedExpression.ExpressionFunctionBinding(expression.type, functionExpression, bindings, expression.chosenParameters)
        }
    }
}

private fun renameArgument(argument: Argument, renamingMap: Map<String, String>): Argument {
    return Argument(renamingMap[argument.name] ?: error("Bug in renaming"), argument.type)
}

typealias VariableRenamingStrategy = (varName: String, allVarNamesPresent: Set<String>) -> String

object RenamingStrategies {
    fun avoidNumeralAtStartByPrependingUnderscores(varName: String, allVarNamesPresent: Set<String>): String {
        if (!varName[0].isDigit()) {
            return varName
        }
        var newName = "_" + varName
        while (allVarNamesPresent.contains(newName)) {
            newName = "_" + newName
        }
        return newName
    }
}
