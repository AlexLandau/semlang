import "jest";
import * as fs from "fs";
import { runTests, toModule } from "../index";

const allFiles = [] as string[];

const nativeCorpusRoot = "../../semlang-corpus";
if (!fs.existsSync(nativeCorpusRoot)) {
    throw new Error(`Couldn't find semlang-corpus in the expected spot; probably running from the wrong directory, cwd is: ${fs.realpathSync(".")}`);
}
const libraryCorpusRoot = "../../semlang-library-corpus";
if (!fs.existsSync(libraryCorpusRoot)) {
    throw new Error(`Couldn't find semlang-library-corpus in the expected spot; probably running from the wrong directory, cwd is: ${fs.realpathSync(".")}`);
}

for (const rootDir of [nativeCorpusRoot, libraryCorpusRoot]) {
    const translationsDir = rootDir + "/build/translations/json";
    if (!fs.existsSync(translationsDir)) {
        throw new Error(`The JSON translations directory ${translationsDir} doesn't exist yet`);
    }
    const nativeCorpusFilenames = fs.readdirSync(translationsDir);
    allFiles.push(...nativeCorpusFilenames.map(filename => translationsDir + "/" + filename));
}

for (const file of allFiles) {
    test('Interpreter test for ' + file, () => {
        const jsonString = fs.readFileSync(file, 'utf8');
        const context = JSON.parse(jsonString);
        // Unfortunately, "module" is already a thing...
        const theModule = toModule(context);
        
        // TODO: Maybe also check for case where there are no tests found?
        const errorMessages = runTests(theModule);
        if (errorMessages.length > 0) {
            console.log("Errors were found:");
            errorMessages.forEach((message) => {
                console.log(message);
            });
        }
        expect(errorMessages).toHaveLength(0);
    });
}
