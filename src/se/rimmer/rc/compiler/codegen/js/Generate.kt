package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.resolve.*
import se.rimmer.rc.compiler.resolve.Expr
import se.rimmer.rc.compiler.resolve.AppExpr
import se.rimmer.rc.compiler.resolve.AssignExpr
import se.rimmer.rc.compiler.resolve.CoerceExpr
import se.rimmer.rc.compiler.resolve.ConstructExpr
import se.rimmer.rc.compiler.resolve.FieldExpr
import se.rimmer.rc.compiler.resolve.IfExpr
import se.rimmer.rc.compiler.resolve.LitExpr
import se.rimmer.rc.compiler.resolve.MultiExpr
import se.rimmer.rc.compiler.resolve.VarExpr
import se.rimmer.rc.compiler.resolve.WhileExpr
import java.io.Writer

enum class ImportMode {
    Require, ES6
}

enum class ObjectMode {
    Array, MinifiedObject, FullObject
}

class GenOptions(val minify: Boolean, val importMode: ImportMode, val objectMode: ObjectMode, val comments: Boolean)

class VarGen(val mangledName: String, var defined: Boolean)

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
        scope.imports.forEach { _, import -> genModule(import.scope) }
        scope.children.forEach { genModule(it) }

        scope.functions.forEach { _, f ->
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
                        if(it.scope === scope) defineVar(it, null)
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

    private fun genExpr(expr: Expr, scope: Scope) = when(expr.kind) {
        is MultiExpr -> genMulti(expr, expr.kind, scope)
        is PrimOpExpr -> genPrimOp(expr.kind, scope)
        is LitExpr -> genLit(expr.kind.literal)
        is VarExpr -> genVar(expr.kind.variable)
        is AppExpr -> genApp(expr.kind, scope)
        is RetExpr -> genRet(expr.kind, scope)
        is FieldExpr -> genField(expr.kind, scope)
        is ConstructExpr -> genConstruct(expr.kind, scope)
        is AssignExpr -> genAssign(expr.kind, scope)
        is CoerceExpr -> genCoerce(expr.kind, scope)
        is IfExpr -> genIf(expr, expr.kind, scope)
        is WhileExpr -> genWhile(expr.kind, scope)
        else -> throw NotImplementedError()
    }

    private fun genMulti(node: Expr, expr: MultiExpr, scope: Scope): String {
        if(node.used) {
            var last = ""
            iterateLast(expr.list, {
                b.startLine()
                b.append(genExpr(it, scope))
                b.newLine()
            }, {
                last = genExpr(it, scope)
            })
            return last
        } else {
            expr.list.forEach {
                b.startLine()
                b.append(genExpr(it, scope))
                b.append(';')
                b.newLine()
            }
            return ""
        }
    }

    private fun genField(expr: FieldExpr, scope: Scope): String {
        return "${genExpr(expr.container, scope)}[${expr.field.index}]"
    }

    private fun genRet(expr: RetExpr, scope: Scope): String {
        b.append("return ${genExpr(expr.value, scope)}")

        // Returns are always used as statements.
        return ""
    }

    private fun genConstruct(expr: ConstructExpr, scope: Scope): String {
        when(expr.constructor.parent.kind) {
            RecordKind.Enum -> return expr.constructor.index.toString()
            RecordKind.Single -> {
                val args = expr.args.map { genExpr(it, scope) }
                return args.joinToString(b.comma, "[", "]")
            }
            RecordKind.Multi -> {
                val args = listOf(expr.constructor.index.toString()) + expr.args.map { genExpr(it, scope) }
                return args.joinToString(b.comma, "[", "]")
            }
            else -> throw NotImplementedError()
        }
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
                return "($lhs${b.space}${op.op}${b.space}$rhs)"
            }
            is JsUnaryOp -> {
                val arg = genExpr(expr.args[0], scope)
                return "(${op.op}$arg)"
            }
            is JsFunOp -> {
                genCall(op.name, expr.args, scope)
            }
            else -> throw NotImplementedError()
        }
    }

    private fun genWhile(expr: WhileExpr, scope: Scope): String {
        b.line("while(true) {")
        b.withLevel {
            val cond = genExpr(expr.cond, scope)
            line("if(!$cond) break;")

            val body = genExpr(expr.loop, scope)
            line(body)
            append(';')
        }
        b.line("}")
        return ""
    }

    private fun genIf(node: Expr, expr: IfExpr, scope: Scope): String {
        if(expr.alwaysTrue) {
            val body = genExpr(expr.then, scope)
            if(node.used) {
                return body
            } else {
                b.line(body)
                return ""
            }
        }

        val result = if(node.used) localVar(scope) else null

        expr.conds.forEach {
            it.scope?.let { genExpr(it, scope) }
        }

        val conds = expr.conds.filter { it.cond != null }.map { genExpr(it.cond!!, scope) }
        val op = when(expr.mode) {
            CondMode.Or -> " || "
            CondMode.And -> " && "
        }

        b.doIf(conds.joinToString(op)) {
            genExpr(expr.then, scope)
        }

        if(expr.otherwise != null) {
            b.doElse { genExpr(expr.otherwise, scope) }
        }

        return result ?: ""
    }

    private fun prepareVar(it: Var) {
        if(it.codegen == null) {
            it.codegen = VarGen(localName(it.name, it.scope), false)
        }
    }

    private fun genVar(v: Var): String {
        return v.gen.mangledName
    }

    private fun genAssign(expr: AssignExpr, scope: Scope): String {
        val v = genExpr(expr.value, scope)
        if(expr.target.gen.defined) {
            b.line("${expr.target.gen.mangledName} = $v;")
        } else {
            defineVar(expr.target, v)
        }
        return ""
    }

    private fun genCoerce(expr: CoerceExpr, scope: Scope): String {
        // Implicit conversion - JS doesn't have types.
        return genExpr(expr.source, scope)
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

    private fun localVar(scope: Scope): String {
        val name = localName(null, scope)
        b.line("var $name;")
        return name
    }

    private fun defineVar(it: Var, content: String?) {
        if(content == null) {
            b.line("var ${it.gen.mangledName};")
        } else {
            b.line("var ${it.gen.mangledName} = $content;")
        }
        it.gen.defined = true
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

        val v = n.toString()
        return if(scope.variables.find { it.gen.mangledName == v } != null) {
            localName(name, scope)
        } else {
            n.toString()
        }
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