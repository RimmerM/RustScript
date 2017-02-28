package se.rimmer.rc.compiler.codegen.js

import java.io.Writer

class CodeBuilder(val w: Writer, val minify: Boolean) {
    var indentString = "    "
    var indent = 0

    val space = if(minify) "" else " "
    val comma = if(minify) "," else ", "
    val parenBlockStart = if(minify) "){" else ") {"
    val elseHead = "else$space{"

    inline fun function(name: String?, parameters: List<String>, body: CodeBuilder.() -> Unit) {
        if(name != null) {
            startLine()
            append("function $name(")
        } else {
            append("function(")
        }

        if(parameters.size > 6 && !minify) {
            newLine()
            withLevel {
                startLine()
                sepBy(parameters, ",\n") { append(it) }
            }
            newLine()
            startLine()
        } else if(parameters.isNotEmpty()) {
            sepBy(parameters, comma) { append(it) }
        }

        append(parenBlockStart)
        newLine()
        withLevel(body)
        line('}')
    }

    inline fun call(name: String, args: CodeBuilder.() -> Unit) {
        append(name)
        append('(')
        args()
        append(')')
    }

    inline fun doIf(cond: CodeBuilder.() -> Unit, f: CodeBuilder.() -> Unit) = ifBlock("if", cond, f)
    inline fun doElseif(cond: CodeBuilder.() -> Unit, f: CodeBuilder.() -> Unit) = ifBlock("else if", cond, f)

    inline fun doElse(f: CodeBuilder.() -> Unit) {
        append(elseHead)
        withLevel(f)
        line('}')
    }

    inline fun ifBlock(name: String, cond: CodeBuilder.() -> Unit, f: CodeBuilder.() -> Unit) {
        startLine()
        append(name)
        append('(')
        cond()
        append(parenBlockStart)
        withLevel(f)
        line('}')
    }

    inline fun <E> sepBy(e: Iterable<E>, sep: String, f: (E) -> Unit) {
        if(sep.firstOrNull() == '\n') {
            val s = sep.substring(1)
            iterateLast(e, {f(it); newLine(); startLine(); append(s)}, f)
        } else if(sep.lastOrNull() == '\n') {
            val s = sep.take(sep.length - 1)
            iterateLast(e, {f(it); append(s); newLine(); startLine()}, f)
        } else {
            iterateLast(e, {f(it); append(sep)}, f)
        }
    }

    inline fun withLevel(f: CodeBuilder.() -> Unit) {
        indent += 1
        f()
        indent -= 1
    }

    fun line(s: String) {
        startLine()
        append(s)
        newLine()
    }

    fun line(c: Char) {
        startLine()
        append(c)
        newLine()
    }

    fun newLine() {
        if(!minify) append('\n')
    }

    fun startLine() {
        if(!minify) {
            var i = 0
            while(i < indent) {
                append(indentString)
                i++
            }
        }
    }

    fun append(s: String) {
        w.append(s)
    }

    fun append(c: Char) {
        w.append(c)
    }
}

inline fun <E> iterateLast(e: Iterable<E>, element: (E) -> Unit, last: (E) -> Unit) {
    val i = e.iterator()
    if(i.hasNext()) {
        while(true) {
            val it = i.next()
            if(i.hasNext()) {
                element(it)
            } else {
                last(it)
                break
            }
        }
    }
}