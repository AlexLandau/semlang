/**
 * Returns the index of the first member of the list that satisfies the given predicate,
 * or -1 otherwise.
 */
export function findIndex<T>(list: T[], predicate: (item: T) => boolean): number {
    for (let i = 0; i < list.length; i++) {
        const item = list[i];
        if (predicate(item)) {
            return i;
        }
    }
    return -1;
}

export function assertNever(never: never): never {
    throw new Error(`Expected never, but was ${never}`);
}
