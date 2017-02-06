package se.rimmer.rc.compiler.parser

import org.junit.Assert
import org.junit.Test
import se.rimmer.rc.compiler.PrintDiagnostics
import java.math.BigDecimal
import java.math.BigInteger

fun parser(text: String): ModuleParser {
    val p = ModuleParser(text, PrintDiagnostics())
    skipWhitespace(p.lexer)
    return p
}

fun skipWhitespace(lexer: Lexer) {
    while(lexer.token.type == Token.Type.Semicolon) lexer.next()
}

fun testExprs(tests: Array<out Pair<String, Expr>>) {
    tests.forEach {
        val p = parser(it.first)
        Assert.assertEquals(it.second, p.parseExpr())
    }
}

fun q(name: String) = Qualified(name, emptyList(), name.firstOrNull()?.isLowerCase() ?: false)
fun v(name: String) = VarExpr(q(name))
fun l(length: Int, offset: Int = 0) = SourceLocation(0, 0, offset, offset + length, offset, offset + length)
fun <T> n(it: T, length: Int, offset: Int = 0) = Node(it, l(length, offset))

val selExprTests = arrayOf(
    /* Literals */
    "6" to LitExpr(IntLiteral(BigInteger.valueOf(6))),
    "1.0" to LitExpr(RationalLiteral(BigDecimal.valueOf(1.0))),
    "'a'" to LitExpr(CharLiteral('a')),
    "'\\n'" to LitExpr(CharLiteral('\n')),
    "\"hello world\"" to LitExpr(StringLiteral("hello world")),

    /* String formatting */
    "\"`a`\"" to FormatExpr(listOf(FormatChunk("", null), FormatChunk("", v("a")))),
    "\"we have `b` cases\"" to FormatExpr(listOf(FormatChunk("we have ", null), FormatChunk(" cases", v("b")))),
    "\"`d`, `c`, `e`\"" to FormatExpr(listOf(FormatChunk("", null), FormatChunk(", ", v("d")), FormatChunk(", ", v("c")), FormatChunk("", v("e")))),

    /* Other cases */
    "my_variable" to v("my_variable"),
    "(v)" to NestedExpr(n(v("v"), 1, 1))
)

val baseExprTests = arrayOf(
    "{0, x}" to TupExpr(listOf(TupArg(null, n(LitExpr(IntLiteral(BigInteger.valueOf(0))), 1, 1)), TupArg(null, n(v("x"), 1, 4)))),
    "{x = 0, y = x}" to TupExpr(listOf(TupArg("x", n(LitExpr(IntLiteral(BigInteger.valueOf(0))), 1, 5)), TupArg("y", n(v("x"), 1, 12)))),
    "Vector" to ConstructExpr(n(ConType(q("Vector")), 6), emptyList()),
    "Vector(a, b)" to ConstructExpr(n(ConType(q("Vector")), 6), listOf(n(v("a"), 1, 7), n(v("b"), 1, 9))),
    "Vector {x = a, y = b}" to ConstructExpr(n(ConType(q("Vector")), 6), listOf(TupExpr(listOf(TupArg("x", v("a")), TupArg("y", v("b")))))),
    "() => x" to FunExpr(emptyList(), n(v("x"), 1, 6)),
    "(a, b) => x" to FunExpr(listOf(Arg("a", null, null), Arg("b", null, null)), n(v("x"), 1, 10)),
    "(a: Int, b: Int) => x" to FunExpr(listOf(Arg("a", ConType(q("Int"))), Arg("b", ConType(q("Int")))), v("x"))
)

val appExprTests = arrayOf(
    "x(y, z)" to AppExpr(v("x"), listOf(v("y"), v("z"))),
    "x(y)" to AppExpr(v("x"), listOf(v("y"))),
    "x y" to AppExpr(v("x"), listOf(v("y"))),
    "x.y" to FieldExpr(v("x"), v("y")),
    "x.0" to FieldExpr(v("x"), LitExpr(IntLiteral(BigInteger.valueOf(0)))),
    "x().y()" to AppExpr(FieldExpr(AppExpr(v("x"), emptyList()), v("y")), emptyList()),
    "x().y().z.w(a)" to AppExpr(FieldExpr(FieldExpr(AppExpr(FieldExpr(AppExpr(v("x"), emptyList()), v("y")), emptyList()), v("z")), v("w")), listOf(v("a")))
)

