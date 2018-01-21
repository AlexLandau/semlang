declare module "utfstring" {
    export function charAt(str: string, index: number): string;
    export function charCodeAt(str: string, index: number): number;
    export function fromCharCode(codepoint: number): string;
    export function indexOf(str: string, searchValue: string, start?: number): number;
    export function lastIndexOf(string: string, searchValue: string, start?: number): number;
    export function slice(str: string, start: number, finish: number): string;
    export function substr(str: string, start: number, length: number): string;
    export function length(str: string): number;
    export function stringToCodePoints(str: string): number[];
    export function codePointsToString(arr: number[]): string;
    export function stringToBytes(str: string): number[];
    export function bytesToString(arr: number[]): string;
    export function stringToCharArray(str: string): string[];
    export function findByteIndex(str: string, charIndex: number): number;
    export function findCharIndex(str: string, byteIndex: number): number;
}
