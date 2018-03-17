import * as bigInt from "big-integer";
import { BigInteger } from "big-integer";
import * as UtfString from "utfstring";
import { SemObject, integerObject, booleanObject, naturalObject, listObject, failureObject, successObject, instanceObject, structObject, isFunctionBinding, namedBindingObject, stringObject, listBuilderObject } from "./SemObject";
import { Struct, Type, Interface, isTryType } from "../api/language";
import { InterpreterContext } from "./interpret";

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
    "Unicode.CodePoint": {
        id: "Unicode.CodePoint",
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
    "BasicSequence": {
        id: "BasicSequence",
        typeParameters: ['T'],
        members: [
            { name: "base", type: typeT },
            { name: "successor", type: { from: [typeT], to: typeT } },
        ],
    },
};

export const NativeInterfaces: { [interfaceName: string]: Interface } = {
    "Sequence": {
        id: "Sequence",
        typeParameters: ['T'],
        methods: [
            { name: "get", arguments: [{name: "index", type: {name: "Natural"}}], returnType: typeT },
            { name: "first", arguments: [{name: "condition", type: { from: [typeT], to: "Boolean" }}], returnType: typeT },
        ],
    },
};

export const NativeFunctions: { [functionName: string]: Function } = {
    "BasicSequence.first": (context: InterpreterContext, basicSequence: SemObject.Struct, predicate: SemObject.FunctionBinding): SemObject => {
        const base = basicSequence.members[0];
        const successor = basicSequence.members[1];
        if (!isFunctionBinding(successor)) {
            throw new Error(`Expected a BasicSequence successor to be a function binding`);
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
    "BasicSequence.get": (context: InterpreterContext, basicSequence: SemObject.Struct, index: SemObject.Natural): SemObject => {
        const base = basicSequence.members[0];
        const successor = basicSequence.members[1];
        if (!isFunctionBinding(successor)) {
            throw new Error(`Expected a BasicSequence successor to be a function binding`);
        }
        let curValue: SemObject = base;
        for (let i = bigInt.zero; index.value.gt(i); i = i.next()) {
            curValue = context.evaluateBoundFunction(successor, [curValue]);
        }
        return curValue;
    },
    "Boolean.and": (context: InterpreterContext, a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value && b.value);
    },
    "Boolean.not": (context: InterpreterContext, a: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(!a.value);
    },
    "Boolean.or": (context: InterpreterContext, a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value || b.value);
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
    "Integer.dividedBy": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Try => {
        if (right.value.equals(0)) {
            return failureObject();
        }
        return successObject(integerObject(left.value.divide(right.value)));
    },
    "Integer.modulo": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Try => {
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
    "List.appendFront": (context: InterpreterContext, newElem: SemObject, list: SemObject.List): SemObject.List => {
        return listObject([newElem].concat(list.contents));
    },
    "List.concatenate": (context: InterpreterContext, left: SemObject.List, right: SemObject.List): SemObject.List => {
        return listObject(left.contents.concat(right.contents));
    },
    "List.get": (context: InterpreterContext, list: SemObject.List, index: SemObject.Natural): SemObject.Try => {
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
    "List.subList": (context: InterpreterContext, list: SemObject.List, start: SemObject.Natural, end: SemObject.Natural): SemObject.Try => {
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
    "ListBuilder.build": (context: InterpreterContext, builder: SemObject.ListBuilder): SemObject.List => {
        return listObject(builder.contents);
    },
    "ListBuilder.create": (context: InterpreterContext): SemObject.ListBuilder => {
        return listBuilderObject([]);
    },
    "Natural": (context: InterpreterContext, integer: SemObject.Integer): SemObject.Try => {
        const value = integer.value;
        if (value.isNegative()) {
            return failureObject();
        } else {
            return successObject(naturalObject(value));
        }
    },
    "Sequence.create": (context: InterpreterContext, base: SemObject, successor: SemObject.FunctionBinding): SemObject => {
        const sequenceInterface = NativeInterfaces["Sequence"];
        const dataObject = structObject(NativeStructs["BasicSequence"], [
            base,
            successor
        ]);

        const boundMethods = [
            namedBindingObject("BasicSequence.get", [dataObject, undefined]),
            namedBindingObject("BasicSequence.first", [dataObject, undefined]),
        ];

        return instanceObject(sequenceInterface, boundMethods);
    },
    "Try.assume": (context: InterpreterContext, theTry: SemObject.Try): SemObject => {
        if (theTry.type === "Try.Success") {
            return theTry.value;
        } else if (theTry.type === "Try.Failure") {
            throw new Error(`A Try.assume() call failed`);
        } else {
            throw new Error(`Type error in Try.assume: Got ${JSON.stringify(theTry)} instead of a Try`);
        }
    },
    "Try.failure": (context: InterpreterContext): SemObject.Try.Failure => {
        return failureObject();
    },
    "Try.flatMap": (context: InterpreterContext, theTry: SemObject.Try, fn: SemObject.FunctionBinding): SemObject.Try => {
        if (theTry.type === "Try.Success") {
            const value = theTry.value;
            const result = context.evaluateBoundFunction(fn, [value]) as SemObject.Try;
            return result;
        } else {
            return theTry;
        }
    },
    "Try.isSuccess": (context: InterpreterContext, theTry: SemObject.Try): SemObject.Boolean => {
        const isSuccess = theTry.type === "Try.Success"
        return booleanObject(isSuccess);
    },
    "Try.map": (context: InterpreterContext, theTry: SemObject.Try, fn: SemObject.FunctionBinding): SemObject.Try => {
        if (theTry.type === "Try.Success") {
            const value = theTry.value;
            const result = context.evaluateBoundFunction(fn, [value]);
            return successObject(result);
        } else {
            return theTry;
        }
    },
    "Try.orElse": (context: InterpreterContext, theTry: SemObject.Try, alternative: SemObject): SemObject => {
        if (theTry.type === "Try.Success") {
            return theTry.value;
        } else {
            return alternative;
        }
    },
    "Try.success": (context: InterpreterContext, object: SemObject): SemObject.Try.Success => {
        return successObject(object);
    },
    "Unicode.String": (context: InterpreterContext, codePointObjects: SemObject.List): SemObject.String => {
        const codePoints = codePointObjects.contents.map(codePointStruct => {
            const codePointNatural = (codePointStruct as SemObject.Struct).members[0] as SemObject.Natural
            return codePointNatural.value.toJSNumber()
        });
        const string = UtfString.codePointsToString(codePoints)
        return stringObject(string);
    },
    "Unicode.String.length": (context: InterpreterContext, string: SemObject.String): SemObject.Natural => {
        const length = UtfString.length(string.value);
        return naturalObject(bigInt(length));
    },
}

function bit(value: BigInteger): SemObject.Struct {
    if (!value.isZero() && value.notEquals(bigInt.one)) {
        throw new Error(`Unexpected bit value: ${value}`);
    }
    const natural = naturalObject(value);
    return structObject(NativeStructs["Bit"], [natural]);
}
