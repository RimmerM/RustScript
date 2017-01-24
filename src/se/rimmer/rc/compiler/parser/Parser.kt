package se.rimmer.rc.compiler.parser

import se.rimmer.rc.compiler.Diagnostics
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

class ModuleParser(text: String, diagnostics: Diagnostics): Parser(text, diagnostics) {
    fun parseModule(target: Module) {
        withLevel {
            while(token.type == Token.Type.Semicolon) {
                while(token.type == Token.Type.Semicolon) {
                    eat()
                }

                if(token.type == Token.Type.kwImport) {
                    target.imports.add(parseImport())
                } else {
                    target.decls.add(parseDecl())
                }
            }
        }
    }

    fun parseImport(): Import {
        expect(Token.Type.kwImport, true)
        val name = parseQualified()
        val asName = if(token.type == Token.Type.kwAs) {
            eat()
            parseConID()
        } else name.name

        return Import(name, asName)
    }

    fun parseDecl() = when(token.type) {
        Token.Type.kwData -> parseDataDecl()
        Token.Type.kwType -> parseTypeDecl()
        Token.Type.kwFn -> parseFunDecl()
        Token.Type.kwForeign -> parseForeignDecl()
        else -> throw ParseError("Expected top-level declaration")
    }

    fun parseFunDecl(): FunDecl {
        expect(Token.Type.kwFn, true)
        val name = parseVarID()
        val args = parens { sepBy(Token.Type.Comma) { parseFunArg() } }
        val ret = if(token.type == Token.Type.opArrowR) {
            eat()
            parseType()
        } else {
            null
        }

        expect(Token.Type.opArrowD, true)
        val body = withLevel { parseExpr() }
        return FunDecl(name, args, ret, body)
    }

    fun parseDataDecl(): DataDecl {
        expect(Token.Type.kwData, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals)
        val cons = sepBy1(Token.Type.opBar) {
            val conName = parseConID()
            val content = maybeParens { parseType() }
            Con(conName, content)
        }
        return DataDecl(name, cons)
    }

    fun parseTypeDecl(): TypeDecl {
        expect(Token.Type.kwType, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals, true)
        val type = parseType()
        return TypeDecl(name, type)
    }

    fun parseForeignDecl(): ForeignDecl {
        expect(Token.Type.kwForeign, true)
        if(token.type == Token.Type.kwFn) eat()

        expect(Token.Type.VarID)
        val id = token.idPayload
        eat()
        expect(Token.Type.opColon, true)
        val type = parseType()
        if(token.type == Token.Type.kwAs) {
            eat()
            expect(Token.Type.VarID)
            val asName = token.idPayload
            eat()
            return ForeignDecl(id, asName, type)
        } else {
            return ForeignDecl(id, id, type)
        }
    }

    fun parseExpr(): Expr {
        val rows = sepBy1(Token.Type.Semicolon) { parseTypedExpr() }
        if(rows.size == 1) return rows[0]
        else return MultiExpr(rows)
    }

    fun parseTypedExpr(): Expr {
        val expr = parseInfixExpr()
        if(token.type == Token.Type.opColon) {
            eat()
            val type = parseType()
            return CoerceExpr(expr, type)
        } else {
            return expr
        }
    }

    fun parseInfixExpr(): Expr {
        val lhs = parsePrefixExpr()
        if(token.type == Token.Type.opEquals) {
            eat()
            return AssignExpr(lhs, parseInfixExpr())
        } else if(token.type == Token.Type.VarSym || token.type == Token.Type.Grave) {
            val op = parseQop()
            return InfixExpr(op, lhs, parseInfixExpr())
        } else {
            return lhs
        }
    }

    fun parsePrefixExpr(): Expr {
        if(token.type == Token.Type.VarSym) {
            val op = token.idPayload
            eat()
            val expr = parseLeftExpr()
            return PrefixExpr(op, expr)
        } else {
            return parseLeftExpr()
        }
    }

