package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.resolve.*
import se.rimmer.rc.compiler.resolve.AppExpr
import se.rimmer.rc.compiler.resolve.Expr
import se.rimmer.rc.compiler.resolve.FieldExpr
import se.rimmer.rc.compiler.resolve.LitExpr
import se.rimmer.rc.compiler.resolve.MultiExpr
import se.rimmer.rc.compiler.resolve.VarExpr
import java.io.Writer

enum class ImportMode {
    Require, ES6
}

enum class ObjectMode {
    Array, MinifiedObject, FullObject
}

class GenOptions(val minify: Boolean, val importMode: ImportMode, val objectMode: ObjectMode, val comments: Boolean)

class VarGen(val mangledName: String, var used: Boolean)

class ScopeGen {
    val namer = StringBuilder("a")
}

class FunLocalGen(val mangledName: String)

val Var.gen: VarGen get() = codegen as VarGen
val Scope.gen: ScopeGen get() = codegen as ScopeGen
val LocalFunction.gen: FunLocalGen get() = codegen as FunLocalGen

class Generator(writer: Writer, val options: GenOptions) {
    val b = CodeBuilder(writer, options.minify)

    fun genModule(module: Scope) {
        if(module.codegen == null) {
            prepareScope(module)
            genScope(module)
        }
    }

    private fun prepareScope(scope: Scope) {
        if(scope.codegen == null) {
            scope.codegen = ScopeGen()
            scope.variables.forEach { prepareVar(it) }
        }
    }

    private fun genScope(scope: Scope) {
        scope.imports.forEach { list, import -> genModule(import.scope) }
        scope.children.forEach { genModule(it) }

        scope.functions.forEach { s, f ->
            val body = f.body
            when(body) {
                is LocalFunction -> {
                    // Note: variables in js are function scope,
                    // so we don't actually have to initialize them before defining functions.
                    // However, should we generate ES6 with local bindings in the future, this will need to change.
                    body.scope.captures.forEach {
                        // Define any variables that are used.
                        // Normally variables are defined lazily,
                        // but in the case of local functions they have to be defined in the preceding scope.
                        if(it.scope === scope) defineVar(it)
                    }
                    prepareFun(body)
                    genFun(body)
                }
            }
        }
    }

    private fun prepareFun(f: LocalFunction) {
        if(f.codegen == null) {
            f.codegen = globalName(f.scope.name.name, f.scope)
        }
    }

    private fun genFun(f: LocalFunction) {
        prepareScope(f.scope)
        b.function(f.gen.mangledName, f.head.args.map { it.gen.mangledName }) {
            genScope(f.scope)
            f.content?.let { genExpr(it, f.scope) }
        }
    }

    private fun genExpr(expr: Expr, scope: Scope) = when(expr) {
        is MultiExpr -> genMulti(expr, scope)
        is PrimOpExpr -> genPrimOp(expr, scope)
        is LitExpr -> genLit(expr.literal)
        is VarExpr -> genVar(expr.variable)
        is AppExpr -> genApp(expr, scope)
        is RetExpr -> genRet(expr, scope)
        is FieldExpr -> genField(expr, scope)
        else -> throw NotImplementedError()
    }

    private fun genMulti(expr: MultiExpr, scope: Scope): String {
        expr.list.forEach {
            b.startLine()
            b.append(genExpr(it, scope))
            b.append(';')
            b.newLine()
        }
        return ""
    }

    private fun genField(expr: FieldExpr, scope: Scope): String {
        return "${genExpr(expr.container, scope)}[${expr.field.index}]"
    }

    private fun genRet(expr: RetExpr, scope: Scope): String {
        b.append("return ${genExpr(expr.value, scope)}")

        // Returns are always used as statements.
        return ""
    }

    private fun genApp(expr: AppExpr, scope: Scope): String {
        val body = expr.callee.body
        return when(body) {
            is LocalFunction -> genCall(body.gen.mangledName, expr.args, scope)
            is VarFunction -> genCall(body.variable.gen.mangledName, expr.args, scope)
            else -> throw NotImplementedError()
        }
    }

