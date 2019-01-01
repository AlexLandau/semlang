import * as bigInt from "big-integer";
import { BigInteger } from "big-integer";
import * as UtfString from "utfstring";
import { SemObject, integerObject, booleanObject, naturalObject, listObject, failureObject, successObject, instanceObject, structObject, isFunctionBinding, namedBindingObject, stringObject, listBuilderObject } from "./SemObject";
import { Struct, Type, Interface, isMaybeType } from "../api/language";
import { InterpreterContext } from "./interpret";
import { assertNever } from "./util";

const typeT: Type.NamedType = { name: "T" };
export const NativeStructs: { [structName: string]: Struct } = {
    "Bit": {
        id: "Bit",
        members: [{name: "natural", type: {name: "Natural"}}],
        requires: [
            {
                return: {
                    type: "namedCall",
                    function: "Boolean.or",
                    chosenParameters: [],
                    arguments: [
                        {
                            type: "namedCall",
                            function: "Integer.equals",
                            chosenParameters: [],
                            arguments: [
                                { type: "follow", expression: { type: "var", var: "natural" }, name: "integer" },
                                { type: "literal", literalType: "Integer", value: "0" }
                            ],
                        },
                        {
                            type: "namedCall",
                            function: "Integer.equals",
                            chosenParameters: [],
                            arguments: [
                                { type: "follow", expression: { type: "var", var: "natural" }, name: "integer" },
                                { type: "literal", literalType: "Integer", value: "1" }
                            ],
                        },
                    ],
                }
            }
        ],
    },
    "BitsBigEndian": {
        id: "BitsBigEndian",
        members: [{name: "bits", type: {List: {name: "Bit"}}}],
    },
    "CodePoint": {
        id: "CodePoint",
        members: [{ name: "natural", type: {name: "Natural"} }],
        requires: [
            {
                return: {
                    type: "namedCall",
                    function: "Integer.lessThan",
                    chosenParameters: [],
                    arguments: [
                        { type: "follow", expression: { type: "var", var: "natural" }, name: "integer" },
                        { type: "literal", literalType: "Integer", value: "1114112" }
                    ],
                }
            }
        ],
    },
    "Sequence": {
        id: "Sequence",
        typeParameters: ['T'],
        members: [
            { name: "base", type: typeT },
            { name: "successor", type: { from: [typeT], to: typeT } },
        ],
    },
};

export const NativeInterfaces: { [interfaceName: string]: Interface } = {
};

