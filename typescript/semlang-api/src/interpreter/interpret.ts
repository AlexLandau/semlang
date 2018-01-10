import { Function, Module, Block, isAssignment, Expression, Type, isNamedType } from "../api/language";
import { SemObject, listObject, bindingObject, booleanObject, integerObject, naturalObject } from "./SemObject";
import { NativeFunctions } from "./nativeFunctions";

export function evaluateLiteral(module: Module, type: Type, value: string): SemObject {
    const context = new InterpreterContext(module);
    return context.evaluateLiteral(type, value);    
}

export function interpret(module: Module, functionName: string, args: SemObject[]): SemObject {
    const context = new InterpreterContext(module);
    return context.evaluateFunctionCall(functionName, args);
}

type BoundVars = { [varName: string]: SemObject };

class InterpreterContext {
    module: Module;
    constructor(module: Module) {
        this.module = module;
    }

    evaluateFunctionCall(functionName: string, args: SemObject[]): SemObject {
        // Note: This currently isn't going to do nearly enough checks
        if (functionName in NativeFunctions) {
            const theFunction = NativeFunctions[functionName];
            return theFunction(...args);
        }

        // Find the function
        const theFunction = this.module.functions[functionName];
        if (theFunction !== undefined) {
            const boundArguments: BoundVars = {};
            theFunction.arguments.forEach((argument, index) => {
                const inputForArg = args[index];
                // TODO: Might want to do some type checking here
                boundArguments[argument.name] = inputForArg;
            });
            return this.evaluateBlock(theFunction.block, boundArguments);
        }

        const theStruct = this.module.structs[functionName];
        if (theStruct !== undefined) {
            throw new Error(`TODO: Implement struct constructors`);
        }

        const theInterface = this.module.interfaces[functionName];
        if (theInterface !== undefined) {
            throw new Error(`TODO: Implement interface constructors`);
        }

        const theAdaptedInterface = this.module.interfacesByAdapterId[functionName];
        if (theAdaptedInterface !== undefined) {
            throw new Error(`TODO: Implement interface adapter constructors`);
            
        }

        throw new Error(`Couldn't find the function ${functionName}`);
    }

    evaluateBoundFunction(functionBinding: SemObject.FunctionBinding, args: SemObject[]): SemObject {
        const fullyBoundBinding = combineBindings(functionBinding, args);
        const functionId = fullyBoundBinding.functionId;
        const fullArgs = fullyBoundBinding.bindings as SemObject[]; // These should all be defined by now
        return this.evaluateFunctionCall(functionId, fullArgs);
    }

    evaluateBlock(block: Block, alreadyBoundVars: BoundVars): SemObject {
        for (const blockElement of block) {
            if (isAssignment(blockElement)) {
                const varName = blockElement.let;
                const expression = blockElement.be;
    
                alreadyBoundVars[varName] = this.evaluateExpression(expression, alreadyBoundVars);
            } else {
                const expression = blockElement.return;
                return this.evaluateExpression(expression, alreadyBoundVars);
            }
        }
        throw new Error(`Malformed block: ${JSON.stringify(block)}`);
    }
    
