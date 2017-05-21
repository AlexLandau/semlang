import com.squareup.javapoet.ClassName
import semlang.api.ValidatedContext
import java.io.File
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.MethodSpec
import javax.lang.model.element.Modifier


data class WrittenJavaInfo(val testClassNames: List<String>)

fun writeJavaSourceIntoFolders(context: ValidatedContext, newSrcDir: File, newTestSrcDir: File): WrittenJavaInfo {
    val main = MethodSpec.methodBuilder("main")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Void.TYPE)
            .addParameter(Array<String>::class.java, "args")
            .addStatement("\$T.out.println(\$S)", System::class.java, "Hello, JavaPoet!")
            .build()

    val helloWorld = TypeSpec.classBuilder("HelloWorld")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(main)
            .build()

    val javaFile = JavaFile.builder("com.example.helloworld", helloWorld)
            .build()

    javaFile.writeTo(newSrcDir)

    // Write a JUnit test file
    writeJUnitTest(newTestSrcDir)

    return WrittenJavaInfo(listOf("com.example.test.TestClass"))
}

private fun writeJUnitTest(newTestSrcDir: File) {
    val runFakeTest = MethodSpec.methodBuilder("runFakeTest")
            .addModifiers(Modifier.PUBLIC)
            .returns(Void.TYPE)
            .addAnnotation(ClassName.bestGuess("org.junit.Test"))
            .addStatement("\$T.out.println(\$S)", System::class.java, "I am a fake test!")
            .build()

    val testClass = TypeSpec.classBuilder("TestClass")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(runFakeTest)
            .build()

    val javaFile = JavaFile.builder("com.example.test", testClass).build()
    javaFile.writeTo(newTestSrcDir)
}
