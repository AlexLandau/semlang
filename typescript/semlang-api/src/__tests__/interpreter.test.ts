import "jest";
import * as fs from "fs";
import { runTests, toModule } from "../index";

const corpusRoot = "../../semlang-corpus";
if (!fs.existsSync(corpusRoot)) {
    throw new Error(`Couldn't find semlang-corpus in the expected spot; probably running from the wrong directory, cwd is: ${fs.realpathSync(".")}`);
}
const translationsDir = corpusRoot + "/build/translations/json";
if (!fs.existsSync(translationsDir)) {
    throw new Error(`The JSON translations directory doesn't exist yet`);
}
const filenames = fs.readdirSync(translationsDir);

for (const filename of filenames) {
    const file = translationsDir + "/" + filename;
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
        expect(errorMessages.length).toBe(0);
    });
}
