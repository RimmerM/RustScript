package se.rimmer.rc.compiler.parser

import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

class Module(val name: Qualified) {
    val imports = ArrayList<Import>()
    val decls = ArrayList<Decl>()
    val ops = HashMap<String, Fixity>()
}

data class Import(val source: Qualified, val localName: String)

enum class FixityKind { Left, Right, Prefix }
data class Fixity(val kind: FixityKind, val precedence: Int)

data class Qualified(val name: String, val qualifier: List<String>)

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
data class TupType(val fields: List<TupField>): Type
data class AppType(val base: Type, val apps: List<Type>): Type
data class ConType(val name: Qualified): Type
data class GenType(val name: String): Type
data class FunType(val args: List<ArgDecl>, val ret: Type): Type

data class SimpleType(val name: String, val kind: List<String>)
data class TupField(val type: Type, val name: String?)

interface Expr
data class LitExpr(val literal: Literal): Expr
data class NestedExpr(val expr: Expr): Expr
data class MultiExpr(val list: List<Expr>): Expr
data class VarExpr(val name: String): Expr
data class AppExpr(val callee: Expr, val args: List<Expr>): Expr
data class InfixExpr(val op: String, val lhs: Expr, val rhs: Expr): Expr
data class PrefixExpr(val op: String, val callee: Expr): Expr
data class IfExpr(val cond: Expr, val then: Expr, val otherwise: Expr?): Expr
data class MultiIfExpr(val cases: List<IfCase>): Expr
data class DeclExpr(val name: String, val content: Expr?, val mutable: Boolean): Expr
data class WhileExpr(val cond: Expr, val content: Expr): Expr
data class AssignExpr(val target: Expr, val value: Expr): Expr
data class CoerceExpr(val target: Expr, val type: Type): Expr
data class FieldExpr(val target: Expr, val field: Expr): Expr
data class ConstructExpr(val type: Type, val args: List<Expr>): Expr
data class TupExpr(val args: List<TupArg>): Expr
data class FunExpr(val args: List<Arg>, val body: Expr): Expr
data class FormatExpr(val chunks: List<FormatChunk>): Expr
data class CaseExpr(val pivot: Expr, val alts: List<Alt>): Expr

data class IfCase(val cond: Expr, val then: Expr)
data class FormatChunk(val text: String, val format: Expr?)
data class TupArg(val name: String?, val content: Expr)

interface Pat
data class VarPat(val name: String): Pat
data class LitPat(val lit: Literal): Pat
data class TupPat(val fields: List<PatternField>): Pat
data class ConPat(val con: Qualified, val pats: List<Pat>): Pat
data class AnyPat(val v: Unit): Pat

data class PatternField(val field: String?, val pat: Pat)
data class Alt(val pat: Pat, val alias: String?, val body: Expr)

interface Decl
data class FunDecl(val name: String, val args: List<Arg>, val ret: Type?, val body: Expr): Decl
data class TypeDecl(val type: SimpleType, val target: Type): Decl
data class DataDecl(val type: SimpleType, val cons: List<Constructor>): Decl
data class ForeignDecl(val externalName: String, val internalName: String, val type: Type): Decl

data class Constructor(val name: String, val content: Type?)
data class Arg(val name: String, val type: Type?)
data class ArgDecl(val name: String?, val type: Type)