export const NativeFunctions: { [functionName: string]: Function } = {
    "Boolean.and": (context: InterpreterContext, a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value && b.value);
    },
    "Boolean.not": (context: InterpreterContext, a: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(!a.value);
    },
    "Boolean.or": (context: InterpreterContext, a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value || b.value);
    },
    "Data.equals": (context: InterpreterContext, left: SemObject, right: SemObject): SemObject.Boolean => {
        return booleanObject(dataEquals(left, right));
    },
    "Integer.equals": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Boolean => {
        return booleanObject(left.value.equals(right.value));
    },
    "Integer.fromNatural": (context: InterpreterContext, natural: SemObject.Natural): SemObject.Integer => {
        return integerObject(natural.value);
    },
    "Integer.greaterThan": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Boolean => {
        return booleanObject(left.value.greater(right.value));
    },
    "Integer.lessThan": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Boolean => {
        return booleanObject(left.value.lesser(right.value));
    },
    "Integer.minus": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value.minus(right.value));
    },
    "Integer.plus": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value.plus(right.value));
    },
    "Integer.sum": (context: InterpreterContext, list: SemObject.List): SemObject.Integer => {
        let sum = bigInt.zero;
        for (const intObject of list.contents) {
            if (intObject.type !== "Integer") {
                throw new Error(`List not of integers passed to Integer.sum`);
            }
            sum = sum.plus(intObject.value);
        }
        return integerObject(sum);
    },
    "Integer.times": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value.times(right.value));
    },
    "Integer.dividedBy": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Maybe => {
        if (right.value.equals(0)) {
            return failureObject();
        }
        return successObject(integerObject(left.value.divide(right.value)));
    },
    "Integer.modulo": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Maybe => {
        if (right.value.lesser(1)) {
            return failureObject();
        }
        const moduloValue = left.value.mod(right.value);
        // We expect the modulo value to be positive, but the BigInteger library returns a value with the same sign as the quotient
        const fixedModuloValue = moduloValue.lesser(0) ? right.value.plus(moduloValue) : moduloValue;
        if (fixedModuloValue.lesser(0) || fixedModuloValue.greaterOrEquals(right.value)) {
            throw new Error(`Assumption in Integer.modulo failed; inputs were ${JSON.stringify(left)}, ${JSON.stringify(right)}, JS modulo value was ${moduloValue}, and fixed modulo value was ${fixedModuloValue}`);
        }
        return successObject(integerObject(fixedModuloValue));
    },
    "List.append": (context: InterpreterContext, list: SemObject.List, newElem: SemObject): SemObject.List => {
        return listObject(list.contents.concat(newElem));
    },
    "List.appendFront": (context: InterpreterContext, list: SemObject.List, newElem: SemObject): SemObject.List => {
        return listObject([newElem].concat(list.contents));
    },
    "List.concatenate": (context: InterpreterContext, left: SemObject.List, right: SemObject.List): SemObject.List => {
        return listObject(left.contents.concat(right.contents));
    },
    "List.flatMap": (context: InterpreterContext, list: SemObject.List, fn: SemObject.FunctionBinding): SemObject.List => {
        const mappedToIndividualLists = list.contents.map(item => (context.evaluateBoundFunction(fn, [item]) as SemObject.List).contents);
        const flattened = ([] as SemObject[]).concat(...mappedToIndividualLists);
        return listObject(flattened);
    },
    "List.get": (context: InterpreterContext, list: SemObject.List, index: SemObject.Natural): SemObject.Maybe => {
        const i = index.value;
        if (i.lt(list.contents.length)) {
            // TODO: We lose precision here. Any way to stop that, or crash on that?
            return successObject(list.contents[i.toJSNumber()]);
        } else {
            return failureObject();
        }
    },
    "List.lastN": (context: InterpreterContext, list: SemObject.List, n: SemObject.Natural): SemObject.List => {
        const startIndex = list.contents.length - n.value.toJSNumber();
        return listObject(list.contents.slice(startIndex));
    },
    "List.map": (context: InterpreterContext, list: SemObject.List, fn: SemObject.FunctionBinding): SemObject.List => {
        const mapped = list.contents.map(item => context.evaluateBoundFunction(fn, [item]));
        return listObject(mapped);
    },
    "List.reduce": (context: InterpreterContext, list: SemObject.List, initialValue: SemObject, reducer: SemObject.FunctionBinding): SemObject => {
        let value = initialValue;
        for (const item of list.contents) {
            value = context.evaluateBoundFunction(reducer, [value, item]);
        }
        return value;
    },
    "List.size": (context: InterpreterContext, list: SemObject.List): SemObject.Natural => {
        return naturalObject(bigInt(list.contents.length));
    },
    "List.subList": (context: InterpreterContext, list: SemObject.List, start: SemObject.Natural, end: SemObject.Natural): SemObject.Maybe => {
        const startInt = start.value.toJSNumber();
        const endInt = end.value.toJSNumber();
        if (startInt > endInt || endInt > list.contents.length) {
            return failureObject();
        }
        return successObject(listObject(list.contents.slice(startInt, endInt)));
    },
    "ListBuilder.append": (context: InterpreterContext, builder: SemObject.ListBuilder, item: SemObject): SemObject.ListBuilder => {
        builder.contents.push(item);
        return builder;
    },
    "ListBuilder.appendAll": (context: InterpreterContext, builder: SemObject.ListBuilder, items: SemObject.List): SemObject.ListBuilder => {
        for (const item of items.contents) {
            builder.contents.push(item);
        }
        return builder;
    },
    "ListBuilder.build": (context: InterpreterContext, builder: SemObject.ListBuilder): SemObject.List => {
        return listObject(builder.contents);
    },
    "ListBuilder.create": (context: InterpreterContext): SemObject.ListBuilder => {
        return listBuilderObject([]);
    },
    "Natural": (context: InterpreterContext, integer: SemObject.Integer): SemObject.Maybe => {
        const value = integer.value;
        if (value.isNegative()) {
            return failureObject();
        } else {
            return successObject(naturalObject(value));
        }
    },
    "Sequence.first": (context: InterpreterContext, sequence: SemObject.Struct, predicate: SemObject.FunctionBinding): SemObject => {
        const base = sequence.members[0];
        const successor = sequence.members[1];
        if (!isFunctionBinding(successor)) {
            throw new Error(`Expected a Sequence successor to be a function binding`);
        }
        let curValue: SemObject = base;
        while (true) {
            const isSatisfying = context.evaluateBoundFunction(predicate, [curValue]);
            if (isSatisfying.type !== "Boolean") {
                throw new Error(`Expected boolean output from a Sequence.first predicate, but was ${JSON.stringify(isSatisfying)}`);
            }
            if (isSatisfying.value) {
                return curValue;
            } else {
                curValue = context.evaluateBoundFunction(successor, [curValue]);
            }
        }
    },
    "Sequence.get": (context: InterpreterContext, sequence: SemObject.Struct, index: SemObject.Natural): SemObject => {
        const base = sequence.members[0];
        const successor = sequence.members[1];
        if (!isFunctionBinding(successor)) {
            throw new Error(`Expected a Sequence successor to be a function binding`);
        }
        let curValue: SemObject = base;
        for (let i = bigInt.zero; index.value.gt(i); i = i.next()) {
            curValue = context.evaluateBoundFunction(successor, [curValue]);
        }
        return curValue;
    },
    "Maybe.assume": (context: InterpreterContext, theMaybe: SemObject.Maybe): SemObject => {
        if (theMaybe.type === "Maybe.Success") {
            return theMaybe.value;
        } else if (theMaybe.type === "Maybe.Failure") {
            throw new Error(`A Maybe.assume() call failed`);
        } else {
            throw new Error(`Type error in Maybe.assume: Got ${JSON.stringify(theMaybe)} instead of a Maybe`);
        }
    },
    "Maybe.failure": (context: InterpreterContext): SemObject.Maybe.Failure => {
        return failureObject();
    },
    "Maybe.flatMap": (context: InterpreterContext, theMaybe: SemObject.Maybe, fn: SemObject.FunctionBinding): SemObject.Maybe => {
        if (theMaybe.type === "Maybe.Success") {
            const value = theMaybe.value;
            const result = context.evaluateBoundFunction(fn, [value]) as SemObject.Maybe;
            return result;
        } else {
            return theMaybe;
        }
    },
    "Maybe.isSuccess": (context: InterpreterContext, theMaybe: SemObject.Maybe): SemObject.Boolean => {
        const isSuccess = theMaybe.type === "Maybe.Success"
        return booleanObject(isSuccess);
    },
    "Maybe.map": (context: InterpreterContext, theMaybe: SemObject.Maybe, fn: SemObject.FunctionBinding): SemObject.Maybe => {
        if (theMaybe.type === "Maybe.Success") {
            const value = theMaybe.value;
            const result = context.evaluateBoundFunction(fn, [value]);
            return successObject(result);
        } else {
            return theMaybe;
        }
    },
    "Maybe.orElse": (context: InterpreterContext, theMaybe: SemObject.Maybe, alternative: SemObject): SemObject => {
        if (theMaybe.type === "Maybe.Success") {
            return theMaybe.value;
        } else {
            return alternative;
        }
    },
    "Maybe.success": (context: InterpreterContext, object: SemObject): SemObject.Maybe.Success => {
        return successObject(object);
    },
    "String": (context: InterpreterContext, codePointObjects: SemObject.List): SemObject.String => {
        const codePoints = codePointObjects.contents.map(codePointStruct => {
            const codePointNatural = (codePointStruct as SemObject.Struct).members[0] as SemObject.Natural
            return codePointNatural.value.toJSNumber()
        });
        const string = UtfString.codePointsToString(codePoints)
        return stringObject(string);
    },
    "String.length": (context: InterpreterContext, string: SemObject.String): SemObject.Natural => {
        const length = UtfString.length(string.value);
        return naturalObject(bigInt(length));
    },
}

