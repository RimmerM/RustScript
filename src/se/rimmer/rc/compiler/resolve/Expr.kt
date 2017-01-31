package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import java.util.*
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.MultiExpr as ASTMultiExpr
import se.rimmer.rc.compiler.parser.LitExpr as ASTLitExpr
import se.rimmer.rc.compiler.parser.AppExpr as ASTAppExpr
import se.rimmer.rc.compiler.parser.FieldExpr as ASTFieldExpr
import se.rimmer.rc.compiler.parser.VarExpr as ASTVarExpr
import se.rimmer.rc.compiler.parser.NestedExpr as ASTNestedExpr
import se.rimmer.rc.compiler.parser.PrefixExpr as ASTPrefixExpr
import se.rimmer.rc.compiler.parser.InfixExpr as ASTInfixExpr
import se.rimmer.rc.compiler.parser.ReturnExpr as ASTReturnExpr
import se.rimmer.rc.compiler.parser.AssignExpr as ASTAssignExpr
import se.rimmer.rc.compiler.parser.DeclExpr as ASTDeclExpr
import se.rimmer.rc.compiler.parser.IfExpr as ASTIfExpr
import se.rimmer.rc.compiler.parser.MultiIfExpr as ASTMultiIfExpr
import se.rimmer.rc.compiler.parser.WhileExpr as ASTWhileExpr
import se.rimmer.rc.compiler.parser.CaseExpr as ASTCaseExpr

fun FunctionBuilder.resolveExpr(ast: ASTExpr, name: String?, resultUsed: Boolean, typeHint: Type?): Value {
    return when(ast) {
        is ASTMultiExpr -> resolveMulti(ast, name, resultUsed, typeHint)
        is ASTLitExpr -> resolveLit(name, ast)
        is ASTAppExpr -> resolveCall(name, ast, typeHint)
        is ASTNestedExpr -> resolveExpr(ast.expr, name, resultUsed, typeHint)
        is ASTPrefixExpr -> resolvePrefix(name, ast, typeHint)
        is ASTInfixExpr -> resolveInfix(name, ast, typeHint)
        is ASTReturnExpr -> resolveReturn(ast)
        is ASTAssignExpr -> resolveAssign(ast)
        is ASTVarExpr -> resolveVar(ast, false)
        is ASTDeclExpr -> resolveDecl(ast)
        is ASTIfExpr -> resolveIf(name, ast, resultUsed, typeHint)
        is ASTMultiIfExpr -> resolveMultiIf(name, ast, resultUsed, typeHint)
        is ASTWhileExpr -> resolveWhile(name, ast)
        is ASTFieldExpr -> resolveField(name, ast)
        else -> throw NotImplementedError()
    }
}

private fun FunctionBuilder.resolveMulti(ast: ASTMultiExpr, name: String?, resultUsed: Boolean, typeHint: Type?): Value {
    if(resultUsed) {
        // Expressions that are part of a statement list are never used, unless they are the last in the list.
        var value: Value? = null
        ast.list.forEachIndexed { i, expr ->
            val last = i == ast.list.size - 1
            val v = resolveExpr(expr, name, if(last) resultUsed else false, if(last) typeHint else null)
            if(last) value = v
        }

        return value!!
    } else {
        ast.list.forEach { resolveExpr(it, null, false, null) }
        return Value(block, null, unitType)
    }
}

private fun FunctionBuilder.resolveReturn(ast: ASTReturnExpr): Value {
    return block.ret(resolveExpr(ast.expr, null, true, null))
}

private fun FunctionBuilder.resolveLit(name: String?, ast: ASTLitExpr): Value {
    val type = when(ast.literal) {
        is IntLiteral -> primitiveTypes[Primitive.Int.ordinal]
        is RationalLiteral -> primitiveTypes[Primitive.Double.ordinal]
        is BoolLiteral -> primitiveTypes[Primitive.Bool.ordinal]
        is StringLiteral -> primitiveTypes[Primitive.String.ordinal]
        is CharLiteral -> primitiveTypes[Primitive.Int.ordinal]
        else -> throw NotImplementedError()
    }
    return Lit(block, name, type, ast.literal)
}

