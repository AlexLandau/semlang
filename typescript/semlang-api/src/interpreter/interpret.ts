import * as bigInt from "big-integer";
import * as UtfString from "utfstring";
import { Module, Block, Expression, Type, isNamedType, Struct, isAssignment, isBareStatement } from "../api/language";
import { SemObject, listObject, booleanObject, integerObject, naturalObject, failureObject, successObject, structObject, stringObject, isFunctionBinding, namedBindingObject, inlineBindingObject, unionObject } from "./SemObject";
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

function getBoundVarsForArgs(argumentNames: string[], inputs: SemObject[]): BoundVars {
    const boundArguments: BoundVars = {};
    if (argumentNames.length !== inputs.length) {
        throw new Error(`Mismatch between argument names ${argumentNames} and inputs ${inputs}`);
    }
    argumentNames.forEach((argumentName, index) => {
        const inputForArg = inputs[index];
        // TODO: Might want to do some type checking here
        if (inputForArg == undefined) {
            throw new Error(`Undefined input`);
        }
        boundArguments[argumentName] = inputForArg;
    });
    return boundArguments;
}

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
            const argumentNames = theFunction.arguments.map(argument => argument.name);
            const boundArguments = getBoundVarsForArgs(argumentNames, args);
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

        const theUnionOption = this.module.unionsByOptionId[functionName];
        if (theUnionOption !== undefined) {
            const optionIndex = theUnionOption[1];
            const innerObject = (args.length === 1) ? args[0] : undefined;
            return unionObject(optionIndex, innerObject);
        }

        const theUnionWhen = this.module.unionsByWhenId[functionName];
        if (theUnionWhen !== undefined) {
            const theUnionObject = args[0];
            if (theUnionObject.type !== "union") {
                throw new Error();
            }
            const optionIndex = theUnionObject.optionIndex;
            const theBindingToUse = args[optionIndex + 1];
            if (!isFunctionBinding(theBindingToUse)) {
                throw new Error();
            }
            const optionDataArgs = theUnionObject.object !== undefined ? [theUnionObject.object] : []
            return this.evaluateBoundFunction(theBindingToUse, optionDataArgs);
        }

        throw new Error(`Couldn't find the function ${functionName}`);
    }

    evaluateBoundFunction(functionBinding: SemObject.FunctionBinding, args: SemObject[]): SemObject {
        if (functionBinding.type === "namedBinding") {
            return this.evaluateNamedBinding(functionBinding, args);
        } else if (functionBinding.type === "inlineBinding") {
            return this.evaluateInlineBinding(functionBinding, args);
        } else {
            throw assertNever(functionBinding);
        }
    }
    
    private evaluateNamedBinding(functionBinding: SemObject.NamedFunctionBinding, args: SemObject[]): SemObject {
        const fullyBoundArguments = combineBindings(functionBinding, args) as SemObject[]; // These should all be defined by now
        const functionId = functionBinding.functionId;
        return this.evaluateFunctionCall(functionId, fullyBoundArguments);
    }

    private evaluateInlineBinding(functionBinding: SemObject.InlineFunctionBinding, args: SemObject[]): SemObject {
        const fullyBoundArguments = combineBindings(functionBinding, args) as SemObject[]; // These should all be defined by now
        for (const argument of fullyBoundArguments) {
            if (argument == null) {
                throw new Error(`We should have fully bound arguments going into this inline binding, but we didn't`);
            }
        }
        const boundVars = getBoundVarsForArgs(functionBinding.argumentNames, fullyBoundArguments);
        return this.evaluateBlock(functionBinding.block, boundVars);
    }

    private evaluateBlock(block: Block, alreadyBoundVars: BoundVars): SemObject {
        let lastEvaluatedExpression: SemObject | undefined = undefined;
        for (const statement of block) {
            if (isAssignment(statement)) {
                const varName = statement.let;
                const expression = statement.be;
    
                const evaluatedExpression = this.evaluateExpression(expression, alreadyBoundVars);
                if (evaluatedExpression == undefined) {
                    throw new Error(`Evaluated expression was undefined; expression was: ${JSON.stringify(expression)}`);
                }
                alreadyBoundVars[varName] = evaluatedExpression;
                lastEvaluatedExpression = undefined;
            } else if (isBareStatement(statement)) {
                const expression = statement.do;
    
                const evaluatedExpression = this.evaluateExpression(expression, alreadyBoundVars);
                if (evaluatedExpression == undefined) {
                    throw new Error(`Evaluated expression was undefined; expression was: ${JSON.stringify(expression)}`);
                }
                lastEvaluatedExpression = evaluatedExpression;
            } else {
                assertNever(statement);
            }
        }
        if (lastEvaluatedExpression === undefined) {
            throw new Error(`Malformed block: ${JSON.stringify(block)}`);
        }
        return lastEvaluatedExpression;
    }
    
    private evaluateExpression(expression: Expression, alreadyBoundVars: BoundVars): SemObject {
        if (expression.type === "var") {
            const varName = expression.var;
            const value = alreadyBoundVars[varName];
            if (value === undefined) {
                throw new Error(`Undefined variable ${varName}; alreadyBoundVar keys are: ${Object.keys(alreadyBoundVars)}`);
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
            if (!isFunctionBinding(functionBinding)) {
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
            } else if (structureObject.type === "Natural") {
                if (expression.name !== "integer") {
                    throw new Error(`Only component of a Natural is integer`);
                }

                return integerObject(structureObject.value);
            } else if (structureObject.type === "String") {
                if (expression.name !== "codePoints") {
                    throw new Error(`Only component of a String is codePoints`);
                }
                const stringLiteral = structureObject.value;

                const codePoints = UtfString.stringToCodePoints(stringLiteral);
                const codePointObjects = codePoints.map((codePoint: number) => {
                    const charCodeNatural = naturalObject(bigInt(codePoint));
                    const charCodeObject = structObject(NativeStructs["CodePoint"], [charCodeNatural]);
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

            return namedBindingObject(functionId, bindings);
        } else if (expression.type === "expressionBinding") {
            const functionExpression = expression.expression;
            const functionBinding = this.evaluateExpression(functionExpression, alreadyBoundVars);
            if (!isFunctionBinding(functionBinding)) {
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

            const newBindings = combineBindings(functionBinding, explicitBindings);
            return {...functionBinding, bindings: newBindings};
        } else if (expression.type === "inlineFunction") {
            return this.evaluateInlineFunctionExpression(expression, alreadyBoundVars);
        } else {
            throw assertNever(expression);
        }
    }

    private evaluateInlineFunctionExpression(expression: Expression.InlineFunction, alreadyBoundVars: BoundVars): SemObject {
        const block: Block = expression.body;

        const explicitArguments = expression.arguments.map(argument => argument.name);
        // Note: This includes explicit arguments, implicitly bound variables, and variables introduced by assignments within the block
        const varNamesReferencedInBlock = getVarNamesReferencedInBlock(block);

        const implicitArguments = varNamesReferencedInBlock.filter((varName) => varName in alreadyBoundVars);
        
        const args = explicitArguments.concat(implicitArguments);

        const bindings = [] as Array<SemObject | undefined>;
        // First, add the explicit arguments
        for (const explicitArgument of explicitArguments) {
            bindings.push(undefined);
        }
        // Then the implicit arguments
        for (const implicitArgument of implicitArguments) {
            const value = alreadyBoundVars[implicitArgument];
            bindings.push(value);
        }

        return inlineBindingObject(args, bindings, block);
    }

    evaluateLiteral(type: Type, value: string): SemObject {
        // Remainder of cases should be named types
        if (isNamedType(type)) {
            const name = type.name;

            // Handle booleans
            if (name === "Boolean") {
                if (value === "true") {
                    return booleanObject(true);
                } else if (value === "false") {
                    return booleanObject(false);
                }
                throw new Error(`Unexpected Boolean literal ${value}`);
            }

            // Handle integers
            if (name === "Integer") {
                return integerObject(bigInt(value));
            }

            // Handle naturals
            if (name === "Natural") {
                return naturalObject(bigInt(value));
            }

            // Handle strings
            if (name === "String") {
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

type DumbStringSet = { [varName: string]: boolean };

// Note: I don't guarantee consistent results from this method.
function getVarNamesReferencedInBlock(block: Block): string[] {
    // We use this as a set for quick duplicate checking
    const varNamesSet: DumbStringSet = {};

    addVarNamesReferencedInBlock(block, varNamesSet);

    const array = [] as string[];
    for (const varName in varNamesSet) {
        array.push(varName);
    }
    return array;
}

function addVarNamesReferencedInBlock(block: Block, varNamesSet: DumbStringSet) {
    for (const statement of block) {
        if (isAssignment(statement)) {
            addVarNamesReferencedInExpression(statement.be, varNamesSet);
        } else if (isBareStatement(statement)) {
            addVarNamesReferencedInExpression(statement.do, varNamesSet);
        } else {
            assertNever(statement);
        }
    }
}

function addVarNamesReferencedInExpression(expression: Expression, varNamesSet: DumbStringSet) {
    if (expression.type === "var") {
        varNamesSet[expression.var] = true;
    } else if (expression.type === "expressionBinding") {
        addVarNamesReferencedInExpression(expression.expression, varNamesSet);
        for (const binding of expression.bindings) {
            if (binding !== null) {
                addVarNamesReferencedInExpression(binding, varNamesSet);
            }
        }
    } else if (expression.type === "expressionCall") {
        addVarNamesReferencedInExpression(expression.expression, varNamesSet);
        for (const argument of expression.arguments) {
            addVarNamesReferencedInExpression(argument, varNamesSet);
        }
    } else if (expression.type === "follow") {
        addVarNamesReferencedInExpression(expression.expression, varNamesSet);
    } else if (expression.type === "ifThen") {
        addVarNamesReferencedInExpression(expression.if, varNamesSet);
        addVarNamesReferencedInBlock(expression.then, varNamesSet);
        addVarNamesReferencedInBlock(expression.else, varNamesSet);
    } else if (expression.type === "inlineFunction") {
        addVarNamesReferencedInBlock(expression.body, varNamesSet);
    } else if (expression.type === "list") {
        for (const item of expression.contents) {
            addVarNamesReferencedInExpression(item, varNamesSet);
        }
    } else if (expression.type === "literal") {
        // Do nothing
    } else if (expression.type === "namedBinding") {
        for (const binding of expression.bindings) {
            if (binding !== null) {
                addVarNamesReferencedInExpression(binding, varNamesSet);
            }
        }
    } else if (expression.type === "namedCall") {
        for (const argument of expression.arguments) {
            addVarNamesReferencedInExpression(argument, varNamesSet);
        }
    } else {
        throw assertNever(expression);
    }
}

interface LiteralTypeChain {
    literalType: Type;
    // Note: The innermost struct (the one that contains the literal type directly) is first in the list.
    // The type we will end up creating is last in the list.
    structChain: Struct[];
}

function isNativeLiteralType(type: Type) {
    if (isNamedType(type)) {
        if (type.name === "Boolean" ||
            type.name === "Integer" ||
            type.name === "Natural" ||
            type.name === "String") {
            return true;
        }
    }
    return false;
}

function combineBindings(originalBinding: SemObject.FunctionBinding, explicitBinding: Array<SemObject | undefined>): Array<SemObject | undefined> {
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
    return combinedBindings;
}

function replaceFirstUnboundBindingWith(originalBinding: SemObject.FunctionBinding, replacement: SemObject): SemObject.FunctionBinding {
    const newBindings = originalBinding.bindings.slice(); // Make a copy
    const firstUnboundIndex = newBindings.indexOf(undefined);
    if (firstUnboundIndex < 0) {
        throw new Error(`This was supposed to have an unbound index`);
    }
    newBindings[firstUnboundIndex] = replacement;
    return {...originalBinding, bindings: newBindings};
}