    private fun genCall(target: String, args: List<Expr>, scope: Scope): String {
        val operands = args.map { genExpr(it, scope) }
        val b = StringBuilder()
        b.append(target)
        b.append('(')
        iterateLast(operands, {b.append(it); b.append(this.b.comma)}, {b.append(it)})
        b.append(')')
        return b.toString()
    }

    private fun genPrimOp(expr: PrimOpExpr, scope: Scope): String {
        val op = jsOps[expr.op] ?: throw NotImplementedError()
        return when(op) {
            is JsBinaryOp -> {
                val lhs = genExpr(expr.args[0], scope)
                val rhs = genExpr(expr.args[1], scope)
                return "$lhs${b.space}${op.op}${b.space}$rhs"
            }
            is JsUnaryOp -> {
                val arg = genExpr(expr.args[0], scope)
                return "${op.op}$arg"
            }
            is JsFunOp -> {
                genCall(op.name, expr.args, scope)
            }
            else -> throw NotImplementedError()
        }
    }

    private fun prepareVar(it: Var) {
        if(it.codegen == null) {
            it.codegen = VarGen(localName(it.name, it.scope), false)
        }
    }

    private fun genVar(v: Var): String {
        return v.gen.mangledName
    }

    private fun genLit(literal: Literal) = when(literal) {
        is IntLiteral -> literal.v.toString(10)
        is RationalLiteral -> literal.v.toString()
        is BoolLiteral -> if(literal.v) "true" else "false"
        is StringLiteral -> genString(literal.v)
        is CharLiteral -> genString(java.lang.String.valueOf(literal.v))
        else -> throw NotImplementedError()
    }

    private fun genString(string: String): String {
        val b = StringBuilder()
        b.append('"')
        string.forEach {
            when(it) {
                '\b' -> b.append("\\b")
                '\t' -> b.append("\\t")
                '\n' -> b.append("\\n")
                0xb.toChar() -> b.append("\\v")
                0xc.toChar() -> b.append("\\f")
                '\r' -> b.append("\\r")
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                else -> b.append(it)
            }
        }
        b.append('"')
        return b.toString()
    }

    private fun defineVar(it: Var) {
        b.line("var ${it.gen.mangledName};")
    }

    private fun globalName(name: String, scope: Scope): String {
        // TODO: Handle minification.
        return name
    }

    private fun localName(name: String?, scope: Scope): String {
        if(!(name == null || options.minify)) return name

        val n = scope.gen.namer
        if(n.length == 1) {
            if(n.last() == 'z') {
                n.setLast('A')
            } else if(n.last() == 'Z') {
                n.append('a')
            } else {
                n.increment()
            }
        } else if(n.last() == 'z') {
            n.setLast('A')
        } else if(n.last() == 'Z') {
            n.setLast('0')
        } else if(n.last() == '9') {
            n.append('a')
        } else {
            n.increment()
        }
        return n.toString()
    }
}

fun StringBuilder.increment() { setLast(last() + 1) }
fun StringBuilder.setLast(v: Char) { replace(length - 1, length, java.lang.String.valueOf(v)) }

interface JsOp
class JsUnaryOp(val op: String): JsOp
class JsBinaryOp(val op: String): JsOp
class JsFunOp(val name: String): JsOp

val jsOps = mapOf(
    PrimOp.Add to JsBinaryOp("+"),
    PrimOp.Sub to JsBinaryOp("-"),
    PrimOp.Mul to JsBinaryOp("*"),
    PrimOp.Div to JsBinaryOp("/"),
    PrimOp.Rem to JsBinaryOp("%"),
    PrimOp.Shl to JsBinaryOp("<<"),
    PrimOp.Shr to JsBinaryOp(">>"),
    PrimOp.And to JsBinaryOp("&"),
    PrimOp.Or to JsBinaryOp("|"),
    PrimOp.Xor to JsBinaryOp("^"),
    PrimOp.CmpEq to JsBinaryOp("==="),
    PrimOp.CmpNeq to JsBinaryOp("!=="),
    PrimOp.CmpGt to JsBinaryOp(">"),
    PrimOp.CmpGe to JsBinaryOp(">="),
    PrimOp.CmpLt to JsBinaryOp("<"),
    PrimOp.CmpLe to JsBinaryOp("<="),

    PrimOp.Neg to JsUnaryOp("-"),
    PrimOp.Not to JsUnaryOp("!")
)