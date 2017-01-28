package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.DataDecl
import se.rimmer.rc.compiler.parser.Literal
import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.TypeDecl
import java.util.*

data class Scope(val name: Qualified, val parent: Scope?, val function: Function?) {
    val imports = HashMap<List<String>, ImportedScope>()
    val children = HashMap<String, Scope>()
    val shadows = HashMap<String, Var>()
    val captures = HashMap<String, Var>()
    val functions = HashMap<String, FunctionHead>()
    val types = HashMap<String, Type>()
    val constructors = HashMap<String, Constructor>()
    val ops = HashMap<String, Operator>()

    // The variables that have been defined to the current resolving point.
    // Used for lookups to make sure that variables are initialized before use.
    val definedVariables = HashMap<String, Var>()

    // The symbols defined in this module that are visible externally.
    val exportedSymbols = HashSet<String>()

    var codegen: Any? = null
}

data class ImportedScope(val scope: Scope, val qualified: Boolean, val qualifier: List<String>, val localSymbols: Set<String>)

data class Operator(val precedence: Int, val isRight: Boolean)

data class Var(val name: String, val type: Type, val scope: Scope, val isConstant: Boolean, val isArg: Boolean) {
    var codegen: Any? = null
}

val Var.isVar: Boolean get() = !isConstant && !isArg

class FunctionHead {
    val args = ArrayList<Var>()
    var ret: Type? = null
    var body: Function? = null
}

interface Function

class LocalFunction(name: Qualified, parentScope: Scope, val head: FunctionHead): Function {
    val scope = Scope(name, parentScope, null)
    var content: Expr? = null
    var generic = false

    var codegen: Any? = null
}

class ForeignFunction(val name: Qualified, val externalName: String, val head: FunctionHead): Function
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

enum class RecordKind { Enum, Single, Multi }

data class RecordType(var ast: DataDecl?, val scope: Scope): Type {
    var kind = RecordKind.Multi
    val constructors = ArrayList<Constructor>()
}

data class Constructor(val name: Qualified, val index: Int, val parent: RecordType, var content: Type? = null)

data class IfCond(val scope: Expr?, val cond: Expr?)

enum class CondMode { And, Or }

interface ExprKind
data class LitExpr(val literal: Literal): ExprKind
data class MultiExpr(val list: List<Expr>): ExprKind
data class VarExpr(val variable: Var): ExprKind
data class AppExpr(val callee: FunctionHead, val args: List<Expr>): ExprKind
data class PrimOpExpr(val op: PrimOp, val args: List<Expr>): ExprKind
data class ConstructExpr(val constructor: Constructor, val args: List<Expr>): ExprKind
data class AssignExpr(val target: Var, val value: Expr): ExprKind
data class CoerceExpr(val source: Expr): ExprKind
data class CoerceLVExpr(val source: Expr): ExprKind
data class FieldExpr(val container: Expr, val field: Field): ExprKind
data class RetExpr(val value: Expr): ExprKind
data class IfExpr(val conds: List<IfCond>, val then: Expr, val otherwise: Expr?, val mode: CondMode, var alwaysTrue: Boolean): ExprKind
data class WhileExpr(val cond: Expr, val loop: Expr): ExprKind

/**
 * Contains an expression and its metadata.
 * @param used Set when the result of this expression is used by another expression.
 */
class ExprNode<out T: ExprKind>(val kind: T, val type: Type, val used: Boolean)
typealias Expr = ExprNode<ExprKind>
