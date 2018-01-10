import { SemObject, integerObject, booleanObject, naturalObject, listObject } from "./SemObject";


export const NativeFunctions: { [functionName: string]: Function } = {
    "Boolean.and": (a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value && b.value);
    },
    "Boolean.not": (a: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(!a.value);
    },
    "Boolean.or": (a: SemObject.Boolean, b: SemObject.Boolean): SemObject.Boolean => {
        return booleanObject(a.value || b.value);
    },
    "Integer.plus": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value + right.value)
    },
    "Integer.times": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value * right.value)
    },
    "List.append": (list: SemObject.List, newElem: SemObject): SemObject.List => {
        return listObject(list.contents.concat(newElem));
    },
    "List.size": (list: SemObject.List): SemObject.Natural => {
        return naturalObject(list.contents.length);
    },
    "Natural.plus": (left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        return naturalObject(left.value + right.value)
    },
}