private fun FunctionBuilder.resolveDecl(ast: ASTDeclExpr): Value {
    if(ast.mutable) {
        val value = resolveExpr(ast.content ?: throw NotImplementedError(), null, true, null)
        val ref = block.alloca(ast.name, value.type)
        block.store(null, ref, value)
    } else {
        val content = ast.content ?: throw ResolveError("immutable variables must be initialized")
        resolveExpr(content, ast.name, true, null)
    }
    return Value(block, null, unitType)
}

private fun FunctionBuilder.resolveVar(ast: ASTVarExpr, asRV: Boolean): Value {
    val value = block.findValue(ast.name) ?: throw ResolveError("no variable ${ast.name} found")
    if(!asRV && value.type is RefType) {
        return block.load(null, value)
    } else {
        return value
    }
}

private fun FunctionBuilder.resolveAssign(ast: ASTAssignExpr) = when(ast.target) {
    is ASTVarExpr -> {
        val v = resolveVar(ast.target, true)
        if(v.type !is RefType) {
            throw ResolveError("type is not assignable")
        }
        block.store(null, v, resolveExpr(ast.value, null, true, v.type.to))
    }
    is ASTFieldExpr -> {
        throw NotImplementedError()
    }
    else -> throw ResolveError("assign target is not assignable")
}

private fun FunctionBuilder.resolveIf(name: String?, ast: ASTIfExpr, resultUsed: Boolean, typeHint: Type?): Value {
    val hint = if(resultUsed) typeHint else null
    val cond = resolveExpr(ast.cond, null, resultUsed, booleanType)
    if(!cond.type.isBoolean()) throw ResolveError("if condition must be a boolean")

    val then = function.block()
    val `else` = function.block()

    block.`if`(cond, then, `else`)
    block = then
    val thenValue = resolveExpr(ast.then, null, resultUsed, hint)
    val thenBlock = block

    val elseValue = ast.otherwise?.let {
        block = `else`
        resolveExpr(ast.otherwise, null, resultUsed, hint)
    }

    val elseBlock = ast.otherwise?.let { block }

    if(resultUsed) {
        if(elseValue == null || elseBlock == null || elseBlock.complete || thenBlock.complete) {
            throw ResolveError("if expression doesn't produce a result in every case")
        }

        if(!typesCompatible(thenValue.type, elseValue.type)) {
            throw ResolveError("if and else branches produce differing types")
        }

        val after = function.block()
        block = after
        thenBlock.br(after)
        elseBlock.br(after)
        return after.phi(name, thenValue.type, listOf(thenValue to thenValue.block, elseValue to elseValue.block))
    } else {
        if(elseBlock == null) {
            thenBlock.br(`else`)
            block = `else`
        } else if(!(thenBlock.complete && elseBlock.complete)) {
            val after = function.block()
            block = after
            thenBlock.br(after)
            elseBlock.br(after)
        }
        return Value(thenBlock, null, unitType)
    }
}

private fun FunctionBuilder.resolveMultiIf(name: String?, ast: ASTMultiIfExpr, resultUsed: Boolean, typeHint: Type?): Value {
    val hint = if(resultUsed) typeHint else null
    val results = ArrayList<Pair<Value, Block>>()
    var hasElse = false

    for(it in ast.cases) {
        val cond = resolveExpr(it.cond, null, true, booleanType)
        if(!cond.type.isBoolean()) throw ResolveError("if condition must be a boolean")

        if(alwaysTrue(cond)) {
            val result = resolveExpr(it.then, null, resultUsed, hint)
            results.add(result to block)
            block = function.block()
            hasElse = true
            break
        } else {
            val then = function.block()
            val next = function.block()
            block.`if`(cond, then, next)
            block = then
            val result = resolveExpr(it.then, null, resultUsed, hint)
            results.add(result to block)
            block = next
        }
    }

    val next = block
    if(resultUsed) {
        if(!hasElse) {
            throw ResolveError("if expression doesn't produce a result in every case")
        }

        var prev: Value? = null
        results.forEach { v ->
            v.second.br(next)
            prev?.let {
                if(!typesCompatible(it.type, v.first.type)) {
                    throw ResolveError("if and else branches produce differing types")
                }
            }
            prev = v.first
        }

        prev?.let {
            return next.phi(name, it.type, results)
        }

        throw ResolveError("if expression doesn't produce a result in every case")
    } else {
        if(results.all { it.second.returns }) {
            function.blocks.remove(block)
            block = function.blocks.last()
        } else {
            results.forEach {
                it.second.br(next)
            }
        }
        return Value(block, null, unitType)
    }
}

