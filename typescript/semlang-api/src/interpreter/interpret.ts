import * as UtfString from "utfstring";
import { Function, Module, Block, isAssignment, Expression, Type, isNamedType, isTryType, getAdapterStruct, Struct, getStructType } from "../api/language";
import { SemObject, listObject, bindingObject, booleanObject, integerObject, naturalObject, failureObject, successObject, structObject, stringObject, instanceObject } from "./SemObject";
import { NativeFunctions, NativeStructs } from "./nativeFunctions";
import { findIndex, assertNever } from "./util";

export function evaluateLiteral(module: Module, type: Type, value: string): SemObject {
    const context = new InterpreterContext(module);
    return context.evaluateLiteral(type, value);    
}

export function interpret(module: Module, functionName: string, args: SemObject[]): SemObject {
    const context = new InterpreterContext(module);
    return context.evaluateFunctionCall(functionName, args);
}

type BoundVars = { [varName: string]: SemObject };

export class InterpreterContext {
    module: Module;
    constructor(module: Module) {
        this.module = module;
    }

    evaluateFunctionCall(functionName: string, args: SemObject[]): SemObject {
        // Note: This currently isn't going to do nearly enough checks
        if (functionName in NativeFunctions) {
            const theFunction = NativeFunctions[functionName];
            return theFunction(this, ...args);
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

        const theStruct = this.getStructByName(functionName);
        if (theStruct !== undefined) {
            if (theStruct.requires !== undefined) {
                const proposedValues: BoundVars = {};
                theStruct.members.forEach((member, index) => {
                    const inputForArg = args[index];
                    // TODO: Might want to do some type checking here
                    proposedValues[member.name] = inputForArg;
                });
                const requiresValue = this.evaluateBlock(theStruct.requires, proposedValues);
                if (requiresValue.type !== "Boolean") {
                    throw new Error(`Non-boolean value returned from a 'requires' block`);
                }
                const isSatisfied = requiresValue.value;
                if (isSatisfied) {
                    return successObject(structObject(theStruct, args));
                } else {
                    return failureObject();
                }
            } else {
                return structObject(theStruct, args);
            }
        }

        const theInterface = this.module.interfaces[functionName];
        if (theInterface !== undefined) {
            if (args.length !== 2) {
                throw new Error(`Expected two arguments to an interface constructor`);
            }
            const dataObject = args[0];
            const adapterObject = args[1];
            if (adapterObject.type !== "struct") {
                throw new Error(`Was expecting an adapter struct`);
            }
            const adapterMembers = adapterObject.members;
            if (adapterMembers.length !== theInterface.methods.length) {
                throw new Error(`Adapter members length doesn't match interface methods length`);
            }
            const reboundMethods = [] as SemObject.FunctionBinding[];
            for (let i = 0; i < adapterMembers.length; i++) {
                const adapterMember = adapterMembers[i];
                const method = theInterface.methods[i];
                if (adapterMember.type !== "binding") {
                    throw new Error(`Adapter member was a non-binding type`);
                }
                const fixedBindings = adapterMember.bindings.slice();
                if (fixedBindings[0] !== undefined) {
                    throw new Error(`Expected an undefined binding for the 0th element`);
                }
                fixedBindings[0] = dataObject;
                const reboundMethod = bindingObject(adapterMember.functionId, fixedBindings);
                reboundMethods.push(reboundMethod);
            }

            return instanceObject(theInterface, reboundMethods);
        }

        const theAdaptedInterface = this.module.interfacesByAdapterId[functionName];
        if (theAdaptedInterface !== undefined) {
            const adapterStruct = getAdapterStruct(theAdaptedInterface);
            return structObject(adapterStruct, args);
        }

        throw new Error(`Couldn't find the function ${functionName}`);
    }

    evaluateBoundFunction(functionBinding: SemObject.FunctionBinding, args: SemObject[]): SemObject {
        const fullyBoundBinding = combineBindings(functionBinding, args);
        const functionId = fullyBoundBinding.functionId;
        const fullArgs = fullyBoundBinding.bindings as SemObject[]; // These should all be defined by now
        return this.evaluateFunctionCall(functionId, fullArgs);
    }

    private evaluateBlock(block: Block, alreadyBoundVars: BoundVars): SemObject {
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
    
    private evaluateExpression(expression: Expression, alreadyBoundVars: BoundVars): SemObject {
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
                return members[index];
            } else if (structureObject.type === "instance") {
                const interfaceDef = structureObject.interface;
                const methods = structureObject.methods;
                const index = findIndex(interfaceDef.methods, (method) => method.name === followName);
                if (index === -1) {
                    throw new Error(`Interface of type ${interfaceDef.id} doesn't have method named ${followName}`);
                }
                return methods[index];
            } else if (structureObject.type === "String") {
                const stringLiteral = structureObject.value;

                const codePoints = UtfString.stringToCodePoints(stringLiteral);
                const codePointObjects = codePoints.map((codePoint: number) => {
                    const charCodeNatural = naturalObject(codePoint);
                    const charCodeObject = structObject(NativeStructs["Unicode.CodePoint"], [charCodeNatural]);
                    return charCodeObject;
                });
                return listObject(codePointObjects);
            }
            // TODO: If we need to do interfaces separately, do that
            throw new Error(`Object wasn't a structure; was: ${JSON.stringify(structureObject)}`);
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
            throw assertNever(expression);
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
        } else if (isTryType(type)) {
            // Note: This is currently only intended for @Test cases
            if (value === "failure") {
                return failureObject();
            } else {
                if (value.substr(0, 8) !== "success(" || value.charAt(value.length - 1) !== ")") {
                    throw new Error(`Try literal format error; was ${value}`);
                }
                const innerType = type.Try;
                const innerValue = value.substr(8, value.length - 9);
                const innerObject = this.evaluateLiteral(innerType, innerValue);
                return successObject(innerObject);
            }
        } else {
            // Remainder of cases should be named types
            if (isNamedType(type)) {
                const name = type.name;

                // Handle strings
                if (name === "Unicode.String") {
                    return stringObject(value);
                }

                // Struct with only one element? Try that
                const literalTypeChain = this.getStructSingleElementChain(name);
                if (literalTypeChain !== undefined) {
                    // Get the literal from the basic type
                    const literalType = literalTypeChain.literalType;
                    let curValue = this.evaluateLiteral(literalType, value);
                    for (const structToApply of literalTypeChain.structChain) {
                        const wrapped = structObject(structToApply, [curValue]);
                        // We don't yet have checks for this at compile-time, so check at runtime instead
                        if (structToApply.requires !== undefined) {
                            const proposedValues: BoundVars = {};
                            proposedValues[structToApply.members[0].name] = curValue;
                            const requiresValue = this.evaluateBlock(structToApply.requires, proposedValues);
                            if (requiresValue.type !== "Boolean") {
                                throw new Error(`Non-boolean value returned from a 'requires' block`);
                            }
                            const isSatisfied = requiresValue.value;
                            if (!isSatisfied) {
                                throw new Error(`Invalid literal value ${value} for struct type ${type} with literal type ${literalType}`);
                            }
                        }
                        curValue = wrapped;
                    }
                    return curValue;
                }
            }
            throw new Error(`TODO: Implement case for type ${JSON.stringify(type)}`);
        }
    }