function dataEquals(left: SemObject, right: SemObject): boolean {
    if (left.type === "struct") {
        if (right.type !== "struct") throw new Error();
        if (left.struct.id !== right.struct.id) throw new Error();
        return dataArrayEquals(left.members, right.members);
    } else if (left.type === "Integer") {
        if (right.type !== "Integer") throw new Error();
        return left.value.equals(right.value);
    } else if (left.type === "Natural") {
        if (right.type !== "Natural") throw new Error();
        return left.value.equals(right.value);
    } else if (left.type === "List") {
        if (right.type !== "List") throw new Error();
        return dataArrayEquals(left.contents, right.contents);
    } else if (left.type === "String") {
        if (right.type !== "String") throw new Error();
        return left.value === right.value;
    } else if (left.type === "Boolean") {
        if (right.type !== "Boolean") throw new Error();
        return left.value === right.value;
    } else if (left.type === "Maybe.Success" || left.type === "Maybe.Failure") {
        if (right.type !== "Maybe.Success" && right.type !== "Maybe.Failure") throw new Error();
        return (left.type === "Maybe.Failure" && right.type === "Maybe.Failure") ||
               (left.type === "Maybe.Success" && right.type === "Maybe.Success" && dataEquals(left.value, right.value));
    } else if (left.type === "union") {
        if (right.type !== "union") throw new Error();
        if (left.optionIndex !== right.optionIndex) {
            return false;
        }
        if (left.object === undefined) {
            return right.object === undefined;
        }
        if (right.object === undefined) {
            return false;
        }
        return dataEquals(left.object, right.object);
    } else if (isFunctionBinding(left)) {
        throw new Error();
    } else if (left.type === "instance") {
        throw new Error();
    } else if (left.type === "ListBuilder") {
        throw new Error();
    } else {
        throw assertNever(left);
    }
}

function dataArrayEquals(left: SemObject[], right: SemObject[]): boolean {
    if (left.length !== right.length) {
        return false;
    }
    for (let i = 0; i < left.length; i++) {
        if (!dataEquals(left[i], right[i])) {
            return false;
        }
    }
    return true;
}

function bit(value: BigInteger): SemObject.Struct {
    if (!value.isZero() && value.notEquals(bigInt.one)) {
        throw new Error(`Unexpected bit value: ${value}`);
    }
    const natural = naturalObject(value);
    return structObject(NativeStructs["Bit"], [natural]);
}
