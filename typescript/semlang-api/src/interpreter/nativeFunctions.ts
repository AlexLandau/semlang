import * as UtfString from "utfstring";
import { SemObject, integerObject, booleanObject, naturalObject, listObject, failureObject, successObject } from "./SemObject";
import { Struct } from "../api/language";
import { InterpreterContext } from "./interpret";


// TODO: Would be nice to simplify further by offering these as parseable, or linked into sem0...
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
    "Natural.equals": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value === right.value);
    },
    "Natural.greaterThan": (context: InterpreterContext, left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value > right.value);
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