    private getStructSingleElementChain(structName: string): LiteralTypeChain | undefined {
        // TODO: Avoid arms-length recursion here
        const initialStruct = this.getStructByName(structName);
        if (initialStruct === undefined) {
            return undefined;
        }
        const typesSeen = [] as Struct[];
        let curStruct = initialStruct;
        while (true) {
            typesSeen.push(curStruct);
            if (curStruct.members.length !== 1) {
                return undefined;
            }
            const theMember = curStruct.members[0];
            const innerType = theMember.type;
            if (isNativeLiteralType(innerType)) {
                return {
                    literalType: innerType,
                    structChain: typesSeen.reverse()
                };
            } else if (isNamedType(innerType)) {
                const innerTypeStruct = this.getStructByName(innerType.name);
                if (innerTypeStruct === undefined) {
                    return undefined;
                }
                curStruct = innerTypeStruct;
            } else {
                return undefined;
            }
        }
    }

    private getStructByName(structName: string): Struct | undefined {
        return NativeStructs[structName] || this.module.structs[structName];
    }
}

interface LiteralTypeChain {
    literalType: Type;
    // Note: The innermost struct (the one that contains the literal type directly) is first in the list.
    // The type we will end up creating is last in the list.
    structChain: Struct[];
}

function isNativeLiteralType(type: Type) {
    if (type === "Integer" || type === "Natural" || type === "Boolean") {
        return true;
    }
    if (isNamedType(type)) {
        if (type.name === "Unicode.String") {
            return true;
        }
    }
    return false;
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