val leftExprTests = arrayOf(
    "let x = y" to DeclExpr("x", v("y"), false),
    "let mut x = y" to DeclExpr("x", v("y"), true),
    "if x then y else z" to IfExpr(v("x"), v("y"), v("z")),
    "if x then y" to IfExpr(v("x"), v("y"), null),
    "if | x == y -> z\n   | x == z -> y" to MultiIfExpr(listOf(IfCase(InfixExpr("==", v("x"), v("y")), v("z")), IfCase(InfixExpr("==", v("x"), v("z")), v("y")))),
    "while x: print(\"hello\")" to WhileExpr(v("x"), AppExpr(v("print"), listOf(LitExpr(StringLiteral("hello"))))),
    "match x:\n   Vector -> \"hello\"\n   _ -> \"world\"" to CaseExpr(v("x"), listOf(Alt(ConPat(q("Vector"), emptyList()), null, LitExpr(StringLiteral("hello"))), Alt(AnyPat(Unit), null, LitExpr(StringLiteral("world"))))),
    "return x" to ReturnExpr(v("x"))
)

val prefixExprTests = arrayOf(
    "!x" to PrefixExpr("!", v("x")),
    "!x + y" to InfixExpr("+", PrefixExpr("!", v("x")), v("y")),
    "!(x + y)" to PrefixExpr("!", NestedExpr(InfixExpr("+", v("x"), v("y"))))
)

val infixExprTests = arrayOf(
    "4 * 5" to InfixExpr("*", LitExpr(IntLiteral(BigInteger.valueOf(4))), LitExpr(IntLiteral(BigInteger.valueOf(5)))),
    "a * b + c / d + (e() & 1)" to InfixExpr("*", v("a"), InfixExpr("+", v("b"), InfixExpr("/", v("c"), InfixExpr("+", v("d"), NestedExpr(InfixExpr("&", AppExpr(v("e"), emptyList()), LitExpr(IntLiteral(BigInteger.valueOf(1)))))))))
)

val exprTests = arrayOf(
    "x\ny" to MultiExpr(listOf(v("x"), v("y"))),
    "x as List(Int)" to CoerceExpr(v("x"), AppType(ConType(q("List")), listOf(ConType(q("Int")))))
)

val patTests = arrayOf(
    "-5" to LitPat(IntLiteral(BigInteger.valueOf(-5))),
    "Int(0)" to ConPat(q("Int"), listOf(LitPat(IntLiteral(BigInteger.valueOf(0))))),
    "_" to AnyPat(Unit),
    "else" to AnyPat(Unit),
    "(x)" to VarPat("x"),
    "x" to VarPat("x"),
    "{x, y}" to TupPat(listOf(PatternField(null, VarPat("x")), PatternField(null, VarPat("y")))),
    "{x = x, y = y}" to TupPat(listOf(PatternField("x", VarPat("x")), PatternField("y", VarPat("y")))),
    "Vec2 {x = x, y = y}" to ConPat(q("Vec2"), listOf(TupPat(listOf(PatternField("x", VarPat("x")), PatternField("y", VarPat("y"))))))
)

val typeTests = arrayOf(
    "Int" to ConType(q("Int")),
    "Prelude.Core.Int" to ConType(Qualified("Int", listOf("Prelude", "Core"), false)),
    "(Int, Int) -> Int" to FunType(listOf(ArgDecl(null, ConType(q("Int"))), ArgDecl(null, ConType(q("Int")))), ConType(q("Int"))),
    "() -> Int" to FunType(emptyList(), ConType(q("Int"))),
    "List(Int)" to AppType(ConType(q("List")), listOf(ConType(q("Int")))),
    "Maybe [Int]" to AppType(ConType(q("Maybe")), listOf(ArrayType(ConType(q("Int"))))),
    "a" to GenType("a"),
    "{Int, Int}" to TupType(listOf(TupField(ConType(q("Int")), null, false), TupField(ConType(q("Int")), null, false))),
    "{x: Int, y: Int}" to TupType(listOf(TupField(ConType(q("Int")), "x", false), TupField(ConType(q("Int")), "y", false))),
    "[Int]" to ArrayType(ConType(q("Int"))),
    "[Int => Float]" to MapType(ConType(q("Int")), ConType(q("Float")))
)

