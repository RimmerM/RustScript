package se.rimmer.rc.compiler.parser

import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

data class SourceLocation(
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val startOffset: Int,
    val endOffset: Int
)

data class Node<out T>(val ast: T, val location: SourceLocation)

class Module(val name: Qualified) {
    val imports = ArrayList<Node<Import>>()
    val decls = ArrayList<TopDecl>()
    val ops = HashMap<String, Node<Fixity>>()
    val exports = ArrayList<Node<Export>>()
}

data class Import(
    val source: Qualified,
    val qualified: Boolean,
    val localName: String,
    val include: List<String>?,
    val exclude: List<String>?
)

data class Export(val name: Qualified, val qualified: Boolean, val exportName: String)

enum class FixityKind { Left, Right }
data class Fixity(val op: String, val kind: FixityKind, val precedence: Int)

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

interface Type
typealias TypeNode = Node<Type>

data class TupType(val fields: List<TupField>): Type
data class AppType(val base: TypeNode, val apps: List<TypeNode>): Type
data class ConType(val name: Qualified): Type
data class GenType(val name: String): Type
data class FunType(val args: List<ArgDecl>, val ret: TypeNode): Type
data class ArrayType(val type: TypeNode): Type
data class MapType(val from: TypeNode, val to: TypeNode): Type

data class SimpleType(val name: String, val kind: List<String>)
data class TupField(val type: TypeNode, val name: String?, val mutable: Boolean)

interface Expr
typealias ExprNode = Node<Expr>

data class LitExpr(val literal: Literal): Expr
data class NestedExpr(val expr: ExprNode): Expr
data class MultiExpr(val list: List<ExprNode>): Expr
data class VarExpr(val name: Qualified): Expr
data class AppExpr(val callee: ExprNode, val args: List<TupArg>): Expr
data class InfixExpr(val op: String, var lhs: ExprNode, var rhs: ExprNode): Expr {var ordered = false}
data class PrefixExpr(val op: String, val arg: ExprNode): Expr
data class IfExpr(val cond: ExprNode, val then: ExprNode, val otherwise: ExprNode?): Expr
data class MultiIfExpr(val cases: List<IfCase>): Expr
data class DeclExpr(val name: String, val content: ExprNode?, val mutable: Boolean): Expr
data class WhileExpr(val cond: ExprNode, val content: ExprNode): Expr
data class AssignExpr(val target: ExprNode, val value: ExprNode): Expr
data class CoerceExpr(val target: ExprNode, val type: TypeNode): Expr
data class FieldExpr(val target: ExprNode, val field: ExprNode): Expr
data class ConstructExpr(val type: TypeNode, val args: List<ExprNode>): Expr
data class TupExpr(val args: List<TupArg>): Expr
data class TupUpdateExpr(val source: ExprNode, val args: List<TupArg>): Expr
data class ArrayExpr(val values: List<ExprNode>): Expr
data class MapExpr(val pairs: List<Pair<ExprNode, ExprNode>>): Expr
data class FunExpr(val args: List<Arg>, val body: ExprNode): Expr
data class FormatExpr(val chunks: List<FormatChunk>): Expr
data class CaseExpr(val pivot: ExprNode, val alts: List<Alt>): Expr
data class ReturnExpr(val expr: ExprNode): Expr

data class IfCase(val cond: ExprNode, val then: ExprNode)
data class FormatChunk(val text: String, val format: Expr?)
data class TupArg(val name: String?, val content: ExprNode)

interface Pat
typealias PatNode = Node<Pat>

data class VarPat(val name: String): Pat
data class LitPat(val lit: Literal): Pat
data class TupPat(val fields: List<PatternField>): Pat
data class ConPat(val con: Qualified, val pats: List<PatNode>): Pat
data class AnyPat(val v: Unit): Pat

data class PatternField(val field: String?, val pat: PatNode)
data class Alt(val pat: PatNode, val alias: String?, val body: ExprNode)

interface Decl
typealias DeclNode = Node<Decl>
class TopDecl(val decl: DeclNode, val export: Boolean)

data class FunDecl(val name: String, val args: List<Arg>, val ret: TypeNode?, val body: ExprNode): Decl
data class TypeDecl(val type: SimpleType, val target: TypeNode): Decl
data class DataDecl(val type: SimpleType, val cons: List<Node<Constructor>>): Decl
data class ClassDecl(val type: SimpleType, val decls: List<DeclNode>): Decl
data class InstanceDecl(val type: SimpleType, val decls: List<DeclNode>): Decl
data class ForeignDecl(val externalName: String, val internalName: String, val from: String?, val type: TypeNode): Decl

data class Constructor(val name: String, val content: TypeNode?)
data class Arg(val name: String, val type: TypeNode?, val default: ExprNode?)
data class ArgDecl(val name: String?, val type: TypeNode)