    fun parseLeftExpr(): Expr {
        if(token.type == Token.Type.kwLet) {
            eat()
            return parseVarDecl(false)
        } else if(token.type == Token.Type.kwVar) {
            eat()
            return parseVarDecl(true)
        } else if(token.type == Token.Type.kwMatch) {
            eat()
            val expr = parseTypedExpr()
            expect(Token.Type.opArrowD, true)
            val alts = withLevel {
                sepBy1(Token.Type.Semicolon) { parseAlt() }
            }
            return CaseExpr(expr, alts)
        } else if(token.type == Token.Type.kwIf) {
            eat()
            if(token.type == Token.Type.opBar) {
                val cases = withLevel {
                    sepBy(Token.Type.Semicolon) {
                        expect(Token.Type.opBar, true)
                        val cond = if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
                            eat()
                            LitExpr(BoolLiteral(true))
                        } else {
                            parseInfixExpr()
                        }

                        expect(Token.Type.opArrowD, true)
                        val then = parseExpr()
                        IfCase(cond, then)
                    }
                }
                return MultiIfExpr(cases)
            } else {
                val cond = parseInfixExpr()

                // Allow statement ends within an if-expression to allow then/else with the same indentation as if.
                if(token.type == Token.Type.Semicolon) eat()
                expect(Token.Type.kwThen, true)
                val then = parseExpr()
                if(token.type == Token.Type.kwElse) {
                    eat()
                    val otherwise = parseExpr()
                    return IfExpr(cond, then, otherwise)
                } else {
                    return IfExpr(cond, then, null)
                }
            }
        } else if(token.type == Token.Type.kwWhile) {
            eat()
            val cond = parseInfixExpr()
            expect(Token.Type.opArrowD, true)
            val body = parseExpr()
            return WhileExpr(cond, body)
        } else {
            return parseFieldExpr()
        }
    }

    fun parseFieldExpr(): Expr {
        val e = parseAppExpr()
        if(token.type == Token.Type.opDot) {
            eat()
            val app = parseSelExpr()
            return FieldExpr(e, app)
        } else {
            return e
        }
    }

    fun parseAppExpr(): Expr {
        val b = parseBaseExpr()
        if(token.type == Token.Type.ParenL) {
            val args = parens { sepBy(Token.Type.Comma) { parseTypedExpr() } }
            return AppExpr(b, args)
        } else {
            return b
        }
    }

    fun parseBaseExpr(): Expr {
        if(token.type == Token.Type.BraceL) {
            return parseTupExpr()
        } else if(token.type == Token.Type.ConID) {
            val type = ConType(parseQualified())
            if(token.type == Token.Type.ParenL) {
                val args = parens { sepBy1(Token.Type.Comma) { parseTypedExpr() } }
                return ConstructExpr(type, args)
            } else if(token.type == Token.Type.BraceL) {
                return ConstructExpr(type, listOf(parseTupExpr()))
            } else {
                return ConstructExpr(type, emptyList())
            }
        } else if(token.type == Token.Type.ParenL) {
            eat()
            val e = parseTypedExpr()

            // Find out if this is a parenthesized or function expression.
            // a comma or `) =>` indicates a function, anything else is either an error or an expression.
            if(token.type == Token.Type.ParenR) {
                eat()
                if(token.type != Token.Type.opArrowD) {
                    return NestedExpr(e)
                }
            }

            val firstArg = if(e is VarExpr) {
                FunArg(e.name, null)
            } else if(e is CoerceExpr && e.target is VarExpr) {
                FunArg(e.target.name, e.type)
            } else {
                throw ParseError("Expected ')' or a function argument")
            }

            val args: List<FunArg>
            if(token.type == Token.Type.Comma) {
                eat()
                args = sepBy(Token.Type.Comma) { parseFunArg() }
                expect(Token.Type.ParenR, true)
                expect(Token.Type.opArrowD, true)
            } else if(token.type == Token.Type.opArrowD) {
                eat()
                args = listOf(firstArg)
            } else {
                throw ParseError("Expected ')' or a function expression")
            }

            return FunExpr(args, parseExpr())
        } else {
            return parseSelExpr()
        }
    }

    fun parseSelExpr(): Expr {
        if(token.kind == Token.Kind.Literal) {
            if(token.type == Token.Type.String) {
                return parseStringExpr()
            } else {
                return LitExpr(parseLiteral())
            }
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            return VarExpr(id)
        } else if(token.type == Token.Type.ParenL) {
            return NestedExpr(parens { parseTypedExpr() })
        } else {
            throw ParseError("Expected an expression")
        }
    }

    fun parseVarDecl(mutable: Boolean): Expr {
        val list = withLevel {
            sepBy1(Token.Type.Semicolon) { parseDeclExpr(mutable) }
        }
        return if(list.size == 1) list[0] else MultiExpr(list)
    }

    fun parseDeclExpr(mutable: Boolean): Expr {
        expect(Token.Type.VarID, false)
        val id = token.idPayload
        eat()

        if(token.type == Token.Type.opEquals) {
            eat()
            return DeclExpr(id, parseExpr(), mutable)
        } else {
            return DeclExpr(id, null, mutable)
        }
    }

    fun parseStringExpr(): Expr {
        expect(Token.Type.String)
        val string = token.idPayload
        eat()

        // Check if the string contains formatting.
        if(token.type == Token.Type.StartOfFormat) {
            val chunks = ArrayList<FormatChunk>()
            chunks.add(FormatChunk(string, null))

            // Parse one or more formatting expressions.
            // The first one consists of just the first string chunk.
            while(token.type == Token.Type.StartOfFormat) {
                eat()
                val expr = parseTypedExpr()
                expect(Token.Type.EndOfFormat, true)
                expect(Token.Type.String, false)
                chunks.add(FormatChunk(token.idPayload, expr))
                eat()
            }
            return FormatExpr(chunks)
        } else {
            return LitExpr(StringLiteral(string))
        }
    }

    fun parseTupExpr(): Expr {
        val args = between(Token.Type.BraceL, Token.Type.BraceR) { sepBy1(Token.Type.Comma) { parseTupArg() } }
        return TupExpr(args)
    }

    fun parseTupArg(): TupArg {
        if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            if(token.type == Token.Type.opEquals) {
                eat()
                return TupArg(id, parseTypedExpr())
            } else {
                return TupArg(null, VarExpr(id))
            }
        } else {
            return TupArg(null, parseTypedExpr())
        }
    }

    fun parseAlt(): Alt {
        val pat = parsePat()
        val alias = if(token.type == Token.Type.opAt) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            id
        } else null

        expect(Token.Type.opArrowD, true)
        return Alt(pat, alias, parseExpr())
    }

    fun parsePat(): Pat {
        if(token.singleMinus) {
            if(token.type == Token.Type.Integer || token.type == Token.Type.Float) {
                return LitPat(parseLiteral().negate())
            } else {
                throw ParseError("Expected integer or float literal")
            }
        } else if(token.type == Token.Type.ConID) {
            val name = parseQualified()
            eat()
            val list = parens { sepBy1(Token.Type.Comma) { parseLPat() } }
            return ConPat(name, list)
        } else {
            return parseLPat()
        }
    }

    fun parseLPat(): Pat {
        if(token.kind == Token.Kind.Literal) {
            return LitPat(parseLiteral())
        } else if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
            eat()
            return AnyPat()
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            return VarPat(id)
        } else if(token.type == Token.Type.ParenL) {
            return parens { parsePat() }
        } else if(token.type == Token.Type.BraceL) {
            val list = between(Token.Type.BraceL, Token.Type.BraceR) {
                sepBy1(Token.Type.Comma) {
                    if(token.type == Token.Type.VarID) {
                        val id = token.idPayload
                        eat()
                        if(token.type == Token.Type.opEquals) {
                            eat()
                            PatternField(id, parsePat())
                        } else {
                            PatternField(null, VarPat(id))
                        }
                    } else {
                        PatternField(null, parsePat())
                    }
                }
            }
            return TupPat(list)
        } else {
            throw ParseError("Expected pattern")
        }
    }

    fun parseType(): Type {
        val args = maybeParens { sepBy(Token.Type.Comma) { parseType() } }
        if(args == null) {
            return parseAType()
        } else {
            expect(Token.Type.opArrowR, true)
            return FunType(args, parseType())
        }
    }

    fun parseAType(): Type {
        if(token.type == Token.Type.ConID) {
            val base = ConType(parseQualified())
            val app = maybeParens { sepBy1(Token.Type.Comma) { parseType() } }
            if(app == null) {
                return base
            } else {
                return AppType(base, app)
            }
        } else if(token.type == Token.Type.VarID) {
            val name = token.idPayload
            eat()
            return GenType(name)
        } else if(token.type == Token.Type.ParenL) {
            return parens { parseType() }
        } else if(token.type == Token.Type.BraceL) {
            return parseTupleType()
        } else {
            throw ParseError("Expected a type")
        }
    }

    fun parseTupleType(): TupType {
        val fields = between(Token.Type.BraceL, Token.Type.BraceR) {
            sepBy1(Token.Type.Comma) {
                if(token.type == Token.Type.VarID) {
                    val name = token.idPayload
                    eat()
                    if(token.type == Token.Type.opColon) {
                        eat()
                        TupField(parseType(), name)
                    } else {
                        TupField(GenType(name), null)
                    }
                } else {
                    TupField(parseType(), null)
                }
            }
        }
        return TupType(fields)
    }

    fun parseQualified(): Qualified {
        val name = parseConID()
        val qualifier = ArrayList<String>()
        while(token.type == Token.Type.opDot) {
            eat()
            qualifier.add(parseConID())
        }
        return Qualified(name, qualifier)
    }

    fun parseFunArg(): FunArg {
        val name = parseVarID()
        expect(Token.Type.opColon, true)
        return FunArg(name, parseType())
    }

    fun parseSimpleType(): SimpleType {
        val name = parseConID()
        val kind = maybeParens { sepBy1(Token.Type.Comma) { parseID() } } ?: emptyList()
        return SimpleType(name, kind)
    }

    fun parseConID(): String {
        expect(Token.Type.ConID)
        val id = token.idPayload
        eat()
        return id
    }

    fun parseVarID(): String {
        // A VarID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseID(): String {
        // An ID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID && token.type != Token.Type.ConID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseQop(): String {
        if(token.type == Token.Type.VarSym) {
            val id = token.idPayload
            eat()
            return id
        } else if(token.type == Token.Type.Grave) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            expect(Token.Type.Grave, true)
            return id
        } else {
            throw ParseError("Expected operator")
        }
    }

    fun parseLiteral(): Literal {
        val literal = when(token.type) {
            Token.Type.Integer -> IntLiteral(BigInteger.valueOf(token.intPayload))
            Token.Type.Float -> RationalLiteral(BigDecimal.valueOf(token.floatPayload))
            Token.Type.Char -> CharLiteral(token.charPayload)
            Token.Type.String -> StringLiteral(token.idPayload)
            else -> throw ParseError("Invalid literal type ${token.type}")
        }
        eat()
        return literal
    }
}