private fun FunctionBuilder.resolveWhile(name: String?, ast: ASTWhileExpr): Value {
    val condBlock = function.block()
    block.br(condBlock)
    block = condBlock
    val cond = resolveExpr(ast.cond, null, true, booleanType)

    val thenBlock = function.block()
    val quit = function.block()
    block.`if`(cond, thenBlock, quit)
    block = thenBlock
    resolveExpr(ast.content, null, false, null)
    block.br(condBlock)

    block = quit
    return Value(quit, name, unitType)
}

private fun FunctionBuilder.resolveCall(name: String?, ast: ASTAppExpr, typeHint: Type?): Value {
    // If the operand is a field expression we need special handling, since there are several options:
    // - the field operand is an actual field of its target and has a function type, which we call.
    // - the field operand is not a field, and we produce a function call with the target as first parameter.
    if(ast.callee is ASTFieldExpr) {

    }

    val args = ast.args.map { resolveExpr(it, null, true, null) }
    if(ast.callee is ASTVarExpr) {
        testPrimOp(ast.callee.name, name, args)?.let { return it }
    }

    throw NotImplementedError()
}

private fun FunctionBuilder.resolvePrefix(name: String?, ast: ASTPrefixExpr, typeHint: Type?): Value {
    return resolveCall(name, ASTAppExpr(ASTVarExpr(Qualified(ast.op, emptyList(), true)), listOf(ast.arg)), typeHint)
}

private fun FunctionBuilder.resolveInfix(name: String?, ast: ASTInfixExpr, typeHint: Type?): Value {
    val e = if(ast.ordered) ast else reorder(block.function.module, ast, 0)
    return resolveCall(name, ASTAppExpr(ASTVarExpr(Qualified(e.op, emptyList(), true)), listOf(e.lhs, e.rhs)), typeHint)
}

private fun FunctionBuilder.resolveField(name: String?, ast: ASTFieldExpr): Value {
    throw NotImplementedError()
}

private fun FunctionBuilder.testPrimOp(funName: Qualified, name: String?, args: List<Value>): Value? {
    // Check if this can be a primitive operation.
    // Note that primitive operations can be both functions and operators.
    if(args.size == 1) {
        if(args[0].type is PrimType && funName.qualifier.isEmpty()) {
            primUnaryOps[funName.name]?.let {
                return block.primOp(name, it.second, args[0])
            }
        }
    } else if(args.size == 2) {
        val lhs = args[0]
        val rhs = args[1]
        if(lhs.type is PrimType && rhs.type is PrimType && funName.qualifier.isEmpty()) {
            primBinaryOps[funName.name]?.let {
                return block.primOp(name, it.second, lhs, rhs)
            }
        }
    }
    return null
}

private fun opInfo(module: Module, name: Qualified): Operator {
    val op = module.findOperator(name)
    if(op != null) return op

    if(name.qualifier.isEmpty()) {
        val prim = primOps[name.name]
        if(prim != null) return prim.first
    }

    return Operator(9, false)
}

private fun reorder(module: Module, ast: ASTInfixExpr, min: Int): ASTInfixExpr {
    var lhs = ast
    while(lhs.rhs is ASTInfixExpr && !lhs.ordered) {
        val first = opInfo(module, Qualified(lhs.op, emptyList(), true))
        if(first.precedence < min) break

        val rhs = lhs.rhs as ASTInfixExpr
        val second = opInfo(module, Qualified(rhs.op, emptyList(), true))
        if(second.precedence > first.precedence || (second.precedence == first.precedence && second.isRight)) {
            lhs.rhs = reorder(module, rhs, second.precedence)
            if(lhs.rhs == rhs) {
                lhs.ordered = true
                break
            }
        } else {
            lhs.ordered = true
            lhs.rhs = rhs.lhs
            rhs.lhs = lhs
            lhs = rhs
        }
    }
    return lhs
}

private fun alwaysTrue(v: Value) = when(v) {
    is Lit -> v.literal is BoolLiteral && v.literal.v
    else -> false
}