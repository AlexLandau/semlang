package net.semlang.interpreter.test

import net.semlang.interpreter.ComplexLiteralNode
import net.semlang.interpreter.ComplexLiteralParsingResult
import net.semlang.interpreter.parseComplexLiteral
import org.junit.Assert
import org.junit.Test

class ComplexLiteralsPositiveTest {
    private fun squareList(vararg nodes: ComplexLiteralNode): ComplexLiteralNode.SquareList {
        return ComplexLiteralNode.SquareList(nodes.toList())
    }
    private fun literal(contents: String): ComplexLiteralNode.Literal {
        return ComplexLiteralNode.Literal(contents)
    }
    private fun check(actual: ComplexLiteralNode, expected: ComplexLiteralNode) {
        Assert.assertEquals(expected, actual)
    }

    @Test
    fun testSingletonSquareList() {
        val result = parseComplexLiteral("['abc']").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithTrailingComma() {
        val result = parseComplexLiteral("['abc',]").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment1() {
        val result = parseComplexLiteral("/* This is a comment */['abc']").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment2() {
        val result = parseComplexLiteral("/**/['abc']").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment3() {
        val result = parseComplexLiteral("[/**/'abc']").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment4() {
        val result = parseComplexLiteral("['abc'/**/]").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment5() {
        val result = parseComplexLiteral("['abc'/**/,]").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment6() {
        val result = parseComplexLiteral("['abc',/**/]").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testSingletonSquareListWithBlockComment7() {
        val result = parseComplexLiteral("['abc']/**/").assumeSuccess()
        check(result, squareList(literal("abc")))
    }

    @Test
    fun testEmptyStringSingletonSquareList() {
        val result = parseComplexLiteral("['']").assumeSuccess()
        check(result, squareList(literal("")))
    }

    @Test
    fun testEmptySquareList() {
        val result = parseComplexLiteral("[]").assumeSuccess()
        check(result, squareList())
    }

    @Test
    fun testNestedEmptySquareList() {
        val result = parseComplexLiteral("[[]]").assumeSuccess()
        check(result, squareList(squareList()))
    }

    @Test
    fun testNestedEmptySquareListWithTrailingComma() {
        val result = parseComplexLiteral("[[],]").assumeSuccess()
        check(result, squareList(squareList()))
    }

    @Test
    fun testDeeperSquareNesting() {
        val result = parseComplexLiteral("['a', [['b'], 'c', 'd', ['e', [],], 'f'],]").assumeSuccess()
        check(result, squareList(
                literal("a"),
                squareList(
                        squareList(
                                literal("b")
                        ),
                        literal("c"),
                        literal("d"),
                        squareList(
                                literal("e"),
                                squareList()
                        ),
                        literal("f")
                )
        ))
    }
}

class ComplexLiteralsNegativeTest {
    private fun assertInvalid(complexLiteral: String) {
        val result = parseComplexLiteral(complexLiteral)
        if (result !is ComplexLiteralParsingResult.Failure) {
            Assert.fail("Expected parsing of $complexLiteral to fail, but it passed")
        }
    }

    @Test
    fun testEmptyComplexLiteral() {
        assertInvalid("")
    }

    @Test
    fun testIncompleteSquareList1() {
        assertInvalid("[")
    }

    @Test
    fun testIncompleteSquareList2() {
        assertInvalid("['abc'")
    }

    @Test
    fun testIncompleteSquareList3() {
        assertInvalid("]")
    }

    @Test
    fun testIncompleteSquareList4() {
        assertInvalid("['")
    }

    @Test
    fun testSquareListWithBadCommas1() {
        assertInvalid("[,]")
    }

    @Test
    fun testSquareListWithBadCommas2() {
        assertInvalid("[,'abc']")
    }

    @Test
    fun testSquareListWithBadCommas3() {
        assertInvalid("['hello' 'goodbye']")
    }

    @Test
    fun testSquareListWithBadCommas4() {
        assertInvalid("['hello''goodbye']")
    }

    @Test
    fun testSquareListWithBadCommas5() {
        assertInvalid("['''']")
    }

    @Test
    fun testSquareListWithBadCommas6() {
        assertInvalid("['abc',,]")
    }

    @Test
    fun testSquareListWithBadCommas7() {
        assertInvalid("['abc',,'def']")
    }
}
