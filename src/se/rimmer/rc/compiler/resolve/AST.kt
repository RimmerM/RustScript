package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.DataDecl
import se.rimmer.rc.compiler.parser.Literal
import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.TypeDecl
import java.util.*

data class Scope(val name: Qualified, val parent: Scope?, val function: Function?) {
    val imports = HashMap<List<String>, ImportedScope>()
    val children = ArrayList<Scope>()
    val variables = ArrayList<Var>()
    val shadows = ArrayList<Var>()
    val captures = ArrayList<Var>()
    val functions = HashMap<String, FunctionHead>()
    val types = HashMap<String, Type>()
    val constructors = HashMap<String, Constructor>()
    val ops = HashMap<String, Operator>()
}

data class ImportedScope(val scope: Scope, val qualified: Boolean, val qualifier: List<String>)

data class Operator(val precedence: Int, val isRight: Boolean)

data class Var(val name: String, val type: Type, val scope: Scope, val isConstant: Boolean, val isArg: Boolean)
val Var.isVar: Boolean get() = !isConstant && !isArg

data class FunctionHead(val name: String?) {
    val args = ArrayList<Var>()
    var ret: Type? = null
    var body: Function? = null
}

interface Function

class LocalFunction(name: Qualified, scope: Scope, val head: FunctionHead): Function {
    val scope = Scope(name, scope, null)
    var content: Expr? = null
    var generic = false
}

class ForeignFunction(val externalName: String, val head: FunctionHead): Function
class VarFunction(val variable: Var, val head: FunctionHead): Function

interface Type

data class UnknownType(val v: Unit): Type
val unknownType = UnknownType(Unit)

data class UnitType(val v: Unit): Type
val unitType = UnitType(Unit)

data class AliasType(var ast: TypeDecl?, var target: Type, val scope: Scope): Type
data class LVType(val target: Type): Type

/**
 * Generic types are defined by an index to the list of generic parameters of the parent scope.
 * For example, in the type 'Either(a, b)', 'b' is represented with the index 1.
 */
data class GenType(val index: Int): Type

enum class Primitive(val sourceName: kotlin.String) { Int("Int"), Double("Double"), Bool("Bool"), String("String") }
data class PrimType(val prim: Primitive): Type

class Field(val name: String?, val index: Int, val type: Type, val container: Type, val content: Expr?, val constant: Boolean)

data class RecordType(var ast: DataDecl?, val scope: Scope): Type {
    val constructors = ArrayList<Constructor>()
}

data class Constructor(val name: Qualified, val index: Int, val parent: Type, var content: Type? = null)

enum class PrimOp(val sourceName: String, val precedence: Int) {
    // Binary
    Add("+", 11), Sub("-", 11), Mul("*", 12), Div("/", 12), Rem("mod", 12),
    Shl("shl", 10), Shr("shr", 10), And("and", 7), Or("or", 5), Xor("xor", 6),
    CmpEq("==", 8), CmpNeq("!=", 8), CmpGt(">", 9), CmpGe(">=", 9), CmpLt("<", 9), CmpLe("<=", 9),

    // Unary
    Neg("-", 0), Not("!", 0)
}

fun PrimOp.isBinary() = !isUnary()
fun PrimOp.isUnary() = this === PrimOp.Neg || this === PrimOp.Not
fun PrimOp.isAndOr() = this === PrimOp.And || this === PrimOp.Or

interface Expr { val type: Type }
data class LitExpr(val literal: Literal, override val type: Type): Expr
data class MultiExpr(val list: List<Expr>, override val type: Type): Expr
data class VarExpr(val variable: Var, override val type: Type): Expr
data class AppExpr(val callee: FunctionHead, val args: List<Expr>): Expr {override val type: Type get() = callee.ret!!}
data class PrimOpExpr(val op: PrimOp, val args: List<Expr>, override val type: Type): Expr
data class LoadExpr(val target: Expr, override val type: Type): Expr
data class StoreExpr(val target: Expr, val value: Expr): Expr {override val type: Type get() = unitType}
data class AssignExpr(val target: Var, val value: Expr): Expr {override val type: Type get() = unitType}
data class CoerceExpr(val source: Expr, override val type: Type): Expr
data class CoerceLVExpr(val source: Expr, override val type: Type): Expr
data class FieldExpr(val container: Expr, val field: Field, override val type: Type): Expr
data class RetExpr(val value: Expr): Expr {override val type: Type get() = unitType}
