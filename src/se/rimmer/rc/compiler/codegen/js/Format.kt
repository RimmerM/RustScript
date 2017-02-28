package se.rimmer.rc.compiler.codegen.js

fun CodeBuilder.formatFun(name: String?, args: List<Variable>, body: List<Stmt>) {
    function(name, args.map { it.fullName }) {
        body.forEach { formatStmt(it) }
    }
}

fun CodeBuilder.formatStmt(it: Stmt) {
    when(it) {
        is BlockStmt -> {
            line('{')
            withLevel {
                it.stmts.forEach { formatStmt(it) }
            }
            line('}')
        }
        is ExprStmt -> {
            appendExpr(it.expr)
            append(';')
            newLine()
        }
        is IfStmt -> {
            doIf({ appendExpr(it.cond) }, { formatStmt(it.then) })
            if(it.otherwise != null) {
                doElse { formatStmt(it.otherwise) }
            }
        }
        is WhileStmt -> {
            ifBlock("while", { appendExpr(it.cond) }, { formatStmt(it.body) })
        }
        is DoWhileStmt -> {
            line("do$space{")
            withLevel {
                formatStmt(it.body)
            }
            startLine()
            append("while(")
            appendExpr(it.cond)
            append(");")
        }
        is BreakStmt -> {
            line("break;")
        }
        is ContinueStmt -> {
            line("continue;")
        }
        is LabelledStmt -> {
            line("${it.name}:")
            formatStmt(it.content)
        }
        is ReturnStmt -> {
            startLine()
            append("return ")
            appendExpr(it.value)
            append(';')
            newLine()
        }
        is VarStmt -> {
            startLine()
            append("var ")
            iterateLast(it.values, { appendVarDecl(it); append(',') }, { appendVarDecl(it); append(';') })
            newLine()
        }
        is FunStmt -> {
            newLine()
            formatFun(it.name, it.args, it.body)
        }
        is VarDecl -> {
            startLine()
            appendVarDecl(it)
            append(';')
            newLine()
        }
    }
}

fun CodeBuilder.appendExpr(it: Expr) {
    when(it) {
        is StringExpr -> {
            append('"')
            it.it.forEach {
                when(it) {
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(it)
                }
            }
            append('"')
        }
        is FloatExpr -> append(it.it.toString())
        is IntExpr -> append(it.it.toString())
        is BoolExpr -> append(if(it.it) "true" else "false")
        is NullExpr -> append("null")
        is UndefinedExpr -> append("undefined")
        is ArrayExpr -> {
            append('[')
            iterateLast(it.content, { appendExpr(it); append(comma) }, { appendExpr(it) })
            append(']')
        }
        is ObjectExpr -> {
            append('{')
            iterateLast(it.values, { appendObjectKey(it); append(comma) }, { appendObjectKey(it) })
            append('}')
        }
        is VarExpr -> append(it.v.fullName)
        is FieldExpr -> {
            appendExpr(it.arg)
            if(isFieldName(it.field)) {
                append('.')
                append((it.field as StringExpr).it)
            } else {
                append('[')
                appendExpr(it.field)
                append(']')
            }
        }
        is PrefixExpr -> {
            append('(')
            append(it.op)
            appendExpr(it.arg)
            append(')')
        }
        is InfixExpr -> {
            append('(')
            appendExpr(it.lhs)
            append(space)
            append(it.op)
            append(space)
            appendExpr(it.rhs)
            append(')')
        }
        is IfExpr -> {
            append('(')
            appendExpr(it.cond)
            append(space)
            append('?')
            append(space)
            appendExpr(it.then)
            append(space)
            append(':')
            append(space)
            appendExpr(it.otherwise)
            append(')')
        }
        is AssignExpr -> {
            appendExpr(it.target)
            append(space)
            append('=')
            append(space)
            appendExpr(it.value)
        }
        is CallExpr -> {
            appendExpr(it.target)
            append('(')
            iterateLast(it.args, { appendExpr(it); append(comma) }, { appendExpr(it) })
            append(')')
        }
        is FunExpr -> formatFun(it.name, it.args, it.body)
    }
}

private fun CodeBuilder.appendVarDecl(it: VarDecl) {
    append(it.v.fullName)
    if(it.value != null) {
        append("$space=$space")
        appendExpr(it.value)
    }
}

private fun CodeBuilder.appendObjectKey(it: Pair<Expr, Expr>) {
    if(it.first is StringExpr) {
        val s = (it.first as StringExpr).it
        if(isFieldName(it.first)) {
            append(s)
        } else {
            append('"')
            append(s)
            append('"')
        }
    } else {
        append('[')
        appendExpr(it.first)
        append(']')
    }

    append(':')
    append(space)
    appendExpr(it.second)
}

private fun isFieldName(it: Expr) = it is StringExpr && it.it.all { it.isLetterOrDigit() || it == '_' }