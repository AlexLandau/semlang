import * as UtfString from "utfstring";
import { SemObject, integerObject, booleanObject, naturalObject, listObject, failureObject, successObject, instanceObject, structObject, bindingObject } from "./SemObject";
import { Struct, Type, Interface } from "../api/language";
import { InterpreterContext } from "./interpret";


// TODO: Would be nice to simplify further by offering these as parseable, or linked into sem0...
const typeT: Type.NamedType = { name: "T" };
export const NativeStructs: { [structName: string]: Struct } = {
    "Unicode.CodePoint": {
        id: "Unicode.CodePoint",
        members: [{ name: "natural", type: "Natural" }],
        requires: [
            {
                return: {
                    type: "namedCall",
                    function: "Natural.lessThan",
                    chosenParameters: [],
                    arguments: [
                        { type: "var", var: "natural" },
                        { type: "literal", literalType: "Natural", value: "1114112" }
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
            { name: "get", arguments: [{name: "index", type: "Natural"}], returnType: typeT },
            { name: "first", arguments: [{name: "condition", type: { from: [typeT], to: "Boolean" }}], returnType: typeT },
        ],
    },
};

export const NativeFunctions: { [functionName: string]: Function } = {
    "BasicSequence.first": (context: InterpreterContext, basicSequence: SemObject.Struct, predicate: SemObject.FunctionBinding): SemObject => {
        const base = basicSequence.members[0];
        const successor = basicSequence.members[1];
        if (successor.type !== "binding") {
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
        if (successor.type !== "binding") {
            throw new Error(`Expected a BasicSequence successor to be a function binding`);
        }
        let curValue: SemObject = base;
        for (let i = 0; i < index.value; i++) {
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
        return booleanObject(left.value === right.value);
    },
    "Integer.fromNatural": (context: InterpreterContext, natural: SemObject.Natural): SemObject.Integer => {
        return integerObject(natural.value);
    },
    "Integer.greaterThan": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Boolean => {
        return booleanObject(left.value > right.value);
    },
    "Integer.minus": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value - right.value);
    },
    "Integer.plus": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value + right.value);
    },
    "Integer.times": (context: InterpreterContext, left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value * right.value);
    },
    "List.append": (context: InterpreterContext, list: SemObject.List, newElem: SemObject): SemObject.List => {
        return listObject(list.contents.concat(newElem));
    },
    "List.get": (context: InterpreterContext, list: SemObject.List, index: SemObject.Natural): SemObject.Try => {
        const i = index.value;
        if (i < list.contents.length) {
            return successObject(list.contents[i]);
        } else {
            return failureObject();
        }
    },
    "List.size": (context: InterpreterContext, list: SemObject.List): SemObject.Natural => {
        return naturalObject(list.contents.length);
    },
    "Natural.absoluteDifference": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        const difference = left.value - right.value;
        return naturalObject((difference >= 0) ? difference : -difference);
    },
    "Natural.equals": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value === right.value);
    },
    "Natural.greaterThan": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value > right.value);
    },
    "Natural.lesser": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        const a = left.value;
        const b = right.value;
        return naturalObject((a < b) ? a : b);
    },
    "Natural.lessThan": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value < right.value);
    },
    "Natural.plus": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        return naturalObject(left.value + right.value);
    },
    "Natural.times": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        return naturalObject(left.value * right.value);
    },
    "Sequence.create": (context: InterpreterContext, base: SemObject, successor: SemObject.FunctionBinding): SemObject => {
        const sequenceInterface = NativeInterfaces["Sequence"];
        const dataObject = structObject(NativeStructs["BasicSequence"], [
            base,
            successor
        ]);

        const boundMethods = [
            bindingObject("BasicSequence.get", [dataObject, undefined]),
            bindingObject("BasicSequence.first", [dataObject, undefined]),
        ];

        return instanceObject(sequenceInterface, boundMethods);
    },
    "Try.assume": (context: InterpreterContext, theTry: SemObject.Try): SemObject => {
        if (theTry.type === "Try.Success") {
            return theTry.value;
        } else {
            throw new Error(`A Try.assume() call failed`);
        }
    },
    "Try.failure": (context: InterpreterContext): SemObject.Try.Failure => {
        return failureObject();
    },
    "Try.map": (context: InterpreterContext, theTry: SemObject.Try, fn: SemObject.FunctionBinding): SemObject.Try => {
        if (theTry.type === "Try.Success") {
            const value = theTry.value;
            // TODO: Need to be able to reach back in to call here...
            const result = context.evaluateBoundFunction(fn, [value]);
            return successObject(result);
        } else {
            return theTry;
        }
    },
    "Unicode.String.length": (context: InterpreterContext, string: SemObject.String): SemObject.Natural => {
        const length = UtfString.length(string.value);
        return naturalObject(length);
    },
}


