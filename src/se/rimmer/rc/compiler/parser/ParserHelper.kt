package se.rimmer.rc.compiler.parser

import se.rimmer.rc.compiler.Diagnostics
import java.util.*

open class Parser(text: String, diagnostics: Diagnostics) {
    inner class ParseError(text: String): Exception("line ${token.sourceLine}, column ${token.sourceColumn}: $text")

    fun <T> withLevel(f: () -> T): T {
        val level = IndentLevel(token, lexer)
        val v = f()
        level.end()
        if(token.type == Token.Type.EndOfBlock) {
            eat()
        }
        return v
    }

    /** Parses an item between two other items. */
    fun <T> between(start: () -> Unit, end: () -> Unit, f: () -> T): T {
        start()
        val v = f()
        end()
        return v
    }

    fun <T> between(start: Token.Type, end: Token.Type, f: () -> T) = between({expect(start, true)}, {expect(end, true)}, f)
    fun <T> maybeBetween(start: Token.Type, end: Token.Type, f: () -> T) = if(token.type == start) between(start, end, f) else null
    fun <T> parens(f: () -> T) = between(Token.Type.ParenL, Token.Type.ParenR, f)
    fun <T> maybeParens(f: () -> T) = maybeBetween(Token.Type.ParenL, Token.Type.ParenR, f)

    /** Parses one or more items from the provided parser. */
    fun <T> many1(f: () -> T): List<T> {
        val first = f()
        val list = ArrayList<T>()
        list.add(first)
        while(true) {
            val v = tryParse(f) ?: break
            list.add(v)
        }
        return list
    }

    /** Parses zero or more items from the provided parser. */
    fun <T> many(f: () -> T): List<T> {
        val list = ArrayList<T>()
        while(true) {
            val v = tryParse(f) ?: break
            list.add(v)
        }
        return list
    }

    /** Parses one or more items separated by the sep predicate. */
    fun <T> sepBy1(sep: () -> Boolean, f: () -> T): List<T> {
        val first = f()
        val list = ArrayList<T>()
        list.add(first)
        while(sep()) {
            list.add(f())
        }
        return list
    }

    /** Parses one or more items separated by the sep token. */
    fun <T> sepBy1(sep: Token.Type, f: () -> T) = sepBy1({
        if(token.type == sep) {
            eat()
            true
        } else false
    }, f)

    /** Parses zero or more items separated by the sep predicate. */
    fun <T> sepBy(sep: () -> Boolean, f: () -> T): List<T> {
        val first = tryParse(f) ?: return emptyList()
        val list = ArrayList<T>()
        list.add(first)
        while(sep()) {
            list.add(f())
        }
        return list
    }

    /** Parses zero or more items separated by the sep token. */
    fun <T> sepBy(sep: Token.Type, f: () -> T) = sepBy({
        if(token.type == sep) {
            eat()
            true
        } else false
    }, f)

    fun <T> tryParse(f: () -> T): T? {
        lastError = null
        val save = SaveLexer(lexer)
        val token = token.copy()
        val v = try {
            f()
        } catch(e: ParseError) {
            save.restore()
            this.token = token
            lexer.token = token
            lastError = e
            return null
        }
        if(v == null) {
            save.restore()
            this.token = token
            lexer.token = token
        }
        return v
    }

    fun require(type: Token.Type) = {
        if(token.type == type) {
            eat(); Unit
        } else throw ParseError("expected $type")
    }

    fun expectConID(id: String, discard: Boolean = false) {
        if(token.type != Token.Type.ConID || token.idPayload != id) {
            throw ParseError("expected conid $id")
        }

        if(discard) eat()
    }

    fun expectVarID(id: String, discard: Boolean = false) {
        if(token.type != Token.Type.VarID || token.idPayload != id) {
            throw ParseError("expected varid $id")
        }

        if(discard) eat()
    }

    fun expect(type: Token.Type, discard: Boolean = false, error: ParseError? = null) {
        if(token.type != type) {
            throw error ?: ParseError("expected $type")
        }

        if(discard) eat()
    }

    fun expect(kind: Token.Kind, discard: Boolean = false) {
        if(token.kind != kind) {
            throw ParseError("expected $kind")
        }

        if(discard) eat()
    }

    // Makes sure that a whole expression was parsed.
    // If not, it is likely that the parsing failed internally and the error should be propagated.
    fun assertEnded() {
        if(token.type !== Token.Type.Semicolon && token.type !== Token.Type.EndOfBlock) {
            throw lastError ?: ParseError("Expected expression end")
        }
    }

    fun eat() = lexer.next()

    var token = Token()
    val lexer = Lexer(text, token, diagnostics)
    var lastError: ParseError? = null

    init {
        lexer.next()
    }
}

class IndentLevel(start: Token, val lexer: Lexer) {
    val previous = lexer.indentation

    init {
        lexer.indentation = start.sourceColumn
        lexer.blockCount++
    }

    fun end() {
        lexer.indentation = previous
        assert(lexer.blockCount > 0)
        lexer.blockCount--
    }
}