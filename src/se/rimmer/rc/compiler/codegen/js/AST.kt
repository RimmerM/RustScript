package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.parser.Qualified
import java.util.*

class Variable(val fullName: String, var refCount: Int)

interface Expr
class StringExpr(val it: String): Expr
class FloatExpr(val it: Double): Expr
class IntExpr(val it: Int): Expr
class BoolExpr(val it: Boolean): Expr
object NullExpr: Expr
object UndefinedExpr: Expr
class ArrayExpr(val content: List<Expr>): Expr
class ObjectExpr(val values: List<Pair<Expr, Expr>>): Expr
class VarExpr(val v: Variable): Expr
class FieldExpr(val arg: Expr, val field: Expr): Expr
class PrefixExpr(val op: String, val arg: Expr): Expr
class InfixExpr(val op: String, val lhs: Expr, val rhs: Expr): Expr
class IfExpr(val cond: Expr, val then: Expr, val otherwise: Expr): Expr
class AssignExpr(val op: String, val target: Expr, val value: Expr): Expr
class CallExpr(val target: Expr, val args: List<Expr>): Expr
class FunExpr(val name: String?, val args: List<Variable>, val body: List<Stmt>): Expr

interface Stmt
class BlockStmt(val stmts: List<Stmt>): Stmt
class ExprStmt(val expr: Expr): Stmt
class IfStmt(val cond: Expr, val then: Stmt, val otherwise: Stmt?): Stmt
class WhileStmt(val cond: Expr, val body: Stmt): Stmt
class DoWhileStmt(val cond: Expr, val body: Stmt): Stmt
class BreakStmt(val label: String?): Stmt
class ContinueStmt(val label: String?): Stmt
class LabelledStmt(val name: String, val content: Stmt): Stmt
class ReturnStmt(val value: Expr): Stmt
class VarStmt(val values: List<VarDecl>): Stmt
class FunStmt(val name: String, val args: List<Variable>, val body: List<Stmt>): Stmt

class VarDecl(val v: Variable, val value: Expr?): Stmt

class VarScope(val base: String, val parent: VarScope?) {
    private val definedNames = HashSet<String>()
    private var varCounter = 0
    private var funCounter = 0

    fun genVar(name: String?, refCount: Int): Variable {
        val varName = if(name == null) {
            base + "$${varCounter++}"
        } else {
            base + "$$name"
        }
        return Variable(varName, refCount)
    }

    fun genChild(name: String?): VarScope {
        val funName = if(name == null) {
            base + "$${funCounter++}"
        } else {
            base + "$$name"
        }
        return VarScope(funName, this)
    }
}