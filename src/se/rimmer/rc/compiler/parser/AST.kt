package se.rimmer.rc.compiler.parser

import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

class Loc(var line: Int, var column: Int, var offset: Int)

private val placeholderModule = Qualified("[error]", emptyList(), false)

open class Node(
    var sourceModule: Qualified = placeholderModule,
    val sourceStart: Loc = Loc(0, 0, 0),
    val sourceEnd: Loc = Loc(0, 0, 0)
) {
    fun locationFrom(n: Node) {
        sourceStart.offset = n.sourceStart.offset
        sourceStart.column = n.sourceStart.column
        sourceStart.line = n.sourceStart.line
        sourceEnd.offset = n.sourceEnd.offset
        sourceEnd.line = n.sourceEnd.line
        sourceEnd.column = n.sourceEnd.column
        sourceModule = n.sourceModule
    }

    fun print(): String {
        return "$sourceModule, line ${sourceStart.line}:${sourceStart.column}"
    }
}

class Module(val name: Qualified) {
    val imports = ArrayList<Import>()
    val decls = ArrayList<TopDecl>()
    val ops = HashMap<String, Fixity>()
    val exports = ArrayList<Export>()
}

data class Import(
    val from: Qualified,
    val qualified: Boolean,
    val localName: String,
    val include: List<String>?,
    val exclude: List<String>?
): Node()

data class Export(val name: Qualified, val qualified: Boolean, val exportName: String): Node()

enum class FixityKind { Left, Right }
data class Fixity(val op: VarExpr, val kind: FixityKind, val precedence: Int): Node()

data class Qualified(val name: String, val qualifier: List<String>, val isVar: Boolean) {
    override fun toString() = qualifier.joinToString(".") + '.' + name
}

fun Qualified.extend(name: String) = Qualified(name, qualifier + name, isVar)

interface Literal
data class RationalLiteral(val v: BigDecimal): Literal
data class IntLiteral(val v: BigInteger): Literal
data class CharLiteral(val v: Char): Literal
data class StringLiteral(val v: String): Literal
data class BoolLiteral(val v: Boolean): Literal

fun Literal.negate() = when(this) {
    is RationalLiteral -> RationalLiteral(this.v.negate())
    is IntLiteral -> IntLiteral(this.v.negate())
    else -> this
}

open class Type: Node()
data class TupType(val fields: List<TupField>): Type()
data class AppType(val base: Type, val apps: List<Type>): Type()
data class ConType(val name: Qualified): Type()
data class GenType(val name: String): Type()
data class FunType(val args: List<ArgDecl>, val ret: Type): Type()
data class ArrayType(val type: Type): Type()
data class MapType(val from: Type, val to: Type): Type()

data class SimpleType(val name: String, val kind: List<String>)
data class TupField(val type: Type, val name: String?, val mutable: Boolean)

open class Expr: Node()
data class LitExpr(val literal: Literal): Expr()
data class NestedExpr(val expr: Expr): Expr()
data class MultiExpr(val list: List<Expr>): Expr()
data class VarExpr(val name: Qualified): Expr()
data class AppExpr(val callee: Expr, val args: List<TupArg>): Expr()
data class InfixExpr(val op: VarExpr, var lhs: Expr, var rhs: Expr): Expr() {var ordered = false}
data class PrefixExpr(val op: VarExpr, val arg: Expr): Expr()
data class IfExpr(val cond: Expr, val then: Expr, val otherwise: Expr?): Expr()
data class MultiIfExpr(val cases: List<IfCase>): Expr()
data class DeclExpr(val name: String, val content: Expr?, val mutable: Boolean): Expr()
data class WhileExpr(val cond: Expr, val content: Expr): Expr()
data class AssignExpr(val target: Expr, val value: Expr): Expr()
data class CoerceExpr(val target: Expr, val type: Type): Expr()
data class FieldExpr(val target: Expr, val field: Expr): Expr()
data class ConstructExpr(val con: ConType, val args: List<Expr>): Expr()
data class TupExpr(val args: List<TupArg>): Expr()
data class TupUpdateExpr(val value: Expr, val args: List<TupArg>): Expr()
data class ArrayExpr(val values: List<Expr>): Expr()
data class MapExpr(val pairs: List<Pair<Expr, Expr>>): Expr()
data class FunExpr(val args: List<Arg>, val body: Expr): Expr()
data class FormatExpr(val chunks: List<FormatChunk>): Expr()
data class CaseExpr(val pivot: Expr, val alts: List<Alt>): Expr()
data class ReturnExpr(val expr: Expr): Expr()

data class IfCase(val cond: Expr, val then: Expr)
data class FormatChunk(val text: String, val format: Expr?)
data class TupArg(val name: String?, val content: Expr)

open class Pat: Node()
data class VarPat(val name: String): Pat()
data class LitPat(val lit: Literal): Pat()
data class TupPat(val fields: List<PatternField>): Pat()
data class ConPat(val con: Qualified, val pats: List<Pat>): Pat()
data class AnyPat(val v: Unit): Pat()

data class PatternField(val field: String?, val pat: Pat)
data class Alt(val pat: Pat, val alias: String?, val body: Expr)

open class Decl: Node()
class TopDecl(val it: Decl, val export: Boolean)

data class FunDecl(val name: String, val args: List<Arg>, val ret: Type?, val body: Expr): Decl()
data class TypeDecl(val type: SimpleType, val target: Type): Decl()
data class DataDecl(val type: SimpleType, val cons: List<Con>): Decl()
data class ClassDecl(val type: SimpleType, val decls: List<Decl>): Decl()
data class InstanceDecl(val type: SimpleType, val decls: List<Decl>): Decl()
data class ForeignDecl(val externalName: String, val internalName: String, val from: String?, val type: Type): Decl()

data class Con(val name: String, val content: Type?): Node()
data class Arg(val name: String, val type: Type?, val default: Expr?)
data class ArgDecl(val name: String?, val type: Type)