    evaluateExpression(expression: Expression, alreadyBoundVars: BoundVars): SemObject {
        if (expression.type === "var") {
            const varName = expression.var;
            const value = alreadyBoundVars[varName];
            if (value === undefined) {
                throw new Error(`Undefined variable ${varName}`);
            }
            return value;
        } else if (expression.type === "ifThen") {
            const conditionalExpression = expression.if;
            const conditionalValue = this.evaluateExpression(conditionalExpression, alreadyBoundVars);
            if (conditionalValue.type !== "Boolean") {
                throw new Error(`Non-boolean conditional result: ${conditionalValue.type}`);
            }
            const resultBlock = (conditionalValue.value) ? expression.then : expression.else;
            // Note that we copy the variable bindings into a new object so new variables introduced
            // in the new block scope don't leak
            return this.evaluateBlock(resultBlock, {...alreadyBoundVars});
        } else if (expression.type === "namedCall") {
            const functionName = expression.function;
            const argumentExpressions = expression.arguments;
            const evaluatedArguments = argumentExpressions.map((argExpr) => this.evaluateExpression(argExpr, alreadyBoundVars));
    
            return this.evaluateFunctionCall(functionName, evaluatedArguments);
        } else if (expression.type === "expressionCall") {
            const functionExpression = expression.expression;
            const functionBinding = this.evaluateExpression(functionExpression, alreadyBoundVars);
            if (functionBinding.type !== "binding") {
                throw new Error(`Expected a function binding`);
            }
            const argumentExpressions = expression.arguments;
            const evaluatedArguments = argumentExpressions.map((argExpr) => this.evaluateExpression(argExpr, alreadyBoundVars));

            return this.evaluateBoundFunction(functionBinding, evaluatedArguments);
        } else if (expression.type === "literal") {
            const type = expression.literalType;
            return this.evaluateLiteral(type, expression.value);
        } else if (expression.type === "list") {
            const contents = expression.contents.map((expression) => this.evaluateExpression(expression, alreadyBoundVars));
            return listObject(contents);
        } else if (expression.type === "follow") {
            const structureExpression = expression.expression;
            const structureObject = this.evaluateExpression(structureExpression, alreadyBoundVars);
            const followName = expression.name;
            if (structureObject.type === "struct") {
                const structDef = structureObject.struct;
                const members = structureObject.members;
                const index = findIndex(structDef.members, (member) => member.name === followName);
                if (index === -1) {
                    throw new Error(`Struct of type ${structDef.id} doesn't have member named ${followName}`);
                }
            }
            // TODO: If we need to do interfaces separately, do that
            throw new Error(`Object wasn't a structure`);
        } else if (expression.type === "namedBinding") {
            const functionId = expression.function;
            const bindingExpressions = expression.bindings;

            const bindings = bindingExpressions.map((bindingExpression) => {
                if (bindingExpression === null) {
                    return undefined;
                } else {
                    return this.evaluateExpression(bindingExpression, alreadyBoundVars);
                }
            });

            return bindingObject(functionId, bindings);
        } else if (expression.type === "expressionBinding") {
            const functionExpression = expression.expression;
            const functionBinding = this.evaluateExpression(functionExpression, alreadyBoundVars);
            if (functionBinding.type !== "binding") {
                throw new Error(`Expected a function binding`);
            }
            const bindingExpressions = expression.bindings;
            
            const explicitBindings = bindingExpressions.map((bindingExpression) => {
                if (bindingExpression === null) {
                    return undefined;
                } else {
                    return this.evaluateExpression(bindingExpression, alreadyBoundVars);
                }
            });

            return combineBindings(functionBinding, explicitBindings);
        } else if (expression.type === "inlineFunction") {
            throw new Error("TODO: implement inline functions")
        } else {
            assertNever(expression);
            throw new Error(`Expected an expression, but was: ${JSON.stringify(expression)}`);
        }
    }

    evaluateLiteral(type: Type, value: string): SemObject {
        if (type === "Boolean") {
            if (value === "true") {
                return booleanObject(true);
            } else if (value === "false") {
                return booleanObject(false);
            }
            throw new Error(`Unexpected Boolean literal ${value}`);
        } else if (type === "Integer") {
            // TODO: Will need to fix for large integer support
            const jsNumber = Number(value);
            return integerObject(jsNumber);
        } else if (type === "Natural") {
            const jsNumber = Number(value);
            return naturalObject(jsNumber);
        } else {
            // Remainder of cases should be named types
            if (isNamedType(type)) {
                const name = type.name;
                // TODO: Handle strings
            }
            throw new Error(`TODO: Implement case for type ${JSON.stringify(type)}`);
        }
    }
}

function combineBindings(originalBinding: SemObject.FunctionBinding, explicitBinding: Array<SemObject | undefined>): SemObject.FunctionBinding {
    const combinedBindings = [] as Array<SemObject | undefined>;
    let explicitBindingIndex = 0;
    originalBinding.bindings.forEach((binding) => {
        if (binding !== undefined) {
            combinedBindings.push(binding);
        } else {
            const newBinding = explicitBinding[explicitBindingIndex];
            explicitBindingIndex++;
            combinedBindings.push(newBinding);
        }
    })
    if (explicitBindingIndex !== explicitBinding.length) {
        throw new Error(`Binding length mismatch`);
    }
    return bindingObject(originalBinding.functionId, combinedBindings);
}