val declTests = arrayOf(
    "type X(a) = List(a)" to TypeDecl(SimpleType("X", listOf("a")), AppType(ConType(q("List")), listOf(GenType("a")))),
    "data X(a) = X {a: Int}" to DataDecl(SimpleType("X", listOf("a")), listOf(Constructor("X", TupType(listOf(TupField(ConType(q("Int")), "a", false)))))),
    "data X = X | Y | Z(Int)" to DataDecl(SimpleType("X", emptyList()), listOf(Constructor("X", null), Constructor("Y", null), Constructor("Z", ConType(q("Int"))))),
    "fn test(a: Int, b: Int) = a + b" to FunDecl("test", listOf(Arg("a", ConType(q("Int"))), Arg("b", ConType(q("Int")))), null, InfixExpr("+", v("a"), v("b"))),
    "foreign fn createElement(name: String, options: Any) -> Any" to ForeignDecl("createElement", "createElement", FunType(listOf(ArgDecl("name", ConType(q("String"))), ArgDecl("options", ConType(q("Any")))), ConType(q("Any")))),
    "foreign document: Dom.Doc as doc" to ForeignDecl("document", "doc", null, ConType(Qualified("Doc", listOf("Dom"), false)))
)

val importTests = arrayOf(
    "import Dom.Doc as Document" to Import(Qualified("Doc", listOf("Dom"), false), false, "Document", null, null),
    "import Dom" to Import(q("Dom"), false, "Dom", null, null)
)

class ParserTest {
    val intLiterals = arrayOf(23423546, 0, 1, 99999, Long.MAX_VALUE)

    @Test
    fun literalInt() {
        intLiterals.forEach {
            val p = parser(it.toString())
            Assert.assertEquals(IntLiteral(BigInteger.valueOf(it)), p.parseLiteral())
        }
    }

    @Test
    fun literalHex() {
        intLiterals.forEach {
            val p = parser("0x" + java.lang.Long.toHexString(it))
            Assert.assertEquals(IntLiteral(BigInteger.valueOf(it)), p.parseLiteral())
        }
    }

    @Test
    fun literalBin() {
        intLiterals.forEach {
            val p = parser("0b" + java.lang.Long.toBinaryString(it))
            Assert.assertEquals(IntLiteral(BigInteger.valueOf(it)), p.parseLiteral())
        }
    }

    @Test
    fun literalOct() {
        intLiterals.forEach {
            val p = parser("0o" + java.lang.Long.toOctalString(it))
            Assert.assertEquals(IntLiteral(BigInteger.valueOf(it)), p.parseLiteral())
        }
    }

    @Test fun selExpr() { testExprs(selExprTests) }
    @Test fun baseExpr() { testExprs(baseExprTests) }
    @Test fun appExpr() { testExprs(appExprTests) }
    @Test fun leftExpr() { testExprs(leftExprTests) }
    @Test fun prefixExpr() { testExprs(prefixExprTests) }
    @Test fun infixExpr() { testExprs(infixExprTests) }
    @Test fun expr() { testExprs(exprTests) }

    @Test fun pat() {
        patTests.forEach {
            val p = parser(it.first)
            Assert.assertEquals(it.second, p.parsePat())
        }
    }

    @Test fun type() {
        typeTests.forEach {
            val p = parser(it.first)
            Assert.assertEquals(it.second, p.parseType())
        }
    }

    @Test fun decl() {
        declTests.forEach {
            val p = parser(it.first)
            Assert.assertEquals(it.second, p.parseDecl())
        }
    }

    @Test fun import() {
        importTests.forEach {
            val p = parser(it.first)
            Assert.assertEquals(it.second, p.parseImport())
        }
    }
}