
// TODO: ...
export type SemObject = SemObject.Integer | SemObject.Boolean;
export namespace SemObject {
    export interface Integer {
        type: "Integer";
        value: number; // TODO: Fix this
    }
    export interface Boolean {
        type: "Boolean";
        value: boolean;
    }
}
