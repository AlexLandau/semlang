package semlang.api

//TODO: Validate inputs (non-overlapping keys)
data class InterpreterContext(val functions: Map<FunctionId, Function>, val structs: Map<FunctionId, Struct>, val interfaces: Map<FunctionId, Interface>)

// TODO: We'll want this to be able to reference upstream contexts without repeating their contents
class ValidatedContext private constructor(val functionImplementations: Map<FunctionId, ValidatedFunction>,
                       val functionSignatures: Map<FunctionId, TypeSignature>,
                       val structs: Map<FunctionId, Struct>,
                       val interfaces: Map<FunctionId, Interface>) {
    val interfacesByAdapterId = interfaces.values.associateBy(Interface::adapterId)

    companion object {
        fun create(functions: Map<FunctionId, ValidatedFunction>,
                   structs: Map<FunctionId, Struct>,
                   interfaces: Map<FunctionId, Interface>): ValidatedContext {
            val functionSignatures = functions.values.map(ValidatedFunction::toTypeSignature).associateBy(TypeSignature::id)
            return ValidatedContext(functions, functionSignatures, structs, interfaces)
        }

        fun getNativeContext(): ValidatedContext {
            return ValidatedContext(mapOf(), getNativeFunctionDefinitions(), getNativeStructs(), getNativeInterfaces())
        }
    }
}
