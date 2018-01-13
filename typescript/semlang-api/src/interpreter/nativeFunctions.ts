import { SemObject, integerObject, booleanObject, naturalObject, listObject, failureObject, successObject } from "./SemObject";


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
    "Integer.equals": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Boolean => {
        return booleanObject(left.value === right.value);
    },
    "Integer.fromNatural": (natural: SemObject.Natural): SemObject.Integer => {
        return integerObject(natural.value);
    },
    "Integer.minus": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value - right.value);
    },
    "Integer.plus": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value + right.value);
    },
    "Integer.times": (left: SemObject.Integer, right: SemObject.Integer): SemObject.Integer => {
        return integerObject(left.value * right.value);
    },
    "List.append": (list: SemObject.List, newElem: SemObject): SemObject.List => {
        return listObject(list.contents.concat(newElem));
    },
    "List.get": (list: SemObject.List, index: SemObject.Natural): SemObject.Try => {
        const i = index.value;
        if (i < list.contents.length) {
            return successObject(list.contents[i]);
        } else {
            return failureObject();
        }
    },
    "List.size": (list: SemObject.List): SemObject.Natural => {
        return naturalObject(list.contents.length);
    },
    "Natural.equals": (left: SemObject.Natural, right: SemObject.Natural): SemObject.Boolean => {
        return booleanObject(left.value === right.value);
    },
    "Natural.plus": (left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        return naturalObject(left.value + right.value);
    },
    "Natural.times": (left: SemObject.Natural, right: SemObject.Natural): SemObject.Natural => {
        return naturalObject(left.value * right.value);
    },
    "Try.assume": (theTry: SemObject.Try): SemObject => {
        if (theTry.tryType === "success") {
            return theTry.value;
        } else {
            throw new Error(`A Try.assume() call failed`);
        }
    },
    "Try.failure": (): SemObject.Try.Failure => {
        return failureObject();
    },
}
